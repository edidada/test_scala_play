package controllers

import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** Balance requests — maps PHP user.php?act=act_account|account_log|cancel|account_detail|pay. */
class AccountController @Inject() (cc: ControllerComponents) extends AbstractController(cc) with ApiSupport:
  def listRequests(page: Option[Int], page_size: Option[Int]) : Action[AnyContent] = Action { req => notReady("GET /api/v1/me/account/requests")(req) }

  def createRequest : Action[AnyContent] = Action { req => notReady("POST /api/v1/me/account/requests")(req) }

  def cancelRequest(id: Long) : Action[AnyContent] = Action { req => notReady("DELETE /api/v1/me/account/requests/{id}")(req) }

  def transactions(page: Option[Int], page_size: Option[Int]) : Action[AnyContent] = Action { req => notReady("GET /api/v1/me/account/transactions")(req) }

  def payRequest(id: Long) : Action[AnyContent] = Action { req => notReady("POST /api/v1/me/account/requests/{id}/payment")(req) }
