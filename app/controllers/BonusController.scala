package controllers

import ecs.{ApiError, AuthedAction, Body, BonusFailure, BonusRepo, Db, ErrorCode, FieldError, Paging}
import javax.inject.Inject
import play.api.libs.json.JsValue
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** Cash coupons — maps PHP user.php?act=bonus|act_add_bonus. */
class BonusController @Inject() (cc: ControllerComponents, db: Db, bonuses: BonusRepo, authed: AuthedAction)
  extends AbstractController(cc) with ApiSupport:

  /** `GET /api/v1/me/bonuses` — only coupons claimed by the bearer user. */
  def index(page: Option[Int], page_size: Option[Int]): Action[AnyContent] = authed { req =>
    orFail(Paging.of(page, page_size), req) { p =>
      Ok(db.readOnly(conn => bonuses.mine(conn, req.user.id, p)))
    }
  }

  /** `POST /api/v1/me/bonuses/claim` — the serial is claimed with a `user_id = 0` guard. */
  def claim: Action[JsValue] = authed(parse.json) { req =>
    val body = Body(req.body)
    val parsed =
      for
        serial <- BonusController.serial(body.str("bonus_sn", maxBytes = 20))
        _ <- body.rejectUnknown(Set("bonus_sn")).toLeft(())
      yield serial

    orFail(parsed, req) { serial =>
      db.inTransaction(conn => bonuses.claim(conn, req.user.id, serial)) match
        case Right(_) => NoContent
        case Left(BonusFailure.NotFound) => ApiError.notFound("bonus not found", req)
        case Left(BonusFailure.AlreadyClaimed) =>
          ApiError.conflict(ErrorCode.Conflict, "this bonus serial has already been claimed", req)
        case Left(BonusFailure.Expired) =>
          ApiError.conflict(ErrorCode.Conflict, "this bonus serial is expired", req)
    }
  }

object BonusController:
  /** `bonus_sn` must arrive as a 1--20 digit string so a JSON int cannot lose precision. */
  def serial(either: Either[FieldError, String]): Either[FieldError, Long] =
    either.flatMap { raw =>
      val value = raw.trim
      if !value.matches("^[0-9]{1,20}$") then
        Left(FieldError("bonus_sn", "bonus_sn must be a string of 1 to 20 digits"))
      else value.toLongOption match
        case Some(parsed) => Right(parsed)
        case None         => Left(FieldError("bonus_sn", "bonus_sn is out of range"))
    }
