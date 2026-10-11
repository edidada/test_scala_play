package controllers

import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** Checkout — maps PHP flow.php?step=checkout. */
class CheckoutController @Inject() (cc: ControllerComponents) extends AbstractController(cc) with ApiSupport:
  def options : Action[AnyContent] = Action { req => notReady("GET /api/v1/checkout/options")(req) }

  def quote : Action[AnyContent] = Action { req => notReady("POST /api/v1/checkout/quote")(req) }
