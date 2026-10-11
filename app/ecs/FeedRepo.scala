package ecs

import play.api.Configuration

import java.time.{Instant, ZoneOffset, format}
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
class FeedRepo @Inject() (db: Db, config: Configuration, goodsRepo: GoodsRepo):

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

  /** One RSS entry. */
  private final case class Item(title: String, link: String, description: String, timeSeconds: Long)

  private val Rfc822: format.DateTimeFormatter = format.DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)

  /** Legacy `act_type` of the goods_activity based feeds. */
  private val ActivityTypes: Map[String, Int] = Map("snatch" -> 0, "group_buy" -> 1, "auction" -> 2, "package" -> 4)

  /** RSS items are capped at 100 by contract, whatever the source table. */
  private val MaxItems = 100

  /** `GET /feed.xml` (PHP `feed.php`). */
  def feed(conn: Connection, cat: Option[Long], brand: Option[Long], kind: Option[String]): Either[FieldError, String] =
    items(conn, cat, brand, kind).map(render)

  private def items(conn: Connection, cat: Option[Long], brand: Option[Long], kind: Option[String]): Either[FieldError, List[Item]] =
    kind match
      case None => Right(goodsItems(conn, cat, brand))
      case Some(k) if ActivityTypes.contains(k) => Right(activityItems(conn, ActivityTypes(k)))
      case Some("exchange") => Right(exchangeItems(conn))
      case Some("activity") => Right(favourableItems(conn))
      case Some(k) if k.startsWith("article_cat") =>
        k.stripPrefix("article_cat").toLongOption match
          case Some(id) if id > 0 => Right(articleItems(conn, id))
          case _ => Left(FieldError("type", "type must be article_cat followed by a positive id"))
      case Some(_) => Left(FieldError("type", "type must be one of group_buy, snatch, auction, exchange, activity, package or article_cat{id}"))

  private def goodsItems(conn: Connection, cat: Option[Long], brand: Option[Long]): List[Item] =
    val conditions = scala.collection.mutable.ListBuffer.empty[String]
    val params = scala.collection.mutable.ListBuffer.empty[Any]
    conditions += GoodsRepo.VisiblePlain
    cat.foreach { cid =>
      val ids = goodsRepo.categorySubtreeIds(conn, cid)
      conditions += s"cat_id IN (${Sql.marks(ids.size)})"
      params ++= ids
    }
    brand.foreach { bid => conditions += "brand_id = ?"; params += bid }
    db.rows(conn,
      s"""SELECT goods_id, goods_name, goods_brief, shop_price, add_time
         |FROM goods
         |WHERE ${conditions.mkString(" AND ")}
         |ORDER BY goods_id DESC
         |LIMIT $MaxItems""".stripMargin, params.toList)
      .map(r => Item(
        r.str("goods_name"),
        s"$baseUrl/api/v1/goods/${r.long("goods_id")}",
        s"${r.str("goods_brief")} (${ApiJson.money(r.dec("shop_price"))})",
        ApiJson.unixOf(r.str("add_time"))))

  private def activityItems(conn: Connection, actType: Int): List[Item] =
    val now = ApiJson.nowUnix
    db.rows(conn,
      """SELECT act_id, act_name, act_desc, start_time, end_time
        |FROM goods_activity
        |WHERE act_type = ? AND is_finished = 0 AND start_time <= ? AND end_time >= ?
        |ORDER BY end_time ASC
        |LIMIT 100""".stripMargin, Seq(actType, now, now))
      .map(r => Item(r.str("act_name"), s"$baseUrl/api/v1/promotions/${r.long("act_id")}", r.str("act_desc"), r.long("end_time")))

  private def exchangeItems(conn: Connection): List[Item] =
    db.rows(conn,
      """SELECT g.goods_id, g.goods_name, g.goods_brief, x.exchange_integral
        |FROM exchange_goods x JOIN goods g ON g.goods_id = x.goods_id
        |WHERE x.is_exchange = 1 AND g.is_on_sale = 1 AND g.is_delete = 0
        |ORDER BY x.exchange_integral ASC
        |LIMIT 100""".stripMargin)
      .map(r => Item(
        r.str("goods_name"),
        s"$baseUrl/api/v1/exchange-goods/${r.long("goods_id")}",
        s"${r.str("goods_brief")} (${r.long("exchange_integral")} points)",
        0L))

  private def favourableItems(conn: Connection): List[Item] =
    val now = ApiJson.nowUnix
    db.rows(conn,
      """SELECT act_id, act_name, start_time, end_time
        |FROM favourable_activity
        |WHERE start_time <= ? AND end_time >= ?
        |ORDER BY sort_order ASC, act_id ASC
        |LIMIT 100""".stripMargin, Seq(now, now))
      .map(r => Item(r.str("act_name"), s"$baseUrl/api/v1/activities", "", r.long("end_time")))

  private def articleItems(conn: Connection, catId: Long): List[Item] =
    db.rows(conn,
      """SELECT a.article_id, a.title, a.article_desc
        |FROM article a
        |WHERE a.cat_id = ? AND a.is_open = 1
        |ORDER BY a.article_id DESC
        |LIMIT 100""".stripMargin, Seq(catId))
      .map(r => Item(r.str("title"), s"$baseUrl/api/v1/articles/${r.long("article_id")}", r.str("article_desc"), 0L))

  private def render(items: List[Item]): String =
    val body = items.map { item =>
      val pubDate = if item.timeSeconds > 0 then s"<pubDate>${escape(Rfc822.format(Instant.ofEpochSecond(item.timeSeconds)))}</pubDate>" else ""
      s"""    <item>
         |      <title>${escape(item.title)}</title>
         |      <link>${escape(item.link)}</link>
         |      <guid isPermaLink="false">${escape(item.link)}</guid>
         |      <description>${escape(item.description)}</description>
         |      $pubDate
         |    </item>""".stripMargin
    }.mkString("\n")

    s"""<?xml version="1.0" encoding="UTF-8"?>
       |<rss version="2.0">
       |  <channel>
       |    <title>ECShop</title>
       |    <link>${escape(baseUrl)}</link>
       |    <description>Latest ECShop catalogue entries</description>
       |$body
       |  </channel>
       |</rss>
       |""".stripMargin
