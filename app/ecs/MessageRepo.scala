package ecs

import play.api.libs.json.{JsObject, Json}

import java.sql.Connection
import javax.inject.{Inject, Singleton}

/**
 * Guestbook (PHP `message.php` and `user.php?act=message_list|del_msg`).
 *
 * Only rows in the front area that already passed moderation are public; the
 * owner-scoped reads add the first reply so a user can see the answer to their
 * own message without exposing anybody else's traffic.
 */
@Singleton
class MessageRepo @Inject() (db: Db):

  /** Front-area, moderated rows only (docs/03_api_contract.md, 留言板). */
  val Public: String = "msg_area = 1 AND msg_status = 1"

  private val Columns =
    "msg_id, parent_id, user_id, user_name, user_email, msg_title, msg_type, msg_content, msg_time, order_id"

  private def project(row: Row): JsObject =
    Json.obj(
      "id"        -> row.long("msg_id"),
      "parent_id" -> row.long("parent_id"),
      "user_id"   -> row.long("user_id"),
      "username"  -> row.str("user_name"),
      "email"     -> row.str("user_email"),
      "title"     -> row.str("msg_title"),
      "type"      -> row.long("msg_type"),
      "content"   -> row.str("msg_content"),
      "order_id"  -> row.long("order_id"),
      "created_at" -> Views.timestamp(row, "msg_time")
    )

  /** Oldest answer of a message, or None when nobody has replied yet. */
  private def firstReply(conn: Connection, msgId: Long): Option[JsObject] =
    db.row(conn,
      s"""SELECT $Columns FROM feedback
         |WHERE parent_id = ? ORDER BY msg_id ASC LIMIT 1""".stripMargin, Seq(msgId))
      .map(project)

  /** `GET /api/v1/messages` — public top-level entries of the guestbook. */
  def list(conn: Connection, page: Paging.Page): JsObject =
    val total = db.count(conn, s"SELECT COUNT(*) FROM feedback WHERE $Public AND parent_id = 0", Nil)
    val rows = db.rows(conn,
      s"""SELECT $Columns FROM feedback WHERE $Public AND parent_id = 0
         |ORDER BY msg_id DESC
         |LIMIT ? OFFSET ?""".stripMargin, Seq(page.pageSize, page.offset))
    ApiJson.page(page.page, page.pageSize, total, rows.map(project))

  /**
   * One insert of a new guestbook row.
   *
   * The caller decides `userId` and `userName` from the bearer session, so a body
   * can never claim another person's identity.
   */
  def create(conn: Connection, userId: Long, userName: String, email: String, title: String,
    content: String, msgType: Long, orderId: Long): JsObject =
    val id = db.insertReturningKey(conn,
      """INSERT INTO feedback (parent_id, user_id, user_name, user_email, msg_title, msg_type,
        |  msg_status, msg_content, msg_time, order_id, msg_area)
        |VALUES (0, ?, ?, ?, ?, ?, 1, ?, ?, ?, 1)""".stripMargin,
      Seq(userId, userName, email, title, msgType, content, ApiJson.nowUtc, orderId))
    project(db.row(conn, s"SELECT $Columns FROM feedback WHERE msg_id = ?", Seq(id)).get)

  /** `GET /api/v1/me/messages` — the caller's own top-level messages plus the first reply. */
  def mine(conn: Connection, userId: Long, page: Paging.Page): JsObject =
    val total = db.count(conn,
      "SELECT COUNT(*) FROM feedback WHERE user_id = ? AND parent_id = 0", Seq(userId))
    val rows = db.rows(conn,
      s"""SELECT $Columns FROM feedback WHERE user_id = ? AND parent_id = 0
         |ORDER BY msg_id DESC
         |LIMIT ? OFFSET ?""".stripMargin, Seq(userId, page.pageSize, page.offset))
    val items = rows.map { row =>
      project(row) ++ Json.obj("reply" -> firstReply(conn, row.long("msg_id")).getOrElse(Json.obj()))
    }
    ApiJson.page(page.page, page.pageSize, total, items)

  /**
   * `DELETE /api/v1/me/messages/{id}` — owner-only, replies go first.
   *
   * Both statements run in the caller's transaction and the user id is part of the
   * WHERE clause of the primary delete, so a row of somebody else is never touched.
   */
  def delete(conn: Connection, msgId: Long, userId: Long): Boolean =
    db.row(conn, "SELECT user_id FROM feedback WHERE msg_id = ?", Seq(msgId)) match
      case None                          => false
      case Some(row) if row.long("user_id") != userId => false
      case Some(_) =>
        db.execute(conn, "DELETE FROM feedback WHERE parent_id = ?", Seq(msgId))
        db.execute(conn, "DELETE FROM feedback WHERE msg_id = ? AND user_id = ?", Seq(msgId, userId)) == 1
