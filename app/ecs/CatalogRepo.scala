package ecs

import play.api.libs.json.{JsObject, Json}

import java.sql.Connection
import javax.inject.{Inject, Singleton}

/**
 * Catalog reads shared by the storefront endpoints (PHP `index.php`,
 * `catalog.php`, `brand.php`, `category.php`).
 *
 * Every statement is static text or built from a fixed whitelist of column
 * names; request values only ever reach the database as bound parameters.
 */
@Singleton
class CatalogRepo @Inject() (db: Db):

  /** Goods a shopper is allowed to browse and buy (shared with [[GoodsRepo]]). */
  val Visible: String = GoodsRepo.Visible

  private val CategorySql: String =
    """SELECT c.cat_id, c.parent_id, c.cat_name, c.sort_order, c.keywords,
      |  (SELECT COUNT(*) FROM goods g
      |    WHERE g.cat_id = c.cat_id AND g.is_on_sale = 1 AND g.is_delete = 0 AND g.is_alone_sale = 1) AS goods_count
      |FROM category c
      |WHERE c.is_show = 1
      |ORDER BY c.parent_id, c.sort_order, c.cat_id""".stripMargin

  private val BrandSql: String =
    """SELECT b.brand_id, b.brand_name, b.brand_logo, b.site_url, b.sort_order,
      |  (SELECT COUNT(*) FROM goods g
      |    WHERE g.brand_id = b.brand_id AND g.is_on_sale = 1 AND g.is_delete = 0 AND g.is_alone_sale = 1) AS goods_count
      |FROM brand b
      |WHERE b.is_show = 1
      |ORDER BY b.sort_order, b.brand_id""".stripMargin

  /** Homepage goods groups: only these four legacy flags may filter a listing. */
  private val Groups: Map[String, String] = Map(
    "is_best"    -> "g.is_best = 1",
    "is_new"     -> "g.is_new = 1",
    "is_hot"     -> "g.is_hot = 1",
    "is_promote" -> "g.is_promote = 1 AND g.promote_start_date <= ? AND g.promote_end_date >= ?"
  )

  private val ArticleSql: String =
    """SELECT a.article_id, a.cat_id, ac.cat_name, a.title, a.author, a.article_desc, a.keywords
      |FROM article a JOIN article_cat ac ON ac.cat_id = a.cat_id
      |WHERE a.is_open = 1
      |ORDER BY a.article_id DESC
      |LIMIT 10""".stripMargin

  /** Visible goods of one homepage group, newest first, capped by `limit`. */
  private def group(conn: Connection, key: String, limit: Int, now: Long): List[JsObject] =
    val clause = Groups(key)
    val sql =
      s"""SELECT ${Views.goodsColumns("g")}
         |FROM goods g
         |WHERE $Visible AND $clause
         |ORDER BY g.add_time DESC, g.goods_id DESC
         |LIMIT $limit""".stripMargin
    val params = if key == "is_promote" then Seq(now, now) else Nil
    db.rows(conn, sql, params).map(row => Views.goodsSummary(row, now))

  private val BrandCountSql: String = "SELECT COUNT(*) FROM brand WHERE is_show = 1"

  /** Payload of `GET /api/v1/brands` (PHP `brand.php` index page). */
  def brands(conn: Connection, page: Paging.Page): JsObject =
    val total = db.count(conn, BrandCountSql, Nil)
    val sql = BrandSql + "\nLIMIT ? OFFSET ?"
    val items = db.rows(conn, sql, Seq(page.pageSize, page.offset)).map(Views.brand)
    ApiJson.page(page.page, page.pageSize, total, items)

  /** Payload of `GET /api/v1/catalog` (PHP `catalog.php`): flat, tree-ready lists. */
  def catalog(conn: Connection): JsObject =
    Json.obj(
      "categories" -> Json.toJson(db.rows(conn, CategorySql, Nil).map(Views.category)),
      "brands"     -> Json.toJson(db.rows(conn, BrandSql, Nil).map(Views.brand))
    )

  /** Payload of `GET /api/v1/home` (PHP `index.php`). */
  def home(conn: Connection, limitPerGroup: Int): JsObject =
    val now = ApiJson.nowUnix
    Json.obj(
      "categories"    -> Json.toJson(db.rows(conn, CategorySql, Nil).map(Views.category)),
      "brands"        -> Json.toJson(db.rows(conn, BrandSql, Nil).map(Views.brand)),
      "new_goods"     -> Json.toJson(group(conn, "is_new", limitPerGroup, now)),
      "hot_goods"     -> Json.toJson(group(conn, "is_hot", limitPerGroup, now)),
      "best_goods"    -> Json.toJson(group(conn, "is_best", limitPerGroup, now)),
      "promote_goods" -> Json.toJson(group(conn, "is_promote", limitPerGroup, now)),
      "articles"      -> Json.toJson(db.rows(conn, ArticleSql, Nil).map(Views.article))
    )
