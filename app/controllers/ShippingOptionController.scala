package controllers

import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** `GET /api/v1/shipping-options` — maps PHP myship.php. */
class ShippingOptionController @Inject() (cc: ControllerComponents) extends AbstractController(cc) with ApiSupport:
  def list(country_id: Option[Long], province_id: Option[Long], city_id: Option[Long], district_id: Option[Long]) : Action[AnyContent] =
    Action { req => notReady("GET /api/v1/shipping-options")(req) }
