package controllers

import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** `GET /api/v1/home` — maps PHP index.php. */
class HomeController @Inject() (cc: ControllerComponents) extends AbstractController(cc) with ApiSupport:
  def home : Action[AnyContent] = Action { req => notReady("GET /api/v1/home")(req) }
