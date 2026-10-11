package controllers

import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** Account profile — maps PHP user.php?act=profile|act_edit_profile|act_edit_password. */
class MeController @Inject() (cc: ControllerComponents) extends AbstractController(cc) with ApiSupport:
  def show : Action[AnyContent] = Action { req => notReady("GET /api/v1/me")(req) }

  def update : Action[AnyContent] = Action { req => notReady("PATCH /api/v1/me")(req) }

  def updatePassword : Action[AnyContent] = Action { req => notReady("PATCH /api/v1/me/password")(req) }
