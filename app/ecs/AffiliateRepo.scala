package ecs

import play.api.libs.json.{JsObject, Json}

import java.sql.Connection
import javax.inject.{Inject, Singleton}

/**
 * Referral centre (PHP `affiliate.php`, `user.php?act=affiliate`).
 *
 * The referral edge lives in `user_referral`, so the legacy `users.parent_user`
 * column is not resurrected. Levels come from a recursive CTE capped at ten hops,
 * and the order list merges the referred buyers' orders with the commissions the
 * platform already separated for this account.
 */
@Singleton
class AffiliateRepo @Inject() (db: Db):

  val MaxDepth = 10

  /** Legacy `order_query` masking: keep the head, hide the tail. */
  private def mask(orderSn: String): String =
    if orderSn.length <= 5 then "*****" else orderSn.take(orderSn.length - 5) + "***" + orderSn.takeRight(2)

  private val LevelsSql =
    """WITH RECURSIVE referral_tree(user_id, depth) AS (
      |  SELECT ur.user_id, 1 FROM user_referral ur WHERE ur.referrer_user_id = ?
      |  UNION ALL
      |  SELECT ur.user_id, rt.depth + 1
      |  FROM user_referral ur JOIN referral_tree rt ON rt.user_id = ur.referrer_user_id
      |  WHERE rt.depth < 10
      |)
      |SELECT depth, COUNT(*) AS users FROM referral_tree GROUP BY depth ORDER BY depth""".stripMargin

  private val RecordsSql =
    """SELECT record_kind, ref_id, order_id, order_sn, buyer_id, amount, point, separate_type, created_at
      |FROM (
      |  SELECT 'order' AS record_kind, o.order_id AS ref_id, o.order_id AS order_id, o.order_sn AS order_sn,
      |    o.user_id AS buyer_id, o.order_amount AS amount, 0 AS point, 0 AS separate_type, o.add_time AS created_at
      |  FROM order_info o
      |  WHERE o.user_id IN (SELECT ur.user_id FROM user_referral ur WHERE ur.referrer_user_id = ?)
      |  UNION ALL
      |  SELECT 'commission' AS record_kind, al.log_id AS ref_id, al.order_id AS order_id, o.order_sn AS order_sn,
      |    o.user_id AS buyer_id, al.money AS amount, al.point AS point, al.separate_type AS separate_type,
      |    al.created_at AS created_at
      |  FROM affiliate_log al JOIN order_info o ON o.order_id = al.order_id WHERE al.user_id = ?
      |) t
      |ORDER BY t.created_at DESC, t.ref_id DESC
      |LIMIT ? OFFSET ?""".stripMargin

  /** `GET /api/v1/me/affiliate` — levels plus the merged order and commission feed. */
  def mine(conn: Connection, userId: Long, page: Paging.Page): JsObject =
    val levels = db.rows(conn, LevelsSql, Seq(userId))
      .map(r => Json.obj("depth" -> r.long("depth"), "users" -> r.long("users")))
    val total = db.count(conn,
      """SELECT COUNT(*) FROM (
        |  SELECT o.order_id FROM order_info o
        |  WHERE o.user_id IN (SELECT ur.user_id FROM user_referral ur WHERE ur.referrer_user_id = ?)
        |  UNION ALL
        |  SELECT al.log_id FROM affiliate_log al WHERE al.user_id = ?
        |) t""".stripMargin, Seq(userId, userId))
    val rows = db.rows(conn, RecordsSql, Seq(userId, userId, page.pageSize, page.offset))
    val separated = db.count(conn, "SELECT COUNT(*) FROM affiliate_log WHERE user_id = ?", Seq(userId)) > 0
    val items = rows.map { r =>
      Json.obj(
        "kind"          -> r.str("record_kind"),
        "id"            -> r.long("ref_id"),
        "order_id"      -> r.long("order_id"),
        "order_sn"      -> mask(r.str("order_sn")),
        "buyer_id"      -> r.long("buyer_id"),
        "amount"        -> ApiJson.money(r.dec("amount")),
        "point"         -> r.long("point"),
        "separate_type" -> r.long("separate_type"),
        "created_at"    -> Views.timestamp(r, "created_at")
      )
    }
    Json.obj(
      "levels"  -> Json.toJson(levels),
      "orders"  -> ApiJson.page(page.page, page.pageSize, total, items),
      "separated" -> separated
    )
