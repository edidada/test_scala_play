package controllers

import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/**
 * Promotion endpoints — maps PHP group_buy.php / auction.php / snatch.php /
 * activity.php / package.php / topic.php / vote.php / exchange.php.
 */
class PromotionController @Inject() (cc: ControllerComponents) extends AbstractController(cc) with ApiSupport:
  def promotions(page: Option[Int], page_size: Option[Int]) : Action[AnyContent] =
    Action { req => notReady("GET /api/v1/promotions")(req) }

  def promotion(id: Long) : Action[AnyContent] = Action { req => notReady("GET /api/v1/promotions/{id}")(req) }

  def activities : Action[AnyContent] = Action { req => notReady("GET /api/v1/activities")(req) }

  def packages : Action[AnyContent] = Action { req => notReady("GET /api/v1/packages")(req) }

  def topic(id: Long) : Action[AnyContent] = Action { req => notReady("GET /api/v1/topics/{id}")(req) }

  def voteCurrent : Action[AnyContent] = Action { req => notReady("GET /api/v1/votes/current")(req) }

  def voteRespond(id: Long) : Action[AnyContent] = Action { req => notReady("POST /api/v1/votes/{id}/responses")(req) }

  def exchangeGoods(category_id: Option[Int], integral_min: Option[Long], integral_max: Option[Long], page: Option[Int], page_size: Option[Int], sort: Option[String]) : Action[AnyContent] =
    Action { req => notReady("GET /api/v1/exchange-goods")(req) }

  def exchangeGoodsDetail(id: Long) : Action[AnyContent] = Action { req => notReady("GET /api/v1/exchange-goods/{id}")(req) }
