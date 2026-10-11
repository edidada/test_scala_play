package controllers

import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** Guestbook endpoints — maps PHP message.php / user.php?act=message_list|del_msg. */
class MessageController @Inject() (cc: ControllerComponents) extends AbstractController(cc) with ApiSupport:
  def index(page: Option[Int], page_size: Option[Int]) : Action[AnyContent] = Action { req => notReady("GET /api/v1/messages")(req) }

  def create : Action[AnyContent] = Action { req => notReady("POST /api/v1/messages")(req) }

  def mine(page: Option[Int], page_size: Option[Int]) : Action[AnyContent] = Action { req => notReady("GET /api/v1/me/messages")(req) }

  def destroy(id: Long) : Action[AnyContent] = Action { req => notReady("DELETE /api/v1/me/messages/{id}")(req) }
