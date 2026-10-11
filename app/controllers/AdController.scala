package controllers

import ecs.{AdRepo, ApiError, Body, Db}
import javax.inject.Inject
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** Advertising endpoints — maps PHP `affiche.php` / `cycle_image.php`. */
class AdController @Inject() (cc: ControllerComponents, db: Db, ads: AdRepo)
  extends AbstractController(cc) with ApiSupport:

  def cycleImage: Action[AnyContent] = Action {
    xml(db.readOnly(conn => ads.cycleImage(conn)))
  }

  def detail(id: Long): Action[AnyContent] = Action { req =>
    db.readOnly { conn =>
      ads.detail(conn, id) match
        case Some(payload) => Ok(payload)
        case None          => ApiError.notFound("ad not found", req)
    }
  }

  def click(id: Long): Action[JsValue] = Action(parse.json) { req =>
    val body = Body(jsonBody(req))
    val parsed =
      for
        referer <- body.optStr("referer", maxBytes = 255) match
            case None      => Right("")
            case Some(res) => res
        _ <- body.rejectUnknown(Set("referer")).toLeft(())
      yield referer

    orFail(parsed, req) { referer =>
      val outcome = db.inTransaction { conn =>
        ads.click(conn, id, referer)
      }
      outcome match
        case Some(redirect) => Ok(Json.obj("redirect_url" -> redirect))
        case None           => ApiError.notFound("ad not found", req)
    }
  }
