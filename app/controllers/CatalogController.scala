package controllers

import ecs.{ApiError, CatalogRepo, Db, GoodsRepo, Paging}
import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** `GET /api/v1/catalog`, `/api/v1/brands*` — maps PHP `catalog.php` / `brand.php`. */
class CatalogController @Inject() (cc: ControllerComponents, db: Db, catalogRepo: CatalogRepo, goodsRepo: GoodsRepo)
  extends AbstractController(cc) with ApiSupport:

  def catalog: Action[AnyContent] = Action {
    Ok(db.readOnly(conn => catalogRepo.catalog(conn)))
  }

  def brands(page: Option[Int], page_size: Option[Int]): Action[AnyContent] = Action { req =>
    orFail(Paging.of(page, page_size), req) { p =>
      Ok(db.readOnly(conn => catalogRepo.brands(conn, p)))
    }
  }

  def brandGoods(id: Long, page: Option[Int], page_size: Option[Int], sort: Option[String]): Action[AnyContent] =
    Action { req =>
      orFail(Paging.of(page, page_size), req) { p =>
        db.readOnly { conn =>
          if !goodsRepo.brandVisible(conn, id) then ApiError.notFound("brand not found", req)
          else Ok(goodsRepo.list(conn, "g.brand_id = ?", Seq(id), p, sort))
        }
      }
    }
