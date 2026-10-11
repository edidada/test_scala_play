package controllers

import ecs.{AuthedAction, Db, MessageRepo, Paging}
import javax.inject.Inject
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

  def create: Action[AnyContent] = Action { req => notReady("POST /api/v1/messages")(req) }

  def mine(page: Option[Int], page_size: Option[Int]): Action[AnyContent] = Action { req => notReady("GET /api/v1/me/messages")(req) }

  def destroy(id: Long): Action[AnyContent] = Action { req => notReady("DELETE /api/v1/me/messages/{id}")(req) }
