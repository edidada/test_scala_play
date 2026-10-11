package controllers

import ecs.{ApiError, AuthedAction, Body, Db, FieldError, Paging, TagRepo}
import javax.inject.Inject
import play.api.libs.json.JsValue
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** Tag endpoints — maps PHP tag_cloud.php / user.php?act=add_tag|tag_list|act_del_tag. */
class TagController @Inject() (cc: ControllerComponents, db: Db, tags: TagRepo, authed: AuthedAction)
  extends AbstractController(cc) with ApiSupport:

  /** `GET /api/v1/tags` — public tag cloud over saleable goods only. */
  def cloud: Action[AnyContent] = Action {
    Ok(db.readOnly(conn => tags.cloud(conn)))
  }

  /** `POST /api/v1/goods/{id}/tags` — words are stored against the bearer user. */
  def addForGoods(id: Long): Action[JsValue] = authed(parse.json) { req =>
    val body = Body(req.body)
    val parsed =
      for
        raw <- body.str("tag", maxBytes = 2600)
        _ <- body.rejectUnknown(Set("tag")).toLeft(())
        words <- TagController.words(raw)
      yield words

    orFail(parsed, req) { words =>
      db.inTransaction(conn => tags.add(conn, id, req.user.id, words)) match
        case Some(payload) => Created(payload)
        case None          => ApiError.notFound("goods not found or not on sale", req)
    }
  }

  /** `GET /api/v1/me/tags` — the bearer user's own tag words. */
  def mine(page: Option[Int], page_size: Option[Int]): Action[AnyContent] = authed { req =>
    orFail(Paging.of(page, page_size), req) { p =>
      Ok(db.readOnly(conn => tags.mine(conn, req.user.id, p)))
    }
  }

  /** `DELETE /api/v1/me/tags` — idempotent: a missing word still returns 204. */
  def destroyMine: Action[JsValue] = authed(parse.json) { req =>
    val body = Body(req.body)
    val parsed =
      for
        raw <- body.str("tag", maxBytes = 255)
        _ <- body.rejectUnknown(Set("tag")).toLeft(())
      yield raw.trim

    orFail(parsed, req) { word =>
      db.inTransaction(conn => tags.deleteMine(conn, req.user.id, word))
      NoContent
    }
  }

object TagController:
  /** `tag` is 1--10 comma separated words, each at most 255 bytes. */
  def words(raw: String): Either[FieldError, List[String]] =
    val parts = raw.split(",").toList.map(_.trim).filter(_.nonEmpty)
    if parts.isEmpty then Left(FieldError("tag", "tag must contain at least one word"))
    else if parts.size > 10 then Left(FieldError("tag", "tag must contain at most 10 words"))
    else if parts.exists(_.getBytes("UTF-8").length > 255) then
      Left(FieldError("tag", "each tag word must be at most 255 bytes"))
    else Right(parts)
