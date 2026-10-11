package controllers

import ecs.{ApiError, Body, Db, GoodsRepo, Paging, QuoteFailure}
import javax.inject.Inject
import play.api.libs.json.JsValue
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

  def detail(id: Long): Action[AnyContent] = Action { req =>
    db.readOnly { conn =>
      goodsRepo.detail(conn, id) match
        case Some(payload) => Ok(payload)
        case None          => ApiError.notFound("goods not found", req)
    }
  }

  def priceQuote(id: Long): Action[JsValue] = Action(parse.json) { req =>
    val body = Body(jsonBody(req))
    val parsed =
      for
        quantity <- body.long("quantity", min = 1, max = 999)
        productId <- body.optLong("product_id", min = 1)
        attributeIds <- body.optIds("attribute_ids", max = 50)
        _ <- body.rejectUnknown(Set("quantity", "product_id", "attribute_ids"))
          .toLeft(())
      yield (quantity, productId, attributeIds)

    orFail(parsed, req) { case (quantity, productId, attributeIds) =>
      db.readOnly { conn =>
        goodsRepo.priceQuote(conn, id, quantity, productId, attributeIds) match
          case Right(payload)                    => Ok(payload)
          case Left(QuoteFailure.GoodsNotFound)  => ApiError.notFound("goods not found", req)
          case Left(QuoteFailure.AttributeNotOwned) =>
            ApiError.badRequest("attribute_ids must reference attributes of this goods", req, Some("attribute_ids"))
          case Left(QuoteFailure.ProductNotOwned) =>
            ApiError.badRequest("product_id must reference a product of this goods", req, Some("product_id"))
      }
    }
  }

  def gallery(id: Long): Action[AnyContent] = Action { req => notReady("GET /api/v1/goods/{id}/gallery")(req) }

  def compare(goods_ids: Option[String]): Action[AnyContent] = Action { req => notReady("GET /api/v1/compare")(req) }

  def quotation(page: Option[Int], page_size: Option[Int], category_id: Option[Int], brand_id: Option[Int],
    q: Option[String]): Action[AnyContent] =
    Action { req => notReady("GET /api/v1/quotation")(req) }
