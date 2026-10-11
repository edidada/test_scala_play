package ecs

import play.api.libs.json.{JsObject, JsValue, Json}

import java.sql.Connection
import javax.inject.{Inject, Singleton}
import scala.util.Try

/**
 * Marketing reads (PHP `group_buy.php`, `auction.php`, `snatch.php`,
 * `activity.php`, `package.php`, `topic.php`, `vote.php`, `exchange.php`).
 *
 * Time windows are always compared against the server clock; a client cannot
 * extend or shorten an activity by sending its own timestamp.
 */
@Singleton
class MarketingRepo @Inject() (db: Db):

  /** Legacy act_type values of the goods_activity table. */
  val ActivityTypes: Set[Int] = Set(0, 1, 2, 3, 4)

  protected def extInfo(text: String): JsValue =
    if text.trim.isEmpty then Json.obj() else Try(Json.parse(text)).getOrElse(Json.obj())

  /** Shared shape of one goods_activity row joined with its goods. */
  protected def activityProject(row: Row): JsObject =
    Json.obj(
      "id"          -> row.long("act_id"),
      "name"        -> row.str("act_name"),
      "description" -> row.str("act_desc"),
      "type"        -> row.long("act_type"),
      "goods_id"    -> row.long("goods_id"),
      "goods_name"  -> row.str("goods_name"),
      "product_id"  -> row.long("product_id"),
      "price"       -> ApiJson.money(row.dec("shop_price")),
      "start_time"  -> row.long("start_time"),
      "end_time"    -> row.long("end_time"),
      "ext_info"    -> extInfo(row.str("ext_info"))
    )

  private val ActivitySelect: String =
    """SELECT a.act_id, a.act_name, a.act_desc, a.act_type, a.goods_id, a.product_id,
      |  g.goods_name, g.shop_price, a.start_time, a.end_time, a.ext_info
      |FROM goods_activity a
      |JOIN goods g ON g.goods_id = a.goods_id""".stripMargin

  private val ActivityLive: String =
    "a.is_finished = 0 AND a.start_time <= ? AND a.end_time >= ? AND g.is_on_sale = 1 AND g.is_delete = 0"

  /**
   * `GET /api/v1/activities` (PHP `activity.php`): the favourable-activity
   * discount rules, ordered by the shop's own sort order and then by the end of
   * their window. Money stays a decimal string and `gift` keeps its raw JSON.
   */
  def activities(conn: Connection, page: Paging.Page): JsObject =
    val total = db.count(conn, "SELECT COUNT(*) FROM favourable_activity", Nil)
    val rows = db.rows(conn,
      """SELECT act_id, act_name, start_time, end_time, user_rank, act_range, act_range_ext,
        |  min_amount, max_amount, act_type, act_type_ext, gift, sort_order
        |FROM favourable_activity
        |ORDER BY sort_order ASC, end_time ASC, act_id ASC
        |LIMIT ? OFFSET ?""".stripMargin, Seq(page.pageSize, page.offset))
    ApiJson.page(page.page, page.pageSize, total, rows.map(r => Json.obj(
      "id"           -> r.long("act_id"),
      "name"         -> r.str("act_name"),
      "start_time"   -> r.long("start_time"),
      "end_time"     -> r.long("end_time"),
      "user_rank"    -> r.str("user_rank"),
      "range"        -> r.long("act_range"),
      "range_ext"    -> r.str("act_range_ext"),
      "min_amount"   -> ApiJson.money(r.dec("min_amount")),
      "max_amount"   -> ApiJson.money(r.dec("max_amount")),
      "act_type"     -> r.long("act_type"),
      "act_type_ext" -> ApiJson.money(r.dec("act_type_ext")),
      "gift"         -> extInfo(r.str("gift")),
      "sort_order"   -> r.long("sort_order")
    )))

  /** `GET /api/v1/promotions/{id}` — one running activity of visible goods. */
  def promotion(conn: Connection, actId: Long): Option[JsObject] =
    val now = ApiJson.nowUnix
    db.row(conn,
      s"""$ActivitySelect
         |WHERE a.act_id = ? AND $ActivityLive""".stripMargin, Seq(actId, now, now))
      .map(activityProject)

  /**
   * `GET /api/v1/promotions` — running snatch / group-buy / auction / exchange
   * / package activities, optionally narrowed by `type`.
   */
  def promotions(conn: Connection, actType: Option[Int], page: Paging.Page): JsObject =
    val now = ApiJson.nowUnix
    val (extra, params) = actType match
      case Some(t) => (" AND a.act_type = ?", Seq[Any](now, now, t))
      case None    => ("", Seq[Any](now, now))

    val total = db.count(conn,
      s"""SELECT COUNT(*) FROM goods_activity a JOIN goods g ON g.goods_id = a.goods_id
         |WHERE $ActivityLive$extra""".stripMargin, params)
    val rows = db.rows(conn,
      s"""$ActivitySelect
         |WHERE $ActivityLive$extra
         |ORDER BY a.end_time ASC, a.act_id ASC
         |LIMIT ? OFFSET ?""".stripMargin, params ++ Seq(page.pageSize, page.offset))
    ApiJson.page(page.page, page.pageSize, total, rows.map(activityProject))
