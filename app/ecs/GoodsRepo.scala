package ecs

import play.api.libs.json.{JsObject, Json}

import java.sql.Connection
import javax.inject.{Inject, Singleton}

/**
 * Goods listing and detail reads (PHP `brand.php`, `category.php`,
 * `search.php`, `goods.php`).
 *
 * Filters reach SQL as bound parameters only; ordering comes from a fixed
 * whitelist because an ORDER BY fragment cannot be parameterised.
 */
@Singleton
class GoodsRepo @Inject() (db: Db):

  /**
   * The core schema has no `sales_volume` column, so the legacy `sales` sort
   * orders by page hits, the closest audited popularity signal we keep.
   */
  val Sorts: Map[String, String] = Map(
    "price_asc"  -> "g.shop_price ASC, g.goods_id ASC",
    "price_desc" -> "g.shop_price DESC, g.goods_id ASC",
    "newest"     -> "g.add_time DESC, g.goods_id DESC",
    "sales"      -> "g.click_count DESC, g.goods_id DESC"
  )

  /** One paginated goods listing behind `clause`, which must use `?` placeholders. */
  def list(conn: Connection, clause: String, params: Seq[Any], page: Paging.Page, sort: Option[String]): JsObject =
    val orderBy = Paging.sort(sort, Sorts, "g.goods_id ASC")
    val where = s"${GoodsRepo.Visible} AND $clause"
    val total = db.count(conn, s"SELECT COUNT(*) FROM goods g WHERE $where", params)
    val sql =
      s"""SELECT ${Views.goodsColumns("g")}
         |FROM goods g
         |WHERE $where
         |ORDER BY $orderBy
         |LIMIT ? OFFSET ?""".stripMargin
    ApiJson.page(page.page, page.pageSize, total,
      db.rows(conn, sql, params ++ Seq(page.pageSize, page.offset)).map(Views.goodsSummary(_)))

  /** Escape a LIKE operand; `!` is the escape character used by the patterns below. */
  def escapeLike(text: String): String =
    text.replace("!", "!!").replace("%", "!%").replace("_", "!_")

  /**
   * Combined goods search (PHP `search.php?keywords=`): category subtree,
   * brand and a name/keywords/goods_sn text match, all as bound parameters.
   */
  def search(
    conn: Connection,
    categoryId: Option[Long],
    brandId: Option[Long],
    query: Option[String],
    page: Paging.Page,
    sort: Option[String]
  ): JsObject =
    val conditions = scala.collection.mutable.ListBuffer.empty[String]
    val params = scala.collection.mutable.ListBuffer.empty[Any]

    categoryId.foreach { cid =>
      val ids = categorySubtreeIds(conn, cid)
      val marks = Sql.marks(ids.size)
      conditions +=
        s"""(g.cat_id IN ($marks)
           |  OR EXISTS (SELECT 1 FROM goods_cat gc WHERE gc.goods_id = g.goods_id AND gc.cat_id IN ($marks)))""".stripMargin
      params ++= ids
      params ++= ids
    }

    brandId.foreach { bid =>
      conditions += "g.brand_id = ?"
      params += bid
    }

    query.map(_.trim).filter(_.nonEmpty).foreach { text =>
      val like = s"%${escapeLike(text)}%"
      conditions += "(g.goods_name LIKE ? ESCAPE '!' OR g.keywords LIKE ? ESCAPE '!' OR g.goods_sn LIKE ? ESCAPE '!')"
      params ++= Seq(like, like, like)
    }

    val clause = if conditions.isEmpty then "1 = 1" else conditions.mkString(" AND ")
    list(conn, clause, params.toList, page, sort)

  /** Existence check for a brand that shoppers may open. */  def brandVisible(conn: Connection, brandId: Long): Boolean =
    db.row(conn, "SELECT brand_id FROM brand WHERE brand_id = ? AND is_show = 1", Seq(brandId)).isDefined

  /** Existence check for a visible category. */
  def categoryVisible(conn: Connection, catId: Long): Boolean =
    db.row(conn, "SELECT cat_id FROM category WHERE cat_id = ? AND is_show = 1", Seq(catId)).isDefined

  /** Direct plus inherited category ids of a subtree, so listings can include children. */
  def categorySubtreeIds(conn: Connection, catId: Long): List[Long] =
    def walk(id: Long): List[Long] =
      val children = db.rows(conn, "SELECT cat_id FROM category WHERE parent_id = ?", Seq(id))
        .map(_.long("cat_id"))
      id :: children.flatMap(walk)
    walk(catId)

  /** One goods row for the detail page, including description and images. */
  def detail(conn: Connection, goodsId: Long): Option[JsObject] =
    val now = ApiJson.nowUnix
    val sql =
      s"""SELECT g.goods_id, g.cat_id, g.brand_id, c.cat_name, g.goods_sn, g.goods_name,
         |  g.goods_brief, g.keywords, g.goods_desc, g.goods_thumb, g.goods_img, g.original_img,
         |  g.shop_price, g.market_price, g.promote_price, g.promote_start_date, g.promote_end_date,
         |  g.goods_number, g.goods_weight, g.integral, g.is_on_sale, g.is_alone_sale, g.is_shipping,
         |  g.is_real, g.is_delete, g.is_best, g.is_new, g.is_hot, g.is_promote, g.click_count,
         |  g.add_time, g.last_update,
         |  b.brand_name
         |FROM goods g
         |JOIN category c ON c.cat_id = g.cat_id
         |LEFT JOIN brand b ON b.brand_id = g.brand_id
         |WHERE g.goods_id = ? AND g.is_delete = 0""".stripMargin
    db.row(conn, sql, Seq(goodsId)).map { row =>
      Views.goodsSummary(row, now) ++ Json.obj(
        "description"    -> row.str("goods_desc"),
        "image"          -> row.str("goods_img"),
        "original_image" -> row.str("original_img"),
        "category_name"  -> row.str("cat_name"),
        "brand_name"     -> row.str("brand_name"),
        "weight"         -> row.dec("goods_weight").bigDecimal.toPlainString,
        "is_shipping"    -> row.bool("is_shipping"),
        "is_real"        -> row.bool("is_real"),
        "click_count"    -> row.long("click_count"),
        "last_update"    -> Views.timestamp(row, "last_update"),
        "attributes"     -> Json.toJson(attributes(conn, row.long("goods_id"))),
        "products"       -> Json.toJson(products(conn, row.long("goods_id")))
      )
    }

  /** Attribute rows of one goods item, id-ordered for a stable quote signature. */
  def attributes(conn: Connection, goodsId: Long): List[JsObject] =
    db.rows(conn,
      """SELECT goods_attr_id, attr_id, attr_value, attr_price
        |FROM goods_attr WHERE goods_id = ? ORDER BY goods_attr_id""".stripMargin, Seq(goodsId))
      .map(r => Json.obj(
        "goods_attr_id" -> r.long("goods_attr_id"),
        "attr_id"       -> r.long("attr_id"),
        "value"         -> r.str("attr_value"),
        "price"         -> ApiJson.money(r.dec("attr_price"))
      ))

  /** SKU rows of one goods item. */
  def products(conn: Connection, goodsId: Long): List[JsObject] =
    db.rows(conn,
      """SELECT product_id, product_sn, product_number, product_attr
        |FROM products WHERE goods_id = ? ORDER BY product_id""".stripMargin, Seq(goodsId))
      .map(r => Json.obj(
        "product_id"   -> r.long("product_id"),
        "product_sn"   -> r.str("product_sn"),
        "stock"        -> r.long("product_number"),
        "product_attr" -> r.str("product_attr")
      ))

object GoodsRepo:
  /** Browseable goods; the single definition shared by every goods read. */
  val Visible: String = "g.is_on_sale = 1 AND g.is_delete = 0 AND g.is_alone_sale = 1"
