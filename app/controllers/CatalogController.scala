package controllers

import ecs.{CatalogRepo, Db}
import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** `GET /api/v1/catalog` — maps PHP `catalog.php`. */
class CatalogController @Inject() (cc: ControllerComponents, db: Db, catalog: CatalogRepo)
  extends AbstractController(cc) with ApiSupport:

  def catalog: Action[AnyContent] = Action {
    Ok(db.readOnly(conn => catalog.catalog(conn)))
  }

  def brands: Action[AnyContent] = Action { req => notReady("GET /api/v1/brands")(req) }

  def brandGoods(id: Long, page: Option[Int], page_size: Option[Int], sort: Option[String]): Action[AnyContent] =
    Action { req => notReady("GET /api/v1/brands/{id}/goods")(req) }
