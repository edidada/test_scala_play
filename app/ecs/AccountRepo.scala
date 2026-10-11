package ecs

import play.api.libs.json.{JsObject, Json}

import java.sql.Connection
import javax.inject.{Inject, Singleton}

/**
 * Balance top-ups and withdrawals (PHP `user.php?act=act_account|account_log|cancel|account_detail|pay`).
 *
 * Money lives in integer cents, so a request can never lose precision on the way
 * in or out. Freezing a withdrawal and writing the ledger entry happen in the same
 * transaction as the request row, and the balance update carries its own guard
 * (`available_cents >= ?`), which is what stops a concurrent double spend.
 */
@Singleton
class AccountRepo @Inject() (db: Db):

  private val RequestColumns =
    "rec_id, user_id, amount_cents, process_type, payment_id, user_note, admin_note, status, created_at, paid_at"

  private def centsToDecimal(cents: Long): BigDecimal = BigDecimal(cents) / 100

  private def requestProject(row: Row): JsObject =
    Json.obj(
      "id"         -> row.long("rec_id"),
      "kind"       -> row.str("process_type"),
      "amount"     -> ApiJson.money(row.long("amount_cents")),
      "payment_id" -> row.optLong("payment_id"),
      "status"     -> row.str("status"),
      "note"       -> row.str("user_note"),
      "admin_note" -> row.str("admin_note"),
      "created_at" -> Views.timestamp(row, "created_at"),
      "paid_at"    -> row.optStr("paid_at").getOrElse("")
    )

  private def balancePair(conn: Connection, userId: Long): (Long, Long) =
    db.row(conn, "SELECT available_cents, frozen_cents FROM account_balance WHERE user_id = ?", Seq(userId))
      .map(r => (r.long("available_cents"), r.long("frozen_cents")))
      .getOrElse((0L, 0L))

  /** Balance row must exist before a guarded update can freeze anything. */
  private def ensureBalance(conn: Connection, userId: Long): Unit =
    db.execute(conn,
      Sql.insertIgnore(db.dialect) + "account_balance (user_id, available_cents, frozen_cents) VALUES (?, 0, 0)",
      Seq(userId))

  private def log(conn: Connection, userId: Long, availableDelta: Long, frozenDelta: Long,
    reason: String, referenceId: Long): Unit =
    db.execute(conn,
      """INSERT INTO account_log (user_id, available_delta_cents, frozen_delta_cents, reason,
        |  reference_type, reference_id, created_at)
        |VALUES (?, ?, ?, ?, 'user_account', ?, ?)""".stripMargin,
      Seq(userId, availableDelta, frozenDelta, reason, referenceId, ApiJson.nowUtc))

  /** `GET /api/v1/me/account/requests` — the request list plus the current balances. */
  def listRequests(conn: Connection, userId: Long, page: Paging.Page): JsObject =
    val total = db.count(conn, "SELECT COUNT(*) FROM user_account WHERE user_id = ?", Seq(userId))
    val rows = db.rows(conn,
      s"""SELECT $RequestColumns FROM user_account WHERE user_id = ?
         |ORDER BY rec_id DESC
         |LIMIT ? OFFSET ?""".stripMargin, Seq(userId, page.pageSize, page.offset))
    val (available, frozen) = balancePair(conn, userId)
    ApiJson.page(page.page, page.pageSize, total, rows.map(requestProject)) ++ Json.obj(
      "available_balance" -> ApiJson.money(centsToDecimal(available)),
      "frozen_balance"    -> ApiJson.money(centsToDecimal(frozen))
    )

  /**
   * `POST /api/v1/me/account/requests`.
   *
   * A deposit waits for a payment; a withdrawal immediately moves money from
   * available to frozen inside the same guarded update.
   */
  def createRequest(conn: Connection, userId: Long, kind: String, amountCents: Long,
    paymentId: Option[Long], note: String): Either[AccountFailure, JsObject] =
    kind match
      case "deposit" =>
        paymentId match
          case None => Left(AccountFailure.PaymentRequired)
          case Some(pid) if !paymentEnabled(conn, pid) => Left(AccountFailure.PaymentUnavailable)
          case Some(pid) =>
            val id = db.insertReturningKey(conn,
              """INSERT INTO user_account (user_id, amount_cents, process_type, payment_id, user_note, status, created_at)
                |VALUES (?, ?, 'deposit', ?, ?, 'pending_payment', ?)""".stripMargin,
              Seq(userId, amountCents, pid, note, ApiJson.nowUtc))
            Right(request(conn, id))
      case "withdrawal" =>
        ensureBalance(conn, userId)
        val frozen = db.execute(conn,
          """UPDATE account_balance SET available_cents = available_cents - ?, frozen_cents = frozen_cents + ?
            |WHERE user_id = ? AND available_cents >= ?""".stripMargin,
          Seq(amountCents, amountCents, userId, amountCents))
        if frozen != 1 then Left(AccountFailure.InsufficientBalance)
        else
          val id = db.insertReturningKey(conn,
            """INSERT INTO user_account (user_id, amount_cents, process_type, user_note, status, created_at)
              |VALUES (?, ?, 'withdrawal', ?, 'pending_review', ?)""".stripMargin,
            Seq(userId, amountCents, note, ApiJson.nowUtc))
          log(conn, userId, -amountCents, amountCents, "withdrawal request", id)
          Right(request(conn, id))
      case other => Left(AccountFailure.UnknownKind(other))

  private def paymentEnabled(conn: Connection, payId: Long): Boolean =
    db.row(conn, "SELECT enabled FROM payment WHERE pay_id = ?", Seq(payId)).exists(_.bool("enabled"))

  private def request(conn: Connection, recId: Long): JsObject =
    requestProject(db.row(conn, s"SELECT $RequestColumns FROM user_account WHERE rec_id = ?", Seq(recId)).get)

  /** `DELETE /api/v1/me/account/requests/{id}` — pending rows only, owner only. */
  def cancelRequest(conn: Connection, userId: Long, recId: Long): Either[AccountFailure, Unit] =
    db.row(conn,
      s"""SELECT $RequestColumns FROM user_account WHERE rec_id = ? AND user_id = ?${Sql.forUpdate(db.dialect)}"""
        .stripMargin, Seq(recId, userId)) match
      case None => Left(AccountFailure.NotFound)
      case Some(row) if row.str("status") != "pending_payment" && row.str("status") != "pending_review" =>
        Left(AccountFailure.WrongState)
      case Some(row) =>
        if row.str("process_type") == "withdrawal" then
          val cents = row.long("amount_cents")
          db.execute(conn,
            """UPDATE account_balance SET available_cents = available_cents + ?, frozen_cents = frozen_cents - ?
              |WHERE user_id = ? AND frozen_cents >= ?""".stripMargin, Seq(cents, cents, userId, cents))
          log(conn, userId, cents, -cents, "withdrawal cancelled", recId)
        if db.execute(conn,
          "DELETE FROM user_account WHERE rec_id = ? AND user_id = ?", Seq(recId, userId)) == 1 then Right(())
        else Left(AccountFailure.NotFound)

  /** `GET /api/v1/me/account/transactions` — the integer-cent ledger and balances. */
  def transactions(conn: Connection, userId: Long, page: Paging.Page): JsObject =
    val total = db.count(conn, "SELECT COUNT(*) FROM account_log WHERE user_id = ?", Seq(userId))
    val rows = db.rows(conn,
      """SELECT log_id, available_delta_cents, frozen_delta_cents, reason, reference_type, reference_id, created_at
        |FROM account_log WHERE user_id = ?
        |ORDER BY log_id DESC
        |LIMIT ? OFFSET ?""".stripMargin, Seq(userId, page.pageSize, page.offset))
    val (available, frozen) = balancePair(conn, userId)
    ApiJson.page(page.page, page.pageSize, total, rows.map(r => Json.obj(
      "id"               -> r.long("log_id"),
      "available_change" -> ApiJson.money(centsToDecimal(r.long("available_delta_cents"))),
      "frozen_change"    -> ApiJson.money(centsToDecimal(r.long("frozen_delta_cents"))),
      "reason"           -> r.str("reason"),
      "reference_type"   -> r.str("reference_type"),
      "reference_id"     -> r.long("reference_id"),
      "created_at"       -> Views.timestamp(r, "created_at")
    ))) ++ Json.obj(
      "available_balance" -> ApiJson.money(centsToDecimal(available)),
      "frozen_balance"    -> ApiJson.money(centsToDecimal(frozen))
    )

  /**
   * `POST /api/v1/me/account/requests/{id}/payment`.
   *
   * The intent is keyed by `request_id`, so repeating the call updates the same
   * pending payment instead of creating a second one.
   */
  def payRequest(conn: Connection, userId: Long, recId: Long, paymentId: Long): Either[AccountFailure, JsObject] =
    db.row(conn,
      s"""SELECT $RequestColumns FROM user_account WHERE rec_id = ? AND user_id = ?
         |  AND process_type = 'deposit' AND status = 'pending_payment'${Sql.forUpdate(db.dialect)}"""
        .stripMargin, Seq(recId, userId)) match
      case None => Left(AccountFailure.NotFound)
      case Some(row) =>
        db.row(conn, "SELECT pay_fee FROM payment WHERE pay_id = ? AND enabled = 1", Seq(paymentId)) match
          case None => Left(AccountFailure.PaymentUnavailable)
          case Some(pay) =>
            val amount = row.long("amount_cents")
            val feeCents = (pay.dec("pay_fee") * 100).setScale(0, BigDecimal.RoundingMode.HALF_UP).toLong
            db.execute(conn, "UPDATE user_account SET payment_id = ? WHERE rec_id = ? AND user_id = ?",
              Seq(paymentId, recId, userId))
            val existing = db.row(conn,
              "SELECT intent_id FROM account_payment_intent WHERE request_id = ?", Seq(recId))
            existing match
              case Some(intent) =>
                db.execute(conn,
                  """UPDATE account_payment_intent SET payment_id = ?, amount_cents = ?, fee_cents = ?, updated_at = ?
                    |WHERE intent_id = ?""".stripMargin,
                  Seq(paymentId, amount, feeCents, ApiJson.nowUtc, intent.long("intent_id")))
              case None =>
                db.execute(conn,
                  """INSERT INTO account_payment_intent (request_id, user_id, payment_id, amount_cents, fee_cents,
                    |  status, created_at, updated_at)
                    |VALUES (?, ?, ?, ?, ?, 'pending', ?, ?)""".stripMargin,
                  Seq(recId, userId, paymentId, amount, feeCents, ApiJson.nowUtc, ApiJson.nowUtc))
            Right(Json.obj(
              "request_id"  -> recId,
              "intent_id"   -> existing.map(_.long("intent_id"))
                .orElse(db.row(conn, "SELECT intent_id FROM account_payment_intent WHERE request_id = ?", Seq(recId)).map(_.long("intent_id")))
                .getOrElse(0L),
              "payment_id"  -> paymentId,
              "amount"      -> ApiJson.money(centsToDecimal(amount)),
              "fee"         -> ApiJson.money(centsToDecimal(feeCents)),
              "total"       -> ApiJson.money(centsToDecimal(amount + feeCents)),
              "status"      -> "pending"
            ))

/** Why a balance request was refused; each case maps to one HTTP status. */
enum AccountFailure:
  case PaymentRequired, PaymentUnavailable, InsufficientBalance, WrongState, NotFound
  case UnknownKind(kind: String)
