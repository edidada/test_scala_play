package ecs

import play.api.libs.json.{JsObject, Json}

import java.sql.Connection
import javax.inject.{Inject, Singleton}

/**
 * Content reads (PHP `article.php`, `article_cat.php`).
 *
 * Articles are public, but only the ones the shop has opened are ever
 * returned; a closed or unknown article is indistinguishable (404).
 */
@Singleton
class ArticleRepo @Inject() (db: Db):

  private val SelectColumns: String =
    "a.article_id, a.cat_id, ac.cat_name, a.title, a.author, a.article_desc, a.keywords"

  /** `GET /api/v1/articles/{id}` — full body of one opened article. */
  def detail(conn: Connection, articleId: Long): Option[JsObject] =
    db.row(conn,
      s"""SELECT $SelectColumns, a.content
         |FROM article a JOIN article_cat ac ON ac.cat_id = a.cat_id
         |WHERE a.article_id = ? AND a.is_open = 1""".stripMargin, Seq(articleId))
      .map(r => Views.article(r) ++ Json.obj("content" -> r.str("content")))
