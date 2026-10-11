package controllers

import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** `GET /api/v1/regions` — maps PHP region.php. */
class RegionController @Inject() (cc: ControllerComponents) extends AbstractController(cc) with ApiSupport:
  def list(parent: Option[Long]) : Action[AnyContent] = Action { req => notReady("GET /api/v1/regions")(req) }
