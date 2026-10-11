package controllers

import ecs.{ApiError, AuthedAction, Body, BookingFailure, BookingRepo, Db, ErrorCode, Paging}
import javax.inject.Inject
import play.api.libs.json.{JsObject, JsValue}
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents, RequestHeader, Result}

/**
 * Out-of-stock bookings — maps PHP user.php?act=booking_list|act_add_booking|act_del_booking.
 */
class BookingController @Inject() (cc: ControllerComponents, db: Db, bookings: BookingRepo, authed: AuthedAction)
  extends AbstractController(cc) with ApiSupport:

  /** `GET /api/v1/me/bookings` */
  def index(page: Option[Int], page_size: Option[Int]): Action[AnyContent] = authed { req =>
    orFail(Paging.of(page, page_size), req) { p =>
      Ok(db.readOnly(conn => bookings.list(conn, req.user.id, p)))
    }
  }

  /** `POST /api/v1/me/bookings` — one row per user and goods, 409 on a repeat. */
  def create: Action[JsValue] = authed(parse.json) { req =>
    val body = Body(req.body)
    val parsed =
      for
        goodsId   <- body.long("goods_id", min = 1)
        quantity  <- body.long("goods_number", min = 1, max = 999999)
        note      <- MessageController.text(body.optStr("description", 255), "")
        linkman   <- MessageController.text(body.optStr("linkman", 60), req.user.userName)
        email     <- MessageController.text(body.optStr("email", 60), req.user.email)
        telephone <- MessageController.text(body.optStr("telephone", 60), "")
        _         <- body.rejectUnknown(Set("goods_id", "goods_number", "description", "linkman", "email",
          "telephone")).toLeft(())
      yield (goodsId, quantity, note, linkman, email, telephone)

    orFail(parsed, req) { case (goodsId, quantity, note, linkman, email, telephone) =>
      bookingsOutcome(db.inTransaction(conn =>
        bookings.create(conn, req.user.id, goodsId, quantity, note, linkman, email, telephone)), req)
    }
  }

  /** `DELETE /api/v1/me/bookings/{id}` */
  def destroy(id: Long): Action[AnyContent] = authed { req =>
    if db.inTransaction(conn => bookings.delete(conn, req.user.id, id)) then NoContent
    else ApiError.notFound("booking not found", req)
  }

  private def bookingsOutcome(outcome: Either[BookingFailure, JsObject], req: RequestHeader): Result =
    outcome match
      case Right(payload) => Created(payload)
      case Left(BookingFailure.GoodsNotFound) => ApiError.notFound("goods not found", req)
      case Left(BookingFailure.Duplicate) =>
        ApiError.conflict(ErrorCode.Conflict, "you already have a booking for this goods", req)
