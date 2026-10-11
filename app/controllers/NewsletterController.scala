package controllers

import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** Newsletter lifecycle — maps the four jobs of PHP user.php?act=email_list. */
class NewsletterController @Inject() (cc: ControllerComponents) extends AbstractController(cc) with ApiSupport:
  def subscribe : Action[AnyContent] = Action { req => notReady("POST /api/v1/newsletter-subscriptions")(req) }

  def unsubscribe : Action[AnyContent] = Action { req => notReady("DELETE /api/v1/newsletter-subscriptions")(req) }

  def confirmSubscribe : Action[AnyContent] = Action { req => notReady("POST /api/v1/newsletter-subscriptions/confirm")(req) }

  def confirmUnsubscribe : Action[AnyContent] = Action { req => notReady("POST /api/v1/newsletter-unsubscriptions/confirm")(req) }
