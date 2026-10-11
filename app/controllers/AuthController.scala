package controllers

import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** Session endpoints — maps PHP user.php?act=act_register|act_login|logout|is_registered|check_email. */
class AuthController @Inject() (cc: ControllerComponents) extends AbstractController(cc) with ApiSupport:
  def register : Action[AnyContent] = Action { req => notReady("POST /api/v1/auth/register")(req) }

  def usernameAvailability : Action[AnyContent] = Action { req => notReady("POST /api/v1/auth/availability/username")(req) }

  def emailAvailability : Action[AnyContent] = Action { req => notReady("POST /api/v1/auth/availability/email")(req) }

  def login : Action[AnyContent] = Action { req => notReady("POST /api/v1/auth/login")(req) }

  def logout : Action[AnyContent] = Action { req => notReady("POST /api/v1/auth/logout")(req) }
