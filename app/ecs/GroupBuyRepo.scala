package ecs

import play.api.libs.json.{JsObject, Json}

import java.sql.Connection
import javax.inject.{Inject, Singleton}

/**
 * Group-buy history (PHP `user.php?act=group_buy|group_buy_detail`).
 *
 * Only orders explicitly linked through `order_promotion` count as group buys, and
 * the linked activity must still be `act_type = 1`. Historical rows are not filtered
 * by the activity time window, because a finished group buy stays in the buyer's
 * history (docs/09_url_completion_matrix.md).
 */
@Singleton
class GroupBuyRepo @Inject() (db: Db):

  private val From =
    """FROM order_promotion op
      |JOIN order_info o ON o.order_id = op.order_id
      |JOIN goods_activity a ON a.act_id = op.promotion_id AND a.act_type = 1
      |WHERE o.user_id = ? AND op.promotion_type = 'group_buy'""".stripMargin

  private val Select =
    """SELECT o.order_id, o.order_sn, o.order_status, o.goods_amount, o.order_amount, o.add_time,
      |  a.act_id, a.act_name, a.start_time, a.end_time, a.is_finished""".stripMargin + "\n" + From

  private def orderRow(row: Row): JsObject =
    Json.obj(
      "order_id"     -> row.long("order_id"),
      "order_sn"     -> row.str("order_sn"),
      "status"       -> row.str("order_status"),
      "goods_amount" -> ApiJson.money(row.dec("goods_amount")),
      "order_amount" -> ApiJson.money(row.dec("order_amount")),
      "created_at"   -> Views.timestamp(row, "add_time")
    )

  private def activityRow(row: Row): JsObject =
    Json.obj(
      "id"          -> row.long("act_id"),
      "name"        -> row.str("act_name"),
      "start_time"  -> row.long("start_time"),
      "end_time"    -> row.long("end_time"),
      "is_finished" -> row.bool("is_finished")
    )

  /** `GET /api/v1/me/group-buys` — every group-buy order of the caller. */
  def mine(conn: Connection, userId: Long, page: Paging.Page): JsObject =
    val total = db.count(conn, s"SELECT COUNT(*) $From", Seq(userId))
    val rows = db.rows(conn,
      s"""$Select
         |ORDER BY o.order_id DESC
         |LIMIT ? OFFSET ?""".stripMargin, Seq(userId, page.pageSize, page.offset))
    ApiJson.page(page.page, page.pageSize, total, rows.map(r => activityRow(r) ++ Json.obj("order" -> orderRow(r))))

  /**
   * `GET /api/v1/me/group-buys/{id}` — `id` is the activity id; an activity the
   * caller never ordered through is a 404 like somebody else's record.
   */
  def detail(conn: Connection, userId: Long, actId: Long): Either[OrderFailure, JsObject] =
    val rows = db.rows(conn,
      s"""$Select AND a.act_id = ?
         |ORDER BY o.order_id DESC""".stripMargin, Seq(userId, actId))
    rows match
      case Nil => Left(OrderFailure.NotFound)
      case head :: _ =>
        Right(Json.obj(
          "activity" -> activityRow(head),
          "orders"   -> Json.toJson(rows.map(orderRow))
        ))
