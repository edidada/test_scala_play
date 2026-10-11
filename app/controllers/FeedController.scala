package controllers

import ecs.{Db, FeedRepo, FieldError}
import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** Document endpoints — maps PHP `sitemaps.php` / `feed.php`. */
class FeedController @Inject() (cc: ControllerComponents, db: Db, feeds: FeedRepo)
  extends AbstractController(cc) with ApiSupport:

  def sitemap: Action[AnyContent] = Action {
    xml(db.readOnly(conn => feeds.sitemap(conn)))
  }

  /**
   * `GET /feed.xml?cat=&brand=&type=`.
   *
   * `type` is read straight from the query string because Play binds route
   * parameters positionally and `type` is a reserved word in a route signature.
   */
  def feed: Action[AnyContent] = Action { req =>
    val parsed =
      for
        cat <- FeedController.longParam(req.getQueryString("cat"), "cat")
        brand <- FeedController.longParam(req.getQueryString("brand"), "brand")
      yield (cat, brand)

    orFail(parsed, req) { case (cat, brand) =>
      db.readOnly { conn =>
        feeds.feed(conn, cat, brand, req.getQueryString("type")) match
          case Right(document) => xml(document)
          case Left(fe)        => fail(fe, req)
      }
    }
  }

object FeedController:
  /** Optional positive integer query parameter. */
  def longParam(raw: Option[String], name: String): Either[FieldError, Option[Long]] =
    raw.map(_.trim).filter(_.nonEmpty) match
      case None => Right(None)
      case Some(text) =>
        text.toLongOption.filter(_ > 0) match
          case Some(value) => Right(Some(value))
          case None        => Left(FieldError(name, s"$name must be a positive integer"))
