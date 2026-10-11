package ecs

import play.api.libs.json.{JsObject, Json}

import java.sql.Connection
import javax.inject.{Inject, Singleton}

/**
 * The public goods widget (PHP `goods_script.php`).
 *
 * A widget embedder may only ever see goods the storefront already shows, so
 * the same visible predicate and category-subtree rules apply here.
 */
@Singleton
class WidgetRepo @Inject() (db: Db, goodsRepo: GoodsRepo):

  /** The five legacy selection modes; anything else is a client error. */
  val IntroTypes: Set[String] = Set("is_best", "is_new", "is_hot", "is_promote", "is_random")

  def accepts(introType: String): Boolean = IntroTypes.contains(introType)

  /** `GET /goods-widget.js` payload: at most 50 items of one selection mode. */
  def goods(conn: Connection, categoryId: Option[Long], brandId: Option[Long], limit: Int, introType: String): JsObject =
    val now = ApiJson.nowUnix
    val conditions = scala.collection.mutable.ListBuffer.empty[String]
    val params = scala.collection.mutable.ListBuffer.empty[Any]

    conditions += GoodsRepo.Visible

    categoryId.foreach { cid =>
      val ids = goodsRepo.categorySubtreeIds(conn, cid)
      val marks = Sql.marks(ids.size)
      conditions +=
        s"""(g.cat_id IN ($marks)
           |  OR EXISTS (SELECT 1 FROM goods_cat gc WHERE gc.goods_id = g.goods_id AND gc.cat_id IN ($marks)))""".stripMargin
      params ++= ids
      params ++= ids
    }

    brandId.foreach { bid => conditions += "g.brand_id = ?"; params += bid }

    introType match
      case "is_random"  => ()
      case "is_promote" =>
        conditions += "g.is_promote = 1 AND g.promote_start_date <= ? AND g.promote_end_date >= ?"
        params ++= Seq(now, now)
      case flag =>
        conditions += s"g.$flag = 1"

    val orderBy = if introType == "is_random" then Sql.random(db.dialect) else "g.goods_id DESC"
    val rows = db.rows(conn,
      s"""SELECT ${Views.goodsColumns("g")}
         |FROM goods g
         |WHERE ${conditions.mkString(" AND ")}
         |ORDER BY $orderBy
         |LIMIT $limit""".stripMargin, params.toList)

    Json.obj(
      "intro_type" -> introType,
      "goods_num"  -> limit,
      "items"      -> Json.toJson(rows.map(row => Json.obj(
        "id"        -> row.long("goods_id"),
        "goods_sn"  -> row.str("goods_sn"),
        "name"      -> row.str("goods_name"),
        "price"     -> ApiJson.money(Views.goodsPrice(row, now)),
        "thumb"     -> row.str("goods_thumb")
      )))
    )
