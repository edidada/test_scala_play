package ecs

import play.api.libs.json.{JsObject, Json}

import java.sql.Connection
import javax.inject.{Inject, Singleton}

/**
 * Payment gateway settlement (PHP `respond.php`).
 *
 * The callback is the only write that has no authenticated user, so everything it
 * touches is looked up by `order_sn` and guarded by the order's own status. The unique
 * `(provider, provider_trade_no)` key in `pay_log` is what makes a replay safe: the
 * insert is ignored when the trade was already recorded, and only the transaction that
 * actually inserted the row is allowed to flip the order to `paid`.
 */
@Singleton
class PaymentCallbackRepo @Inject() (db: Db):

  private val Pending = "pending_payment"
  private val Paid    = "paid"

  private val SeenSql =
    """SELECT l.order_id, l.amount, o.order_sn
      |FROM pay_log l JOIN order_info o ON o.order_id = l.order_id
      |WHERE l.provider = ? AND l.provider_trade_no = ?""".stripMargin

  private def decimal(cents: Long): BigDecimal = BigDecimal(cents) / 100

  private def document(orderId: Long, orderSn: String, tradeNo: String, amountCents: Long,
    replayed: Boolean): JsObject =
    Json.obj(
      "order_id"   -> orderId,
      "order_sn"   -> orderSn,
      "trade_no"   -> tradeNo,
      "amount"     -> ApiJson.money(amountCents),
      "status"     -> Paid,
      "pay_status" -> Paid,
      "replayed"   -> replayed
    )

  /**
   * Record one gateway notification.
   *
   * `amountCents` must equal the order's current `order_amount`; anything else is a 409
   * rather than a partial settlement. A repeated notification for the same trade, order
   * and amount is reported as a replay; the same trade number used for a different
   * order or amount is refused.
   */
  def settle(conn: Connection, provider: String, tradeNo: String, orderSn: String,
    amountCents: Long, rawPayload: String): Either[CallbackFailure, JsObject] =
    db.row(conn, SeenSql, Seq(provider, tradeNo)) match
      case Some(seen) if seen.str("order_sn") == orderSn && CartRepo.toCents(seen.dec("amount")) == amountCents =>
        Right(document(seen.long("order_id"), orderSn, tradeNo, amountCents, replayed = true))
      case Some(_) => Left(CallbackFailure.TradeConflict)
      case None =>
        db.row(conn,
          s"""SELECT order_id, order_sn, order_status, order_amount FROM order_info
             |WHERE order_sn = ?${Sql.forUpdate(db.dialect)}""".stripMargin, Seq(orderSn)) match
          case None => Left(CallbackFailure.OrderNotFound)
          case Some(order) =>
            val orderId = order.long("order_id")
            if CartRepo.toCents(order.dec("order_amount")) != amountCents then Left(CallbackFailure.AmountMismatch)
            else if order.str("order_status") != Pending then Left(CallbackFailure.InvalidState)
            else
              val inserted = db.execute(conn,
                Sql.insertIgnore(db.dialect) +
                  """pay_log (order_id, provider, provider_trade_no, amount, status, raw_payload, received_at)
                    |VALUES (?, ?, ?, ?, 'paid', ?, ?)""".stripMargin,
                Seq(orderId, provider, tradeNo, decimal(amountCents), rawPayload, ApiJson.nowUtc))
              if inserted != 1 then
                // Lost the race to an identical notification that has now committed.
                db.row(conn, SeenSql, Seq(provider, tradeNo)) match
                  case Some(seen) if seen.str("order_sn") == orderSn &&
                    CartRepo.toCents(seen.dec("amount")) == amountCents =>
                    Right(document(orderId, orderSn, tradeNo, amountCents, replayed = true))
                  case _ => throw Abort(CallbackFailure.TradeConflict)
              else
                if db.execute(conn,
                  """UPDATE order_info SET order_status = 'paid', pay_status = 'paid', pay_time = ?
                    |WHERE order_id = ? AND order_status = 'pending_payment'""".stripMargin,
                  Seq(ApiJson.nowUtc, orderId)) != 1 then throw Abort(CallbackFailure.InvalidState)
                db.execute(conn,
                  """INSERT INTO order_action (order_id, actor_type, actor_id, action_note, created_at)
                    |VALUES (?, 'system', NULL, ?, ?)""".stripMargin,
                  Seq(orderId, s"payment confirmed by $provider trade $tradeNo", ApiJson.nowUtc))
                Right(document(orderId, orderSn, tradeNo, amountCents, replayed = false))

/** Why a gateway notification was refused; each case maps to one HTTP status. */
enum CallbackFailure:
  case OrderNotFound, AmountMismatch, InvalidState, TradeConflict
