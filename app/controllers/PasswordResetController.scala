package controllers

import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** Password recovery — maps PHP user.php?act=get_password|send_pwd_email|reset_password. */
class PasswordResetController @Inject() (cc: ControllerComponents) extends AbstractController(cc) with ApiSupport:
  def request : Action[AnyContent] = Action { req => notReady("POST /api/v1/password-resets")(req) }

  def confirm : Action[AnyContent] = Action { req => notReady("POST /api/v1/password-resets/confirm")(req) }
