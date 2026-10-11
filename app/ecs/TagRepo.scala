package ecs

import play.api.libs.json.{JsObject, Json}

import java.sql.Connection
import javax.inject.{Inject, Singleton}

/**
 * Goods tags (PHP `tag_cloud.php` and `user.php?act=add_tag|tag_list|act_del_tag`).
 *
 * A tag row belongs to exactly one user and one goods item, so every read has to
 * re-join `goods` to keep tags of withdrawn or deleted products out of the cloud.
 */
@Singleton
class TagRepo @Inject() (db: Db):

  /** Longest tag cloud the public endpoint returns in one page. */
  val CloudLimit = 200

  /** `GET /api/v1/tags` — words ranked by usage, ties broken alphabetically. */
  def cloud(conn: Connection, limit: Int = CloudLimit): JsObject =
    val rows = db.rows(conn,
      """SELECT t.tag_words AS word, COUNT(*) AS uses
        |FROM tag t
        |JOIN goods g ON g.goods_id = t.goods_id
        |WHERE g.is_on_sale = 1 AND g.is_delete = 0 AND t.tag_words <> ''
        |GROUP BY t.tag_words
        |ORDER BY uses DESC, t.tag_words ASC
        |LIMIT ?""".stripMargin, Seq(limit))
    val items = rows.map(r => Json.obj("tag" -> r.str("word"), "count" -> r.long("uses")))
    ApiJson.page(1, items.size, items.size.toLong, items)
