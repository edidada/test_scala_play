package controllers

import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** Payment gateway callbacks — maps PHP respond.php. */
class PaymentCallbackController @Inject() (cc: ControllerComponents) extends AbstractController(cc) with ApiSupport:
  def callback(provider: String) : Action[AnyContent] = Action { req => notReady("POST /api/v1/payments/{provider}/callback")(req) }
