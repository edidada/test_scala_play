package controllers

import ecs.{ApiError, Db, FieldError, MarketingRepo, Paging}
import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/**
 * Promotion endpoints — maps PHP group_buy.php / auction.php / snatch.php /
 * activity.php / package.php / topic.php / vote.php / exchange.php.
 */
class PromotionController @Inject() (cc: ControllerComponents, db: Db, marketing: MarketingRepo)
  extends AbstractController(cc) with ApiSupport:

  def promotions(page: Option[Int], page_size: Option[Int]): Action[AnyContent] = Action { req =>
    val parsed =
      for
        p <- Paging.of(page, page_size)
        kind <- PromotionController.activityType(req.getQueryString("type"), marketing.ActivityTypes)
      yield (p, kind)

    orFail(parsed, req) { case (p, kind) =>
      Ok(db.readOnly(conn => marketing.promotions(conn, kind, p)))
    }
  }

  def promotion(id: Long): Action[AnyContent] = Action { req =>
    db.readOnly { conn =>
      marketing.promotion(conn, id) match
        case Some(payload) => Ok(payload)
        case None          => ApiError.notFound("promotion not found", req)
    }
  }

  def activities(page: Option[Int], page_size: Option[Int]): Action[AnyContent] = Action { req =>
    orFail(Paging.of(page, page_size), req) { p =>
      Ok(db.readOnly(conn => marketing.activities(conn, p)))
    }
  }

  def packages: Action[AnyContent] = Action { req => notReady("GET /api/v1/packages")(req) }

  def topic(id: Long): Action[AnyContent] = Action { req => notReady("GET /api/v1/topics/{id}")(req) }

  def voteCurrent: Action[AnyContent] = Action { req => notReady("GET /api/v1/votes/current")(req) }

  def voteRespond(id: Long): Action[AnyContent] = Action { req => notReady("POST /api/v1/votes/{id}/responses")(req) }

  def exchangeGoods(category_id: Option[Int], integral_min: Option[Long], integral_max: Option[Long],
    page: Option[Int], page_size: Option[Int], sort: Option[String]): Action[AnyContent] =
    Action { req => notReady("GET /api/v1/exchange-goods")(req) }

  def exchangeGoodsDetail(id: Long): Action[AnyContent] = Action { req => notReady("GET /api/v1/exchange-goods/{id}")(req) }

object PromotionController:
  /** `type` is a reserved route word, so it is read from the query string. */
  def activityType(raw: Option[String], allowed: Set[Int]): Either[FieldError, Option[Int]] =
    raw.map(_.trim).filter(_.nonEmpty) match
      case None => Right(None)
      case Some(text) =>
        text.toIntOption match
          case Some(value) if allowed.contains(value) => Right(Some(value))
          case Some(_) => Left(FieldError("type", s"type must be one of ${allowed.toSeq.sorted.mkString(", ")}"))
          case None    => Left(FieldError("type", "type must be an integer"))
