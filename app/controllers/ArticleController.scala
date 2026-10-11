package controllers

import ecs.{ApiError, ArticleRepo, Db, Paging}
import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** `GET /api/v1/articles/{id}`, `GET /api/v1/article-categories/{id}/articles` — maps PHP article.php / article_cat.php. */
class ArticleController @Inject() (cc: ControllerComponents, db: Db, articles: ArticleRepo)
  extends AbstractController(cc) with ApiSupport:

  def detail(id: Long): Action[AnyContent] = Action { req =>
    db.readOnly { conn =>
      articles.detail(conn, id) match
        case Some(payload) => Ok(payload)
        case None          => ApiError.notFound("article not found", req)
    }
  }

  def categoryArticles(id: Long, page: Option[Int], page_size: Option[Int]): Action[AnyContent] =
    Action { req =>
      orFail(Paging.of(page, page_size), req) { p =>
        db.readOnly { conn =>
          if !articles.categoryVisible(conn, id) then ApiError.notFound("article category not found", req)
          else Ok(articles.categoryArticles(conn, id, p))
        }
      }
    }
