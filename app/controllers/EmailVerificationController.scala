package controllers

import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** Mailbox verification — maps PHP user.php?act=send_hash_mail|validate_email. */
class EmailVerificationController @Inject() (cc: ControllerComponents) extends AbstractController(cc) with ApiSupport:
  def request : Action[AnyContent] = Action { req => notReady("POST /api/v1/me/email-verifications")(req) }

  def confirm : Action[AnyContent] = Action { req => notReady("POST /api/v1/email-verifications/confirm")(req) }
