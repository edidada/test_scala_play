package controllers

import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** Orders — maps PHP flow.php?step=done and the order actions of user.php. */
class OrderController @Inject() (cc: ControllerComponents) extends AbstractController(cc) with ApiSupport:
  def create : Action[AnyContent] = Action { req => notReady("POST /api/v1/orders")(req) }

  def index(page: Option[Int], page_size: Option[Int]) : Action[AnyContent] = Action { req => notReady("GET /api/v1/me/orders")(req) }

  def merge : Action[AnyContent] = Action { req => notReady("POST /api/v1/me/orders/merge")(req) }

  def show(id: Long) : Action[AnyContent] = Action { req => notReady("GET /api/v1/me/orders/{id}")(req) }

  def statusByNumber(orderSn: String) : Action[AnyContent] = Action { req => notReady("GET /api/v1/me/orders/by-number/{order_sn}/status")(req) }

  def cancel(id: Long) : Action[AnyContent] = Action { req => notReady("POST /api/v1/me/orders/{id}/cancel")(req) }

  def confirmReceived(id: Long) : Action[AnyContent] = Action { req => notReady("POST /api/v1/me/orders/{id}/received")(req) }

  def returnToCart(id: Long) : Action[AnyContent] = Action { req => notReady("POST /api/v1/me/orders/{id}/cart")(req) }

  def applySurplus(id: Long) : Action[AnyContent] = Action { req => notReady("PATCH /api/v1/me/orders/{id}/surplus")(req) }

  def changePayment(id: Long) : Action[AnyContent] = Action { req => notReady("PATCH /api/v1/me/orders/{id}/payment")(req) }

  def changeAddress(id: Long) : Action[AnyContent] = Action { req => notReady("PATCH /api/v1/me/orders/{id}/address")(req) }

  def groupBuys(page: Option[Int], page_size: Option[Int]) : Action[AnyContent] = Action { req => notReady("GET /api/v1/me/group-buys")(req) }

  def groupBuy(id: Long) : Action[AnyContent] = Action { req => notReady("GET /api/v1/me/group-buys/{id}")(req) }

  def affiliate(page: Option[Int], page_size: Option[Int]) : Action[AnyContent] = Action { req => notReady("GET /api/v1/me/affiliate")(req) }

  def shipments(page: Option[Int], page_size: Option[Int]) : Action[AnyContent] = Action { req => notReady("GET /api/v1/me/shipments")(req) }

  def pointOptions : Action[AnyContent] = Action { req => notReady("GET /api/v1/me/points/conversion-options")(req) }

  def convertPoints : Action[AnyContent] = Action { req => notReady("POST /api/v1/me/points/conversions")(req) }

  def clearBrowsingHistory : Action[AnyContent] = Action { req => notReady("DELETE /api/v1/me/browsing-history")(req) }
