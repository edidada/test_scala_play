package controllers

import ecs.{ApiError, Body, Db, EmailAddress, ErrorCode, FieldError, NewsletterRepo}
import javax.inject.Inject
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{AbstractController, Action, ControllerComponents, Request, Result}

/**
 * Newsletter lifecycle — maps the four jobs of PHP user.php?act=email_list.
 *
 * The two request endpoints answer with the same 202 whether or not the address is
 * known, so they cannot be used to enumerate the mailing list.
 */
class NewsletterController @Inject() (cc: ControllerComponents, db: Db, newsletter: NewsletterRepo)
  extends AbstractController(cc) with ApiSupport:

  /** `POST /api/v1/newsletter-subscriptions` */
  def subscribe: Action[JsValue] = Action(parse.json) { req =>
    emailRequest(req) { email =>
      db.inTransaction(conn => newsletter.requestSubscribe(conn, email))
      Accepted(Json.obj("status" -> "accepted"))
    }
  }

  /** `DELETE /api/v1/newsletter-subscriptions` */
  def unsubscribe: Action[JsValue] = Action(parse.json) { req =>
    emailRequest(req) { email =>
      db.inTransaction(conn => newsletter.requestUnsubscribe(conn, email))
      Accepted(Json.obj("status" -> "accepted"))
    }
  }

  /** `POST /api/v1/newsletter-subscriptions/confirm` */
  def confirmSubscribe: Action[JsValue] = Action(parse.json) { req =>
    tokenRequest(req) { token =>
      if db.inTransaction(conn => newsletter.confirmSubscribe(conn, token)) then NoContent
      else ApiError.conflict(ErrorCode.Conflict, "the newsletter token is not usable", req)
    }
  }

  /** `POST /api/v1/newsletter-unsubscriptions/confirm` */
  def confirmUnsubscribe: Action[JsValue] = Action(parse.json) { req =>
    tokenRequest(req) { token =>
      if db.inTransaction(conn => newsletter.confirmUnsubscribe(conn, token)) then NoContent
      else ApiError.conflict(ErrorCode.Conflict, "the newsletter token is not usable", req)
    }
  }

  private def emailRequest(req: Request[JsValue])(next: String => Result): Result =
    val body = Body(req.body)
    val parsed =
      for
        raw <- body.str("email", maxBytes = 120)
        _ <- body.rejectUnknown(Set("email")).toLeft(())
        email <- NewsletterController.email(raw)
      yield email

    orFail(parsed, req)(next)

  private def tokenRequest(req: Request[JsValue])(next: String => Result): Result =
    val body = Body(req.body)
    val parsed =
      for
        token <- EmailFlow.token(body.str("token", maxBytes = 64))
        _ <- body.rejectUnknown(Set("token")).toLeft(())
      yield token

    orFail(parsed, req)(next)

object NewsletterController:
  /** Addresses are stored lower-cased so the unique key actually catches repeats. */
  def email(raw: String): Either[FieldError, String] =
    val value = raw.trim.toLowerCase
    if !EmailAddress.valid(value) then Left(FieldError("email", "email must be a valid address"))
    else if value.getBytes("UTF-8").length > 120 then Left(FieldError("email", "email must be at most 120 bytes"))
    else Right(value)
