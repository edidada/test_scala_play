package controllers

import ecs.{ApiError, AuthedAction, Body, CommentRepo, Db, Paging}
import javax.inject.Inject
import play.api.libs.json.JsValue
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

  /**
   * `POST /api/v1/goods/{id}/comments` — the author always comes from the bearer
   * session, never from the body (docs/03_api_contract.md).
   */
  def createForGoods(id: Long): Action[JsValue] = authed(parse.json) { req =>
    val body = Body(req.body)
    val parsed =
      for
        content <- body.str("content", maxBytes = 2000)
        _ <- body.rejectUnknown(Set("content")).toLeft(())
      yield content

    orFail(parsed, req) { content =>
      db.inTransaction(conn => comments.create(conn, id, req.user.id, req.user.userName, content)) match
        case Some(payload) => Created(payload)
        case None          => ApiError.notFound("goods not found or not on sale", req)
    }
  }

  def mine(page: Option[Int], page_size: Option[Int]): Action[AnyContent] = Action { req => notReady("GET /api/v1/me/comments")(req) }

  def destroy(id: Long): Action[AnyContent] = Action { req => notReady("DELETE /api/v1/me/comments/{id}")(req) }
