package controllers

import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** `GET /api/v1/articles/{id}`, `GET /api/v1/article-categories/{id}/articles` — maps PHP article.php. */
class ArticleController @Inject() (cc: ControllerComponents) extends AbstractController(cc) with ApiSupport:
  def detail(id: Long) : Action[AnyContent] = Action { req => notReady("GET /api/v1/articles/{id}")(req) }

  def categoryArticles(id: Long, page: Option[Int], page_size: Option[Int]) : Action[AnyContent] =
    Action { req => notReady("GET /api/v1/article-categories/{id}/articles")(req) }
