package controllers

import ecs.{Db, FeedRepo}
import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** Document endpoints — maps PHP `sitemaps.php` / `feed.php`. */
class FeedController @Inject() (cc: ControllerComponents, db: Db, feeds: FeedRepo)
  extends AbstractController(cc) with ApiSupport:

  def sitemap: Action[AnyContent] = Action {
    xml(db.readOnly(conn => feeds.sitemap(conn)))
  }

  def feed: Action[AnyContent] = Action { req =>
    notReady("GET /feed.xml")(req)
  }
