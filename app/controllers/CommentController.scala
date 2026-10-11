package controllers

import ecs.{AuthedAction, Db, CommentRepo, Paging}
import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** Comment endpoints — maps PHP comment.php / user.php?act=comment_list|del_cmt. */
class CommentController @Inject() (cc: ControllerComponents, db: Db, comments: CommentRepo, authed: AuthedAction)
  extends AbstractController(cc) with ApiSupport:

  /** `GET /api/v1/goods/{id}/comments` — published top-level reviews of one product. */
  def forGoods(id: Long, page: Option[Int], page_size: Option[Int]): Action[AnyContent] = Action { req =>
    orFail(Paging.of(page, page_size), req) { p =>
      Ok(db.readOnly(conn => comments.forGoods(conn, id, p)))
    }
  }

  def createForGoods(id: Long): Action[AnyContent] = Action { req => notReady("POST /api/v1/goods/{id}/comments")(req) }

  def mine(page: Option[Int], page_size: Option[Int]): Action[AnyContent] = Action { req => notReady("GET /api/v1/me/comments")(req) }

  def destroy(id: Long): Action[AnyContent] = Action { req => notReady("DELETE /api/v1/me/comments/{id}")(req) }
