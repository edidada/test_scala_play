package controllers

import ecs.{ApiError, AuthedAction, Body, Db, ErrorCode, FieldError, PasswordFailure, UserRepo}
import javax.inject.Inject
import play.api.libs.json.JsValue
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** Account profile — maps PHP user.php?act=profile|act_edit_profile|act_edit_password. */
class MeController @Inject() (cc: ControllerComponents, db: Db, users: UserRepo, authed: AuthedAction)
  extends AbstractController(cc) with ApiSupport:

  /** `GET /api/v1/me` — the bearer session is the only source of the user id. */
  def show: Action[AnyContent] = authed { req =>
    db.readOnly { conn =>
      users.profile(conn, req.user.id) match
        case Some(payload) => Ok(payload)
        case None          => ApiError.notFound("user not found", req)
    }
  }

  /** `PATCH /api/v1/me` — e-mail and mobile phone are the editable profile fields. */
  def update: Action[JsValue] = authed(parse.json) { req =>
    val body = Body(req.body)
    val parsed =
      for
        email <- MeController.optionalEmail(body.optStr("email", 120))
        mobile <- MeController.optionalText(body.optStr("mobile_phone", 32))
        _ <- body.rejectUnknown(Set("email", "mobile_phone")).toLeft(())
      yield (email, mobile)

    orFail(parsed, req) { case (email, mobile) =>
      orFail(db.inTransaction(conn => users.updateProfile(conn, req.user.id, email, mobile)), req) { payload =>
        Ok(payload)
      }
    }
  }

  /**
   * `PATCH /api/v1/me/password` — every session of the user is revoked on success,
   * so the client has to log in again with the new password.
   */
  def updatePassword: Action[JsValue] = authed(parse.json) { req =>
    val body = Body(req.body)
    val parsed =
      for
        current <- body.str("current_password", maxBytes = 1024)
        next <- AuthController.password(body.str("new_password", maxBytes = 1024))
        _ <- body.rejectUnknown(Set("current_password", "new_password")).toLeft(())
      yield (current, next)

    orFail(parsed, req) { case (current, next) =>
      db.inTransaction(conn => users.changePassword(conn, req.user.id, current, next)) match
        case Right(_) => NoContent
        case Left(PasswordFailure.NotFound) => ApiError.notFound("user not found", req)
        case Left(PasswordFailure.WrongCurrent) =>
          ApiError.conflict(ErrorCode.Conflict, "the current password is not correct", req)
        case Left(PasswordFailure.ConcurrentChange) =>
          ApiError.conflict(ErrorCode.Conflict, "the password changed concurrently, sign in again", req)
    }
  }

object MeController:
  /** Absent or blank optional text becomes None. */
  def optionalText(opt: Option[Either[FieldError, String]]): Either[FieldError, Option[String]] =
    opt match
      case None           => Right(None)
      case Some(Left(fe)) => Left(fe)
      case Some(Right(v)) => Right(Option(v.trim).filter(_.nonEmpty))

  def optionalEmail(opt: Option[Either[FieldError, String]]): Either[FieldError, Option[String]] =
    optionalText(opt).flatMap {
      case None    => Right(None)
      case Some(e) => AuthController.emailField(Right(e)).map(Some(_))
    }
