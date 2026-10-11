package controllers

import ecs.{CatalogRepo, Db}
import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/**
 * `GET /api/v1/home` — maps PHP `index.php`.
 *
 * The homepage needs no authentication and no body: it is one read-only
 * snapshot of the visible catalog (categories, brands), the four legacy goods
 * groups and the newest published articles.
 */
class HomeController @Inject() (cc: ControllerComponents, db: Db, catalogRepo: CatalogRepo)
  extends AbstractController(cc) with ApiSupport:

  private val GroupSize = 10

  def home: Action[AnyContent] = Action {
    Ok(db.readOnly(conn => catalogRepo.home(conn, GroupSize)))
  }
