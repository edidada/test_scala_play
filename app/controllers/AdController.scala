package controllers

import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** Advertising endpoints — maps PHP affiche.php / cycle_image.php. */
class AdController @Inject() (cc: ControllerComponents) extends AbstractController(cc) with ApiSupport:
  def cycleImage : Action[AnyContent] = Action { req => notReady("GET /cycle-image.xml")(req) }

  def detail(id: Long) : Action[AnyContent] = Action { req => notReady("GET /api/v1/ads/{id}")(req) }

  def click(id: Long) : Action[AnyContent] = Action { req => notReady("POST /api/v1/ads/{id}/click")(req) }
