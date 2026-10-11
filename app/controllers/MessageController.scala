package controllers

import ecs.{ApiError, AuthedAction, Body, Db, EmailAddress, FieldError, MessageRepo, Paging}
import javax.inject.Inject
import play.api.libs.json.JsValue
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** Guestbook endpoints — maps PHP message.php / user.php?act=message_list|del_msg. */
class MessageController @Inject() (cc: ControllerComponents, db: Db, messages: MessageRepo, authed: AuthedAction)
  extends AbstractController(cc) with ApiSupport:

  /** `GET /api/v1/messages` — public, moderated, front-area entries. */
  def index(page: Option[Int], page_size: Option[Int]): Action[AnyContent] = Action { req =>
    orFail(Paging.of(page, page_size), req) { p =>
      Ok(db.readOnly(conn => messages.list(conn, p)))
    }
  }

  /**
   * `POST /api/v1/messages` — anonymous posting is allowed; a bearer token binds
   * the row to that user instead of to whatever name the body claims.
   */
  def create: Action[JsValue] = Action(parse.json) { req =>
    val body = Body(jsonBody(req))
    val parsed =
      for
        content <- body.str("content", maxBytes = 2000)
        username <- MessageController.text(body.optStr("username", 60), "")
        email <- MessageController.text(body.optStr("email", 60), "")
        title <- MessageController.text(body.optStr("title", 200), "")
        msgType <- body.optLong("type", 0, 3).map(_.getOrElse(0L))
        orderId <- body.optLong("order_id", 0).map(_.getOrElse(0L))
        anonymous <- body.bool("anonymous")
        _ <- if email.nonEmpty && !EmailAddress.valid(email) then Left(FieldError("email", "email must be a valid address")) else Right(())
      yield (content, username, email, title, msgType, orderId, anonymous)

    orFail(parsed, req) { case (content, username, email, title, msgType, orderId, anonymous) =>
      val user = authed.userOf(req.headers)
      val userId = user.map(_.id).getOrElse(0L)
      val shownName =
        if anonymous then "anonymous"
        else user.map(_.userName).orElse(Option(username).filter(_.nonEmpty)).getOrElse("guest")
      val payload = db.inTransaction { conn =>
        messages.create(conn, userId, shownName, email, title, content, msgType, orderId)
      }
      Created(payload)
    }
  }

  /** `GET /api/v1/me/messages` — own top-level messages with their first reply. */
  def mine(page: Option[Int], page_size: Option[Int]): Action[AnyContent] = authed { req =>
    orFail(Paging.of(page, page_size), req) { p =>
      Ok(db.readOnly(conn => messages.mine(conn, req.user.id, p)))
    }
  }

  /** `DELETE /api/v1/me/messages/{id}` — owner only, replies removed in the same transaction. */
  def destroy(id: Long): Action[AnyContent] = authed { req =>
    if db.inTransaction(conn => messages.delete(conn, id, req.user.id)) then NoContent
    else ApiError.notFound("message not found", req)
  }

object MessageController:
  /** Optional string field with a fallback when it is absent or blank. */
  def text(opt: Option[Either[FieldError, String]], default: String): Either[FieldError, String] =
    opt match
      case None            => Right(default)
      case Some(Left(fe) ) => Left(fe)
      case Some(Right(v))  => Right(if v.trim.isEmpty then default else v.trim)
