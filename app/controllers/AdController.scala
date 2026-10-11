package controllers

import ecs.{AdRepo, ApiError, Db}
import javax.inject.Inject
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

  def click(id: Long): Action[AnyContent] = Action { req => notReady("POST /api/v1/ads/{id}/click")(req) }
