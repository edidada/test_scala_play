package controllers

import ecs.{ApiError, Body, Db, ErrorCode, FieldError, MarketingRepo, Paging, VoteFailure}
import javax.inject.Inject
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents, Request}

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

  def packages(page: Option[Int], page_size: Option[Int]): Action[AnyContent] = Action { req =>
    orFail(Paging.of(page, page_size), req) { p =>
      Ok(db.readOnly(conn => marketing.packages(conn, p)))
    }
  }

  /** `GET /api/v1/topics/{id}` — maps PHP `topic.php?topic_id=`. */
  def topic(id: Long): Action[AnyContent] = Action { req =>
    db.readOnly { conn =>
      marketing.topic(conn, id) match
        case Some(payload) => Ok(payload)
        case None          => ApiError.notFound("topic not found", req)
    }
  }

  /** `GET /api/v1/votes/current` — maps PHP `vote.php`. */
  def voteCurrent: Action[AnyContent] = Action { req =>
    db.readOnly { conn =>
      marketing.currentVote(conn) match
        case Some(payload) => Ok(payload)
        case None          => ApiError.notFound("no vote is currently open", req)
    }
  }

  /**
   * `POST /api/v1/votes/{id}/responses` — the ballot is keyed on the socket
   * address; forwarded headers are deliberately ignored (docs/03_api_contract.md).
   */
  def voteRespond(id: Long): Action[JsValue] = Action(parse.json) { req =>
    val body = Body(jsonBody(req))
    val parsed =
      for
        optionIds <- body.ids("option_ids", max = 20)
        _ <- body.rejectUnknown(Set("option_ids")).toLeft(())
      yield optionIds

    orFail(parsed, req) { optionIds =>
      val ip = PromotionController.ballotIp(req)
      val outcome = db.inTransaction(conn => marketing.respond(conn, id, optionIds, ip))
      outcome match
        case Right(payload)        => Created(payload)
        case Left(VoteFailure.NotFound)         => ApiError.notFound("vote not found", req)
        case Left(VoteFailure.Closed)           => ApiError.notFound("vote is not open", req)
        case Left(VoteFailure.Duplicate)        => ApiError.conflict(ErrorCode.Conflict, "this address has already voted", req)
        case Left(VoteFailure.OptionNotOwned)   => fail(FieldError("option_ids", "option_ids must reference options of this vote"), req)
        case Left(VoteFailure.NotMultiple)      => fail(FieldError("option_ids", "this vote accepts exactly one option"), req)
    }
  }

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

  /**
   * The ballot key is the socket address only. `X-Forwarded-For` is never used,
   * because a client could rotate that header to vote again (docs/03_api_contract.md).
   */
  def ballotIp(req: Request[?]): String =
    val raw = Option(req.remoteAddress).map(_.trim).filter(_.nonEmpty).getOrElse("unknown")
    if raw.length <= 45 then raw else raw.take(45)
