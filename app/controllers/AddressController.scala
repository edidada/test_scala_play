package controllers

import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** Shipping addresses — maps PHP user.php?act=address_list|act_edit_address|drop_consignee. */
class AddressController @Inject() (cc: ControllerComponents) extends AbstractController(cc) with ApiSupport:
  def index : Action[AnyContent] = Action { req => notReady("GET /api/v1/me/addresses")(req) }

  def create : Action[AnyContent] = Action { req => notReady("POST /api/v1/me/addresses")(req) }

  def update(id: Long) : Action[AnyContent] = Action { req => notReady("PATCH /api/v1/me/addresses/{id}")(req) }

  def destroy(id: Long) : Action[AnyContent] = Action { req => notReady("DELETE /api/v1/me/addresses/{id}")(req) }
