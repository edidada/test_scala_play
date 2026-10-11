package ecs

import play.api.libs.json.{JsObject, Json}

import java.sql.Connection
import javax.inject.{Inject, Singleton}
import scala.util.{Failure, Success, Try}

/**
 * Product favourites (PHP `user.php?act=collection_list|collect|add_to_attention|del_attention|delete_collection`).
 *
 * `collect_goods` has a `UNIQUE(user_id, goods_id)` key, so collecting twice is
 * caught by the database rather than by a read-then-write check. Updates and
 * deletes always match the collection id *and* the authenticated user id.
 */
@Singleton
class FavoriteRepo @Inject() (db: Db):

  private val Select =
    s"""SELECT cg.rec_id, cg.user_id, cg.goods_id, cg.is_attention, cg.add_time, ${Views.goodsColumns("g")}
       |FROM collect_goods cg
       |JOIN goods g ON g.goods_id = cg.goods_id
       |WHERE cg.user_id = ? AND g.is_delete = 0""".stripMargin

  private def project(row: Row): JsObject =
    Json.obj(
      "id"         -> row.long("rec_id"),
      "goods_id"   -> row.long("goods_id"),
      "attention"  -> row.bool("is_attention"),
      "created_at" -> Views.timestamp(row, "add_time"),
      "goods"      -> Views.goodsSummary(row)
    )

  /** `GET /api/v1/me/favorites` — newest collection first. */
  def list(conn: Connection, userId: Long, page: Paging.Page): JsObject =
    val total = db.count(conn,
      """SELECT COUNT(*) FROM collect_goods cg JOIN goods g ON g.goods_id = cg.goods_id
        |WHERE cg.user_id = ? AND g.is_delete = 0""".stripMargin, Seq(userId))
    val rows = db.rows(conn,
      s"""$Select
         |ORDER BY cg.rec_id DESC
         |LIMIT ? OFFSET ?""".stripMargin, Seq(userId, page.pageSize, page.offset))
    ApiJson.page(page.page, page.pageSize, total, rows.map(project))

  private def byId(conn: Connection, userId: Long, recId: Long, lock: Boolean): Option[Row] =
    db.row(conn,
      s"""$Select AND cg.rec_id = ?${Sql.forUpdate(db.dialect)}""".stripMargin, Seq(userId, recId))

  /**
   * `POST /api/v1/me/favorites` — only goods that are still on sale can be
   * collected; the unique key answers the concurrent double collect.
   */
  def add(conn: Connection, userId: Long, goodsId: Long): Either[FavoriteFailure, JsObject] =
    val visible = db.row(conn,
      s"SELECT g.goods_id FROM goods g WHERE g.goods_id = ? AND ${GoodsRepo.Visible}", Seq(goodsId))
    if visible.isEmpty then Left(FavoriteFailure.GoodsNotFound)
    else
      Try(db.insertReturningKey(conn,
        """INSERT INTO collect_goods (user_id, goods_id, is_attention, add_time)
          |VALUES (?, ?, 0, ?)""".stripMargin, Seq(userId, goodsId, ApiJson.nowUtc))) match
        case Failure(_) => Left(FavoriteFailure.Duplicate)
        case Success(id) =>
          byId(conn, userId, id, lock = false) match
            case Some(row) => Right(project(row))
            case None      => Left(FavoriteFailure.GoodsNotFound)

  /** `PATCH /api/v1/me/favorites/{id}` — the attention flag of one owned row. */
  def setAttention(conn: Connection, userId: Long, recId: Long,
    attention: Boolean): Either[FavoriteFailure, JsObject] =
    byId(conn, userId, recId, lock = true) match
      case None => Left(FavoriteFailure.NotFound)
      case Some(_) =>
        db.execute(conn, "UPDATE collect_goods SET is_attention = ? WHERE rec_id = ? AND user_id = ?",
          Seq(if attention then 1 else 0, recId, userId))
        byId(conn, userId, recId, lock = false) match
          case Some(row) => Right(project(row))
          case None      => Left(FavoriteFailure.NotFound)

  /** `DELETE /api/v1/me/favorites/{id}` */
  def delete(conn: Connection, userId: Long, recId: Long): Boolean =
    db.execute(conn, "DELETE FROM collect_goods WHERE rec_id = ? AND user_id = ?", Seq(recId, userId)) == 1

/** Why a favourite write was refused; each case maps to one HTTP status. */
enum FavoriteFailure:
  case GoodsNotFound, NotFound, Duplicate
