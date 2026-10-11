package controllers

import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** Document endpoints — maps PHP sitemaps.php / feed.php. */
class FeedController @Inject() (cc: ControllerComponents) extends AbstractController(cc) with ApiSupport:
  def sitemap : Action[AnyContent] = Action { req => notReady("GET /sitemap.xml")(req) }

  def feed(cat: Option[Long], brand: Option[Long]) : Action[AnyContent] = Action { req => notReady("GET /feed.xml")(req) }
