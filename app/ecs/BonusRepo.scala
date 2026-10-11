package ecs

import play.api.libs.json.{JsObject, Json}

import java.sql.Connection
import javax.inject.{Inject, Singleton}

/**
 * Cash coupons / red packets (PHP `user.php?act=bonus|act_add_bonus`).
 *
 * A coupon is claimed by a conditional update on `user_id = 0`, so two concurrent
 * requests for the same serial can never both succeed. Statuses are derived from
 * the database clock, never from a client timestamp.
 */
@Singleton
class BonusRepo @Inject() (db: Db):

  private val Join =
    """FROM user_bonus ub
      |JOIN bonus_type bt ON bt.type_id = ub.bonus_type_id""".stripMargin

  private def statusOf(row: Row, now: Long): String =
    if row.long("used_time") > 0 || row.long("order_id") > 0 then "used"
    else if row.long("use_start_date") > now then "not_started"
    else if row.long("use_end_date") != 0 && row.long("use_end_date") < now then "expired"
    else "available"

  private def project(row: Row, now: Long): JsObject =
    Json.obj(
      "id"                -> row.long("bonus_id"),
      "bonus_sn"          -> row.str("bonus_sn"),
      "type_id"           -> row.long("bonus_type_id"),
      "name"              -> row.str("type_name"),
      "amount"            -> ApiJson.money(row.dec("type_money")),
      "min_amount"        -> ApiJson.money(row.dec("min_amount")),
      "max_amount"        -> ApiJson.money(row.dec("max_amount")),
      "min_goods_amount"  -> ApiJson.money(row.dec("min_goods_amount")),
      "use_start_time"    -> row.long("use_start_date"),
      "use_end_time"      -> row.long("use_end_date"),
      "status"            -> statusOf(row, now),
      "order_id"          -> row.long("order_id"),
      "used_time"         -> row.long("used_time")
    )

  /** `GET /api/v1/me/bonuses` — only coupons already claimed by this user. */
  def mine(conn: Connection, userId: Long, page: Paging.Page): JsObject =
    val now = ApiJson.nowUnix
    val total = db.count(conn, s"SELECT COUNT(*) $Join WHERE ub.user_id = ?", Seq(userId))
    val rows = db.rows(conn,
      s"""SELECT ub.bonus_id, ub.bonus_sn, ub.user_id, ub.used_time, ub.order_id,
         |  bt.type_id AS bonus_type_id, bt.type_name, bt.type_money, bt.min_amount, bt.max_amount,
         |  bt.use_start_date, bt.use_end_date, bt.min_goods_amount
         |$Join WHERE ub.user_id = ?
         |ORDER BY ub.bonus_id DESC
         |LIMIT ? OFFSET ?""".stripMargin, Seq(userId, page.pageSize, page.offset))
    ApiJson.page(page.page, page.pageSize, total, rows.map(project(_, now)))

  /**
   * `POST /api/v1/me/bonuses/claim` — the row is locked, checked and then claimed
   * with `user_id = 0` still in the WHERE clause.
   */
  def claim(conn: Connection, userId: Long, bonusSn: Long): Either[BonusFailure, Unit] =
    val now = ApiJson.nowUnix
    db.row(conn,
      s"""SELECT ub.user_id, bt.use_end_date $Join WHERE ub.bonus_sn = ?${Sql.forUpdate(db.dialect)}""".stripMargin,
      Seq(bonusSn)) match
      case None => Left(BonusFailure.NotFound)
      case Some(row) if row.long("user_id") != 0 => Left(BonusFailure.AlreadyClaimed)
      case Some(row) if row.long("use_end_date") != 0 && row.long("use_end_date") < now =>
        Left(BonusFailure.Expired)
      case Some(_) =>
        val taken = db.execute(conn,
          "UPDATE user_bonus SET user_id = ? WHERE bonus_sn = ? AND user_id = 0", Seq(userId, bonusSn))
        if taken == 1 then Right(()) else Left(BonusFailure.AlreadyClaimed)

/** Why a coupon claim was refused (docs/03_api_contract.md). */
enum BonusFailure:
  case NotFound, AlreadyClaimed, Expired
