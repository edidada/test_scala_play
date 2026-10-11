package controllers

import ecs.{ApiError, AuthedAction, Body, Db, ErrorCode, FavoriteFailure, FavoriteRepo, Paging}
import javax.inject.Inject
import play.api.libs.json.{JsObject, JsValue}
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents, RequestHeader, Result}

/**
 * Favourites — maps PHP user.php?act=collection_list|add_to_attention|delete_collection.
 */
class FavoriteController @Inject() (cc: ControllerComponents, db: Db, favorites: FavoriteRepo, authed: AuthedAction)
  extends AbstractController(cc) with ApiSupport:

  /** `GET /api/v1/me/favorites` */
  def index(page: Option[Int], page_size: Option[Int]): Action[AnyContent] = authed { req =>
    orFail(Paging.of(page, page_size), req) { p =>
      Ok(db.readOnly(conn => favorites.list(conn, req.user.id, p)))
    }
  }

  /** `POST /api/v1/me/favorites` — collecting is idempotent only through the unique key. */
  def create: Action[JsValue] = authed(parse.json) { req =>
    val body = Body(req.body)
    val parsed =
      for
        goodsId <- body.long("goods_id", min = 1)
        _       <- body.rejectUnknown(Set("goods_id")).toLeft(())
      yield goodsId

    orFail(parsed, req) { goodsId =>
      outcome(db.inTransaction(conn => favorites.add(conn, req.user.id, goodsId)), req, created = true)
    }
  }

  /** `PATCH /api/v1/me/favorites/{id}` — `{"attention": true}`. */
  def update(id: Long): Action[JsValue] = authed(parse.json) { req =>
    val body = Body(req.body)
    val parsed =
      for
        attention <- body.bool("attention")
        _         <- body.rejectUnknown(Set("attention")).toLeft(())
      yield attention

    orFail(parsed, req) { attention =>
      outcome(db.inTransaction(conn => favorites.setAttention(conn, req.user.id, id, attention)), req, created = false)
    }
  }

  /** `DELETE /api/v1/me/favorites/{id}` — id plus owner, 404 for anything else. */
  def destroy(id: Long): Action[AnyContent] = authed { req =>
    if db.inTransaction(conn => favorites.delete(conn, req.user.id, id)) then NoContent
    else ApiError.notFound("favorite not found", req)
  }

  private def outcome(result: Either[FavoriteFailure, JsObject], req: RequestHeader, created: Boolean): Result =
    result match
      case Right(payload) => if created then Created(payload) else Ok(payload)
      case Left(FavoriteFailure.GoodsNotFound) => ApiError.notFound("goods not found", req)
      case Left(FavoriteFailure.NotFound) => ApiError.notFound("favorite not found", req)
      case Left(FavoriteFailure.Duplicate) =>
        ApiError.conflict(ErrorCode.Conflict, "this goods is already in your favorites", req)
