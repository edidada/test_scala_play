package ecs

import play.api.libs.json.{JsObject, Json}

/**
 * Row-to-JSON projectors shared by every endpoint that returns the same entity.
 *
 * Keeping them here means `/home`, `/goods`, `/brands/{id}/goods`, `/quotation`
 * and the cart/checkout endpoints all emit identical field names and identical
 * two-decimal money strings (docs/03_api_contract.md).
 */
object Views:

  /** A promotion only prices the goods while its unix window is open. */
  private def promotionActive(row: Row, now: Long): Boolean =
    row.bool("is_promote") &&
      row.long("promote_start_date") <= now &&
      row.long("promote_end_date") >= now &&
      row.dec("promote_price") > 0

  /** Price the shopper actually sees: promoted price when live, else shop price. */
  def goodsPrice(row: Row, now: Long): BigDecimal =
    if promotionActive(row, now) then row.dec("promote_price") else row.dec("shop_price")

  /** Normalise a SQL DATETIME to `yyyy-MM-dd HH:mm:ss` for both dialects. */
  def timestamp(row: Row, column: String): String =
    val raw = row.str(column).replace('T', ' ')
    if raw.length >= 19 then raw.take(19) else raw

  /** Columns every goods listing must SELECT; `alias` is the goods table alias. */
  private val SummaryColumns: List[String] = List(
    "goods_id", "goods_sn", "goods_name", "goods_brief", "cat_id", "brand_id", "goods_thumb",
    "shop_price", "market_price", "promote_price", "promote_start_date", "promote_end_date",
    "goods_number", "integral", "is_on_sale", "is_alone_sale", "is_best", "is_new", "is_hot",
    "is_promote", "add_time"
  )

  def goodsColumns(alias: String): String = SummaryColumns.map(c => s"$alias.$c").mkString(", ")

  def goodsSummary(row: Row, now: Long = ApiJson.nowUnix): JsObject =
    Json.obj(
      "id"            -> row.long("goods_id"),
      "goods_sn"      -> row.str("goods_sn"),
      "name"          -> row.str("goods_name"),
      "brief"         -> row.str("goods_brief"),
      "category_id"   -> row.long("cat_id"),
      "brand_id"      -> row.long("brand_id"),
      "price"         -> ApiJson.money(goodsPrice(row, now)),
      "market_price"  -> ApiJson.money(row.dec("market_price")),
      "thumb"         -> row.str("goods_thumb"),
      "goods_number"  -> row.long("goods_number"),
      "integral"      -> row.long("integral"),
      "is_on_sale"    -> row.bool("is_on_sale"),
      "is_best"       -> row.bool("is_best"),
      "is_new"        -> row.bool("is_new"),
      "is_hot"        -> row.bool("is_hot"),
      "is_promote"    -> promotionActive(row, now),
      "add_time"      -> timestamp(row, "add_time")
    )

  /** Category row with the storefront-visible goods count. */
  def category(row: Row): JsObject =
    Json.obj(
      "id"          -> row.long("cat_id"),
      "parent_id"   -> row.long("parent_id"),
      "name"        -> row.str("cat_name"),
      "sort_order"  -> row.long("sort_order"),
      "keywords"    -> row.str("keywords"),
      "goods_count" -> row.long("goods_count")
    )

  def brand(row: Row): JsObject =
    Json.obj(
      "id"          -> row.long("brand_id"),
      "name"        -> row.str("brand_name"),
      "logo"        -> row.str("brand_logo"),
      "site_url"    -> row.str("site_url"),
      "sort_order"  -> row.long("sort_order"),
      "goods_count" -> row.long("goods_count")
    )

  def article(row: Row): JsObject =
    Json.obj(
      "id"         -> row.long("article_id"),
      "category_id" -> row.long("cat_id"),
      "category_name" -> row.str("cat_name"),
      "title"      -> row.str("title"),
      "author"     -> row.str("author"),
      "brief"      -> row.str("article_desc"),
      "keywords"   -> row.str("keywords")
    )

  def region(row: Row): JsObject =
    Json.obj(
      "id"    -> row.long("region_id"),
      "name"  -> row.str("region_name"),
      "type"  -> row.long("region_type")
    )
