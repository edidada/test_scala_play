package controllers

import ecs.{Db, RegionRepo}
import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** `GET /api/v1/shipping-options` — maps PHP myship.php. */
class ShippingOptionController @Inject() (cc: ControllerComponents, db: Db, regions: RegionRepo)
  extends AbstractController(cc) with ApiSupport:

  /**
   * Public endpoint: the four region ids are validated level by level, and an
   * id that does not hang under the level above it becomes a 400.
   */
  def list(country_id: Option[Long], province_id: Option[Long], city_id: Option[Long],
    district_id: Option[Long]): Action[AnyContent] = Action { req =>
    db.readOnly { conn =>
      regions.shippingSelection(conn, country_id, province_id, city_id, district_id) match
        case Right(payload) => Ok(payload)
        case Left(fe)       => fail(fe, req)
    }
  }
