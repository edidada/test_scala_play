package controllers

import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** Cart — maps PHP flow.php?step=cart|add_to_cart|update_cart|drop_goods. */
class CartController @Inject() (cc: ControllerComponents) extends AbstractController(cc) with ApiSupport:
  def index : Action[AnyContent] = Action { req => notReady("GET /api/v1/me/cart")(req) }

  def create : Action[AnyContent] = Action { req => notReady("POST /api/v1/me/cart")(req) }

  def update(id: Long) : Action[AnyContent] = Action { req => notReady("PATCH /api/v1/me/cart/{id}")(req) }

  def destroy(id: Long) : Action[AnyContent] = Action { req => notReady("DELETE /api/v1/me/cart/{id}")(req) }
