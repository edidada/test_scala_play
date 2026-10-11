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

  /** Whether shoppers may open this article category at all. */
  def categoryVisible(conn: Connection, catId: Long): Boolean =
    db.row(conn, "SELECT cat_id FROM article_cat WHERE cat_id = ? AND is_show = 1", Seq(catId)).isDefined

  /** `GET /api/v1/article-categories/{id}/articles` — paginated list newest first. */
  def categoryArticles(conn: Connection, catId: Long, page: Paging.Page): JsObject =
    val total = db.count(conn,
      "SELECT COUNT(*) FROM article a WHERE a.cat_id = ? AND a.is_open = 1", Seq(catId))
    val rows = db.rows(conn,
      s"""SELECT $SelectColumns
         |FROM article a JOIN article_cat ac ON ac.cat_id = a.cat_id
         |WHERE a.cat_id = ? AND a.is_open = 1
         |ORDER BY a.article_id DESC
         |LIMIT ? OFFSET ?""".stripMargin, Seq(catId, page.pageSize, page.offset))
    ApiJson.page(page.page, page.pageSize, total, rows.map(Views.article))

  /** `GET /api/v1/articles/{id}` — full body of one opened article. */
  def detail(conn: Connection, articleId: Long): Option[JsObject] =
    db.row(conn,
      s"""SELECT $SelectColumns, a.content
         |FROM article a JOIN article_cat ac ON ac.cat_id = a.cat_id
         |WHERE a.article_id = ? AND a.is_open = 1""".stripMargin, Seq(articleId))
      .map(r => Views.article(r) ++ Json.obj("content" -> r.str("content")))
