package controllers

import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** `GET /api/v1/categories/{id}/goods` — maps PHP category.php. */
class CategoryController @Inject() (cc: ControllerComponents) extends AbstractController(cc) with ApiSupport:
  def goods(id: Long, page: Option[Int], page_size: Option[Int], sort: Option[String]) : Action[AnyContent] =
    Action { req => notReady("GET /api/v1/categories/{id}/goods")(req) }
