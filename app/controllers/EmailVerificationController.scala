package controllers

import ecs.{ApiError, AuthedAction, Body, Db, ErrorCode, FieldError, VerificationRepo}
import javax.inject.Inject
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** Mailbox verification — maps PHP user.php?act=send_hash_mail|validate_email. */
class EmailVerificationController @Inject() (cc: ControllerComponents, db: Db,
  tokens: VerificationRepo, authed: AuthedAction) extends AbstractController(cc) with ApiSupport:

  /** `POST /api/v1/me/email-verifications` — the plaintext token only goes to the outbox. */
  def request: Action[AnyContent] = authed { req =>
    db.inTransaction { conn =>
      tokens.queueEmailVerification(conn, req.user.id, req.user.email)
    }
    Accepted(Json.obj("status" -> "queued"))
  }

  /** `POST /api/v1/email-verifications/confirm` — one-shot token, 409 on any misuse. */
  def confirm: Action[JsValue] = Action(parse.json) { req =>
    val parsed =
      for
        token <- EmailFlow.token(Body(req.body).str("token", maxBytes = 64))
        _ <- Body(req.body).rejectUnknown(Set("token")).toLeft(())
      yield token

    orFail(parsed, req) { token =>
      if db.inTransaction(conn => tokens.confirmEmail(conn, token))
      then NoContent
      else ApiError.conflict(ErrorCode.Conflict, "the verification token is not usable", req)
    }
  }

object EmailFlow:
  /** Tokens are 64 lowercase hex characters; anything else is a 400. */
  def token(either: Either[FieldError, String]): Either[FieldError, String] =
    either.flatMap { raw =>
      val value = raw.trim
      if value.length != 64 || !value.matches("^[0-9a-f]{64}$") then
        Left(FieldError("token", "token must be 64 lowercase hexadecimal characters"))
      else Right(value)
    }
