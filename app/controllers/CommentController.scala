package controllers

import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** Comment endpoints — maps PHP comment.php / user.php?act=comment_list|del_cmt. */
class CommentController @Inject() (cc: ControllerComponents) extends AbstractController(cc) with ApiSupport:
  def forGoods(id: Long, page: Option[Int], page_size: Option[Int]) : Action[AnyContent] =
    Action { req => notReady("GET /api/v1/goods/{id}/comments")(req) }

  def createForGoods(id: Long) : Action[AnyContent] = Action { req => notReady("POST /api/v1/goods/{id}/comments")(req) }

  def mine(page: Option[Int], page_size: Option[Int]) : Action[AnyContent] = Action { req => notReady("GET /api/v1/me/comments")(req) }

  def destroy(id: Long) : Action[AnyContent] = Action { req => notReady("DELETE /api/v1/me/comments/{id}")(req) }
