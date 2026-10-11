package controllers

import ecs.{Db, FieldError, WidgetRepo}
import javax.inject.Inject
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** `GET /goods-widget.js` — maps PHP `goods_script.php`. */
class WidgetController @Inject() (cc: ControllerComponents, db: Db, widgets: WidgetRepo)
  extends AbstractController(cc) with ApiSupport:

  def goodsWidget(cat_id: Option[Long], brand_id: Option[Long], goods_num: Option[Int],
    intro_type: Option[String]): Action[AnyContent] = Action { req =>
      val parsed =
        for
          num <- WidgetController.num(goods_num)
          intro <- WidgetController.intro(intro_type, widgets.accepts)
        yield (num, intro)

      orFail(parsed, req) { case (num, intro) =>
        val payload = db.readOnly(conn => widgets.goods(conn, cat_id, brand_id, num, intro))
        // Escape `</` so an embedded <script> tag in shop data cannot close the tag.
        val safe = Json.stringify(payload).replace("</", "<\\/")
        js(s"window.cppEcshopGoodsWidget = $safe;")
      }
    }

object WidgetController:
  val DefaultNum = 10
  val MaxNum     = 50
  val DefaultIntro = "is_hot"

  def num(raw: Option[Int]): Either[FieldError, Int] = raw match
    case None                     => Right(DefaultNum)
    case Some(v) if v >= 1 && v <= MaxNum => Right(v)
    case Some(_)                  => Left(FieldError("goods_num", s"goods_num must be between 1 and $MaxNum"))

  def intro(raw: Option[String], accepts: String => Boolean): Either[FieldError, String] =
    raw.map(_.trim).filter(_.nonEmpty) match
      case None      => Right(DefaultIntro)
      case Some(text) if accepts(text) => Right(text)
      case Some(_) => Left(FieldError("intro_type", "intro_type must be one of is_best, is_new, is_hot, is_promote, is_random"))
