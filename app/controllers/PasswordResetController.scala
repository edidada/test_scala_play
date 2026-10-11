package controllers

import ecs.{ApiError, Body, Db, ErrorCode, VerificationRepo}
import javax.inject.Inject
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{AbstractController, Action, ControllerComponents}

/** Password recovery — maps PHP user.php?act=get_password|send_pwd_email|reset_password. */
class PasswordResetController @Inject() (cc: ControllerComponents, db: Db, tokens: VerificationRepo)
  extends AbstractController(cc) with ApiSupport:

  /**
   * `POST /api/v1/password-resets` — the same 202 whether or not the address is
   * registered, so the endpoint cannot be used to enumerate accounts.
   */
  def request: Action[JsValue] = Action(parse.json) { req =>
    val parsed =
      for
        email <- AuthController.emailField(Body(req.body).str("email", maxBytes = 120))
        _ <- Body(req.body).rejectUnknown(Set("email")).toLeft(())
      yield email

    orFail(parsed, req) { email =>
      db.inTransaction(conn => tokens.queuePasswordReset(conn, email))
      Accepted(Json.obj("status" -> "accepted"))
    }
  }

  /** `POST /api/v1/password-resets/confirm` — rotates the password and drops all sessions. */
  def confirm: Action[JsValue] = Action(parse.json) { req =>
    val body = Body(req.body)
    val parsed =
      for
        token <- EmailFlow.token(body.str("token", maxBytes = 64))
        password <- AuthController.password(body.str("new_password", maxBytes = 1024))
        _ <- body.rejectUnknown(Set("token", "new_password")).toLeft(())
      yield (token, password)

    orFail(parsed, req) { case (token, password) =>
      if db.inTransaction(conn => tokens.confirmPasswordReset(conn, token, password))
      then NoContent
      else ApiError.conflict(ErrorCode.Conflict, "the reset token is not usable", req)
    }
  }
