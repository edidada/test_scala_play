package ecs

import play.api.libs.json.{JsObject, Json}

import java.sql.Connection
import javax.inject.{Inject, Singleton}
import scala.util.{Failure, Success, Try}

/**
 * Out-of-stock registrations (PHP `user.php?act=booking_list|act_add_booking|act_del_booking`).
 *
 * `booking_goods` carries `UNIQUE(user_id, goods_id)`, which is what makes the
 * "one booking per shopper and product" rule survive two concurrent requests; the
 * goods visibility check and the insert share the caller's transaction.
 */
@Singleton
class BookingRepo @Inject() (db: Db):

  private val Select =
    """SELECT bg.rec_id, bg.user_id, bg.goods_id, bg.goods_desc, bg.goods_number, bg.booking_time,
      |  bg.email, bg.link_man, bg.tel, bg.is_dispose, bg.dispose_note, g.goods_name
      |FROM booking_goods bg
      |JOIN goods g ON g.goods_id = bg.goods_id
      |WHERE bg.user_id = ?""".stripMargin

  private def project(row: Row): JsObject =
    val booked = row.long("booking_time")
    Json.obj(
      "id"          -> row.long("rec_id"),
      "goods_id"    -> row.long("goods_id"),
      "goods_name"  -> row.str("goods_name"),
      "quantity"    -> row.long("goods_number"),
      "description" -> row.str("goods_desc"),
      "linkman"     -> row.str("link_man"),
      "email"       -> row.str("email"),
      "telephone"   -> row.str("tel"),
      "handled"     -> row.bool("is_dispose"),
      "admin_note"  -> row.str("dispose_note"),
      "booked_at"   -> booked,
      "created_at"  -> ApiJson.utcOf(booked)
    )

  /** `GET /api/v1/me/bookings` — newest registration first. */
  def list(conn: Connection, userId: Long, page: Paging.Page): JsObject =
    val total = db.count(conn, "SELECT COUNT(*) FROM booking_goods WHERE user_id = ?", Seq(userId))
    val rows = db.rows(conn,
      s"""$Select
         |ORDER BY bg.booking_time DESC, bg.rec_id DESC
         |LIMIT ? OFFSET ?""".stripMargin, Seq(userId, page.pageSize, page.offset))
    ApiJson.page(page.page, page.pageSize, total, rows.map(project))

  /** `POST /api/v1/me/bookings` — only visible goods, and only one row per goods. */
  def create(conn: Connection, userId: Long, goodsId: Long, quantity: Long, description: String,
    linkman: String, email: String, telephone: String): Either[BookingFailure, JsObject] =

    val visible = db.row(conn,
      s"SELECT g.goods_id FROM goods g WHERE g.goods_id = ? AND ${GoodsRepo.Visible}", Seq(goodsId))
    if visible.isEmpty then Left(BookingFailure.GoodsNotFound)
    else
      val stamp = ApiJson.nowUnix
      Try(db.insertReturningKey(conn,
        """INSERT INTO booking_goods (user_id, email, link_man, tel, goods_id, goods_desc, goods_number, booking_time)
          |VALUES (?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin,
        Seq(userId, email, linkman, telephone, goodsId, description, quantity, stamp))) match
        case Failure(_)      => Left(BookingFailure.Duplicate)
        case Success(recId)  =>
          db.row(conn, s"$Select AND bg.rec_id = ?".stripMargin, Seq(userId, recId)) match
            case Some(row) => Right(project(row))
            case None      => Left(BookingFailure.GoodsNotFound)

  /** `DELETE /api/v1/me/bookings/{id}` */
  def delete(conn: Connection, userId: Long, recId: Long): Boolean =
    db.execute(conn, "DELETE FROM booking_goods WHERE rec_id = ? AND user_id = ?", Seq(recId, userId)) == 1

/** Why a booking was refused; each case maps to one HTTP status. */
enum BookingFailure:
  case GoodsNotFound, Duplicate
