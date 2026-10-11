package controllers

import ecs.{Db, RegionRepo}
import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** `GET /api/v1/regions` — maps PHP `region.php`. */
class RegionController @Inject() (cc: ControllerComponents, db: Db, regions: RegionRepo)
  extends AbstractController(cc) with ApiSupport:

  def list(parent: Option[Long]): Action[AnyContent] = Action {
    Ok(db.readOnly(conn => regions.list(conn, parent)))
  }
