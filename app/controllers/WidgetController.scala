package controllers

import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** `GET /goods-widget.js` — maps PHP goods_script.php. */
class WidgetController @Inject() (cc: ControllerComponents) extends AbstractController(cc) with ApiSupport:
  def goodsWidget(cat_id: Option[Long], brand_id: Option[Long], goods_num: Option[Int], intro_type: Option[String]) : Action[AnyContent] =
    Action { req => notReady("GET /goods-widget.js")(req) }
