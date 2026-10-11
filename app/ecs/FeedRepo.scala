package ecs

import play.api.Configuration

import java.sql.Connection
import javax.inject.{Inject, Singleton}

/**
 * Document-producing endpoints (PHP `sitemaps.php`, `feed.php`).
 *
 * Absolute URLs always come from the `site.base_url` configuration and never
 * from the request Host header, so a poisoned header cannot put foreign links
 * into a file search engines crawl.
 */
@Singleton
class FeedRepo @Inject() (db: Db, config: Configuration):

  private val baseUrl: String = config.get[String]("site.base_url").stripSuffix("/")

  /** XML escaping for values interpolated into the documents. */
  def escape(text: String): String =
    text.flatMap {
      case '<'  => "&lt;"
      case '>'  => "&gt;"
      case '&'  => "&amp;"
      case '"'  => "&quot;"
      case '\'' => "&apos;"
      case c    => c.toString
    }

  private def url(loc: String): String = s"  <url><loc>${escape(loc)}</loc></url>"

  /** `GET /sitemap.xml` — home, visible categories, article categories, up to 300 public goods, public articles. */
  def sitemap(conn: Connection): String =
    val home = url(s"$baseUrl/")
    val categories = db.rows(conn,
      "SELECT cat_id FROM category WHERE is_show = 1 ORDER BY cat_id").map(r => url(s"$baseUrl/api/v1/categories/${r.long("cat_id")}/goods"))
    val articleCategories = db.rows(conn,
      "SELECT cat_id FROM article_cat WHERE is_show = 1 ORDER BY cat_id").map(r => url(s"$baseUrl/api/v1/article-categories/${r.long("cat_id")}/articles"))
    val goods = db.rows(conn,
      """SELECT goods_id FROM goods
        |WHERE is_on_sale = 1 AND is_delete = 0
        |ORDER BY goods_id DESC
        |LIMIT 300""".stripMargin).map(r => url(s"$baseUrl/api/v1/goods/${r.long("goods_id")}"))
    val articles = db.rows(conn,
      "SELECT article_id FROM article WHERE is_open = 1 ORDER BY article_id").map(r => url(s"$baseUrl/api/v1/articles/${r.long("article_id")}"))

    s"""<?xml version="1.0" encoding="UTF-8"?>
       |<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
       |${(home :: categories ::: articleCategories ::: goods ::: articles).mkString("\n")}
       |</urlset>
       |""".stripMargin
