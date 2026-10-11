package ecs

import play.api.libs.json.{JsNull, JsObject, Json}

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
    val (clause, params) = filterClause(conn, categoryId, brandId, query)
    list(conn, clause, params, page, sort)

  /** The search.php filters as a SQL fragment plus its bound parameters. */
  private def filterClause(
    conn: Connection,
    categoryId: Option[Long],
    brandId: Option[Long],
    query: Option[String]
  ): (String, List[Any]) =
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

    (if conditions.isEmpty then "1 = 1" else conditions.mkString(" AND "), params.toList)

  /**
   * Quotation rows (PHP `quotation.php?act=print_quotation`): one line per
   * goods/SKU pair, or one per goods when it has no SKU rows.
   *
   * The line carries the numbers a buyer needs to ask for a real price; the
   * attribute surcharge is deliberately left to `/goods/{id}/price-quote`, so
   * no client ever adds prices together itself.
   */
  def quotation(
    conn: Connection,
    categoryId: Option[Long],
    brandId: Option[Long],
    query: Option[String],
    page: Paging.Page
  ): JsObject =
    val (clause, params) = filterClause(conn, categoryId, brandId, query)
    val total = db.count(conn, s"SELECT COUNT(*) FROM goods g WHERE ${GoodsRepo.Visible} AND $clause", params)
    val rows = db.rows(conn,
      s"""SELECT ${Views.goodsColumns("g")}
         |FROM goods g
         |WHERE ${GoodsRepo.Visible} AND $clause
         |ORDER BY g.goods_id ASC
         |LIMIT ? OFFSET ?""".stripMargin,
      params ++ Seq(page.pageSize, page.offset))
    val ids = rows.map(_.long("goods_id"))
    val now = ApiJson.nowUnix

    val skus: Map[Long, List[Row]] =
      if ids.isEmpty then Map.empty
      else db.rows(conn,
        s"""SELECT product_id, goods_id, product_sn, product_number
           |FROM products WHERE goods_id IN (${Sql.marks(ids.size)})
           |ORDER BY goods_id, product_id""".stripMargin, ids)
        .groupBy(_.long("goods_id"))

    val attributeIds: Map[Long, List[Long]] =
      if ids.isEmpty then Map.empty
      else db.rows(conn,
        s"""SELECT goods_id, goods_attr_id
           |FROM goods_attr WHERE goods_id IN (${Sql.marks(ids.size)})
           |ORDER BY goods_id, goods_attr_id""".stripMargin, ids)
        .groupBy(_.long("goods_id"))
        .map { case (goodsId, rs) => goodsId -> rs.map(_.long("goods_attr_id")) }

    val items = rows.flatMap { row =>
      val goodsId = row.long("goods_id")
      val base = Json.obj(
        "goods_id"      -> goodsId,
        "goods_sn"      -> row.str("goods_sn"),
        "goods_name"    -> row.str("goods_name"),
        "category_id"   -> row.long("cat_id"),
        "base_price"    -> ApiJson.money(Views.goodsPrice(row, now)),
        "attribute_ids" -> Json.toJson(attributeIds.getOrElse(goodsId, Nil))
      )
      skus.get(goodsId) match
        case Some(list) if list.nonEmpty =>
          list.map(p => base ++ Json.obj(
            "product_id"   -> p.long("product_id"),
            "product_sn"   -> p.str("product_sn"),
            "stock"        -> math.min(p.long("product_number"), row.long("goods_number"))
          ))
        case _ => List(base ++ Json.obj("product_id" -> JsNull, "product_sn" -> "", "stock" -> row.long("goods_number")))
    }

    ApiJson.page(page.page, page.pageSize, total, items)

  /**
   * Display-only price quote (PHP `goods.php?act=price`).
   *
   * The unit price is the storefront price plus the price of every selected
   * attribute row; stock is the goods stock clipped by the SKU stock when a
   * product is chosen. Order creation always recomputes from the database.
   */
  def priceQuote(conn: Connection, goodsId: Long, quantity: Long, productId: Option[Long],
    attributeIds: List[Long]): Either[QuoteFailure, JsObject] =
    val sql =
      s"""SELECT ${Views.goodsColumns("g")}
         |FROM goods g
         |WHERE g.goods_id = ? AND g.is_delete = 0 AND g.is_on_sale = 1 AND g.is_alone_sale = 1""".stripMargin
    db.row(conn, sql, Seq(goodsId)) match
      case None => Left(QuoteFailure.GoodsNotFound)
      case Some(row) =>
        val now = ApiJson.nowUnix
        if attributeIds.isEmpty then Right(quote(row, quantity, Nil, None, now))
        else
          val marks = Sql.marks(attributeIds.size)
          val owned = db.count(conn,
            s"SELECT COUNT(*) FROM goods_attr WHERE goods_id = ? AND goods_attr_id IN ($marks)",
            goodsId +: attributeIds)
          if owned != attributeIds.size.toLong then Left(QuoteFailure.AttributeNotOwned)
          else
            val chosen = db.rows(conn,
              s"SELECT goods_attr_id, attr_price FROM goods_attr WHERE goods_id = ? AND goods_attr_id IN ($marks)",
              goodsId +: attributeIds)
            productId match
              case None => Right(quote(row, quantity, chosen, None, now))
              case Some(pid) =>
                db.row(conn, "SELECT product_number FROM products WHERE product_id = ? AND goods_id = ?", Seq(pid, goodsId)) match
                  case None => Left(QuoteFailure.ProductNotOwned)
                  case Some(product) => Right(quote(row, quantity, chosen, Some(product.long("product_number")), now))

  private def quote(row: Row, quantity: Long, chosen: List[Row], productStock: Option[Long], now: Long): JsObject =
    val attributeDelta = chosen.foldLeft(BigDecimal(0))((acc, r) => acc + r.dec("attr_price"))
    val unit = (Views.goodsPrice(row, now) + attributeDelta).setScale(2, BigDecimal.RoundingMode.HALF_UP)
    val stock = productStock.fold(row.long("goods_number"))(s => math.min(s, row.long("goods_number")))
    Json.obj(
      "goods_id"         -> row.long("goods_id"),
      "quantity"         -> quantity,
      "unit_price"       -> ApiJson.money(unit),
      "total"            -> ApiJson.money(unit * BigDecimal(quantity)),
      "currency"         -> "CNY",
      "stock_available"  -> stock
    )

  /**
   * Payload of `GET /api/v1/goods/{id}/gallery` (PHP `gallery.php?id=`).
   *
   * None covers both an unknown or deleted goods and a goods with no uploaded
   * images, so the client falls back to the detail page either way.
   */
  def gallery(conn: Connection, goodsId: Long): Option[JsObject] =
    db.row(conn,
      """SELECT goods_id, goods_name, goods_thumb, goods_img, original_img, shop_price
        |FROM goods WHERE goods_id = ? AND is_delete = 0""".stripMargin, Seq(goodsId))
      .flatMap { goods =>
        val images = db.rows(conn,
          """SELECT img_id, img_url, thumb_url, img_original, img_desc, sort_order
            |FROM goods_gallery WHERE goods_id = ? ORDER BY img_id""".stripMargin, Seq(goodsId))
        if images.isEmpty then None
        else
          Some(Json.obj(
            "goods_id"     -> goods.long("goods_id"),
            "goods_name"   -> goods.str("goods_name"),
            "price"        -> ApiJson.money(goods.dec("shop_price")),
            "items"        -> Json.toJson(images.map(r => Json.obj(
              "img_id"       -> r.long("img_id"),
              "img_url"      -> r.str("img_url"),
              "thumb_url"    -> r.str("thumb_url"),
              "img_original" -> r.str("img_original"),
              "desc"         -> r.str("img_desc"),
              "sort_order"   -> r.long("sort_order")
            )))
          ))
      }

  /**
   * Rows for `GET /api/v1/compare` (PHP `compare.php?goods[]=`).
   *
   * Returns None when any requested id is invisible, because a comparison page
   * must not reveal that one of the ids exists but is off-sale or deleted.
   * Otherwise the summaries keep the caller's input order.
   */
  def compare(conn: Connection, goodsIds: List[Long]): Option[List[JsObject]] =
    val marks = Sql.marks(goodsIds.size)
    val sql =
      s"""SELECT ${Views.goodsColumns("g")}
         |FROM goods g
         |WHERE ${GoodsRepo.Visible} AND g.goods_id IN ($marks)""".stripMargin
    val now = ApiJson.nowUnix
    val byId = db.rows(conn, sql, goodsIds).map(r => r.long("goods_id") -> r).toMap
    if byId.size != goodsIds.distinct.size then None
    else Some(goodsIds.distinct.map(id => Views.goodsSummary(byId(id), now)))

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

/** Why a display price quote could not be produced. */
enum QuoteFailure:
  case GoodsNotFound, AttributeNotOwned, ProductNotOwned

object GoodsRepo:
  /** Browseable goods; the single definition shared by every goods read. */
  val Visible: String = "g.is_on_sale = 1 AND g.is_delete = 0 AND g.is_alone_sale = 1"
