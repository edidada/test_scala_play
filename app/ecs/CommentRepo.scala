package ecs

import play.api.libs.json.{JsObject, Json}

import java.sql.Connection
import javax.inject.{Inject, Singleton}

/**
 * Goods comments (PHP `comment.php`, `user.php?act=comment_list|del_cmt`).
 *
 * `comment_type = 0` is the legacy goods comment, `id_value` carries the goods id.
 * Only moderated rows (`status = 1`) are public and the author name always comes
 * from the session, never from the request body.
 */
@Singleton
class CommentRepo @Inject() (db: Db):

  val GoodsType = 0

  private val Columns = "comment_id, comment_type, id_value, user_id, user_name, content, status, add_time, parent_id"

  private def project(row: Row): JsObject =
    Json.obj(
      "id"         -> row.long("comment_id"),
      "type"       -> row.long("comment_type"),
      "goods_id"   -> row.long("id_value"),
      "user_id"    -> row.long("user_id"),
      "author"     -> row.str("user_name"),
      "content"    -> row.str("content"),
      "parent_id"  -> row.long("parent_id"),
      "created_at" -> Views.timestamp(row, "add_time")
    )

  /** `GET /api/v1/goods/{id}/comments` — published top-level comments. */
  def forGoods(conn: Connection, goodsId: Long, page: Paging.Page): JsObject =
    val where = "comment_type = ? AND id_value = ? AND status = 1 AND parent_id = 0"
    val total = db.count(conn, s"SELECT COUNT(*) FROM comment WHERE $where", Seq(GoodsType, goodsId))
    val rows = db.rows(conn,
      s"""SELECT $Columns FROM comment WHERE $where
         |ORDER BY comment_id DESC
         |LIMIT ? OFFSET ?""".stripMargin, Seq(GoodsType, goodsId, page.pageSize, page.offset))
    ApiJson.page(page.page, page.pageSize, total, rows.map(project))

  /**
   * One published comment on a saleable goods item.
   *
   * The goods row is re-read inside the caller's transaction, so a product taken
   * off the shelf between validation and insert is still refused (404).
   */
  def create(conn: Connection, goodsId: Long, userId: Long, userName: String, content: String): Option[JsObject] =
    val visible = db.row(conn,
      s"SELECT g.goods_id FROM goods g WHERE g.goods_id = ? AND ${GoodsRepo.Visible}", Seq(goodsId))
    visible.map { _ =>
      val id = db.insertReturningKey(conn,
        """INSERT INTO comment (comment_type, id_value, user_id, user_name, content, status, add_time, parent_id)
          |VALUES (?, ?, ?, ?, ?, 1, ?, 0)""".stripMargin,
        Seq(GoodsType, goodsId, userId, userName, content, ApiJson.nowUtc))
      project(db.row(conn, s"SELECT $Columns FROM comment WHERE comment_id = ?", Seq(id)).get)
    }
