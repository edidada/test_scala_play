package controllers

import ecs.{Db, GoodsRepo, Paging}
import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/**
 * Goods endpoints — maps PHP `search.php` / `goods.php` / `compare.php` /
 * `goods_script.php` / `quotation.php` / `gallery.php`.
 */
class GoodsController @Inject() (cc: ControllerComponents, db: Db, goodsRepo: GoodsRepo)
  extends AbstractController(cc) with ApiSupport:

  def list(page: Option[Int], page_size: Option[Int], category_id: Option[Int], brand_id: Option[Int],
    q: Option[String], sort: Option[String]): Action[AnyContent] = Action { req =>
      orFail(Paging.of(page, page_size), req) { p =>
        Ok(db.readOnly(conn =>
          goodsRepo.search(conn, category_id.map(_.toLong), brand_id.map(_.toLong), q, p, sort)))
      }
    }

  def detail(id: Long): Action[AnyContent] = Action { req => notReady("GET /api/v1/goods/{id}")(req) }

  def priceQuote(id: Long): Action[AnyContent] = Action { req => notReady("POST /api/v1/goods/{id}/price-quote")(req) }

  def gallery(id: Long): Action[AnyContent] = Action { req => notReady("GET /api/v1/goods/{id}/gallery")(req) }

  def compare(goods_ids: Option[String]): Action[AnyContent] = Action { req => notReady("GET /api/v1/compare")(req) }

  def quotation(page: Option[Int], page_size: Option[Int], category_id: Option[Int], brand_id: Option[Int],
    q: Option[String]): Action[AnyContent] =
    Action { req => notReady("GET /api/v1/quotation")(req) }
