package ecs

import play.api.libs.json.{JsObject, Json}

import java.sql.Connection
import javax.inject.{Inject, Singleton}

/**
 * Shopping cart (PHP `flow.php?step=cart|add_to_cart|update_cart|drop_goods`).
 *
 * `cart` has `UNIQUE(user_id, goods_id, attr_signature)` and an `attr_signature`
 * that is empty for plain goods rows, so adding the same product twice is one
 * atomic upsert that accumulates the quantity instead of a read-then-write race.
 * Every statement filters on the bearer user id, and the version column is the
 * optimistic lock PATCH needs.
 */
@Singleton
class CartRepo @Inject() (db: Db):

  private val Select =
    """SELECT c.rec_id, c.goods_id, c.goods_number, c.version, c.attr_signature, g.goods_name,
      |  g.shop_price, g.promote_price, g.promote_start_date, g.promote_end_date, g.is_promote,
      |  g.goods_number AS stock
      |FROM cart c
      |JOIN goods g ON g.goods_id = c.goods_id
      |WHERE c.user_id = ?""".stripMargin

  private def project(row: Row): JsObject =
    Json.obj(
      "id"        -> row.long("rec_id"),
      "goods_id"  -> row.long("goods_id"),
      "name"      -> row.str("goods_name"),
      "price"     -> ApiJson.money(Views.goodsPrice(row, ApiJson.nowUnix)),
      "quantity"  -> row.long("goods_number"),
      "version"   -> row.long("version")
    )

  private def byId(conn: Connection, userId: Long, recId: Long): Option[Row] =
    db.row(conn, s"$Select AND c.rec_id = ?${Sql.forUpdate(db.dialect)}".stripMargin, Seq(userId, recId))

  /** A goods row that may still be bought, together with the stock it advertises. */
  private def purchasable(conn: Connection, goodsId: Long): Option[Row] =
    db.row(conn,
      s"""SELECT g.goods_id, g.goods_name, g.shop_price, g.promote_price, g.promote_start_date,
         |  g.promote_end_date, g.is_promote, g.goods_number AS stock
         |FROM goods g WHERE g.goods_id = ? AND ${GoodsRepo.Visible}""".stripMargin, Seq(goodsId))

  /** `GET /api/v1/me/cart` — the rows plus the total quantity. */
  def list(conn: Connection, userId: Long): JsObject =
    val rows = db.rows(conn, s"$Select ORDER BY c.rec_id".stripMargin, Seq(userId))
    Json.obj(
      "items"          -> Json.toJson(rows.map(project)),
      "total_quantity" -> rows.map(_.long("goods_number")).sum
    )

  /** The accumulated line after an add, which is what `POST` returns as 201. */
  private def lineOf(conn: Connection, userId: Long, goodsId: Long): Option[JsObject] =
    db.row(conn,
      s"$Select AND c.goods_id = ? AND c.attr_signature = ?".stripMargin, Seq(userId, goodsId, "")).map(project)

  /**
   * `POST /api/v1/me/cart` — quantity is added to whatever is already in the cart.
   *
   * The stock ceiling is checked before the upsert so a refusal never leaves a
   * partially applied line behind.
   */
  def add(conn: Connection, userId: Long, goodsId: Long, quantity: Long): Either[CartFailure, JsObject] =
    purchasable(conn, goodsId) match
      case None => Left(CartFailure.GoodsNotFound)
      case Some(goods) =>
        val already = db.row(conn,
          """SELECT c.goods_number FROM cart c WHERE c.user_id = ? AND c.goods_id = ? AND c.attr_signature = ?
            |  ORDER BY c.rec_id""".stripMargin, Seq(userId, goodsId, ""))
          .map(_.long("goods_number")).getOrElse(0L)
        val wanted = already + quantity
        if wanted > goods.long("stock") then Left(CartFailure.OutOfStock(goods.str("goods_name"), goods.long("stock")))
        else
          mergeQuantity(conn, userId, goodsId, quantity)
          lineOf(conn, userId, goodsId) match
            case Some(line) => Right(line)
            case None       => Left(CartFailure.NotFound)

  /**
   * Add `quantity` to the plain line of one goods row, creating it when missing.
   *
   * The unique key does the arithmetic atomically in both dialects, so two callers
   * adding at the same moment end up with one line that has both quantities. Order
   * cancellation and "return to cart" reuse this without the stock ceiling check.
   */
  def mergeQuantity(conn: Connection, userId: Long, goodsId: Long, quantity: Long): Unit =
    val columns = Seq("user_id", "goods_id", "attr_signature", "goods_number", "version", "created_at", "updated_at")
    val assignments = db.dialect match
      case Dialect.SQLite =>
        Seq("goods_number" -> "cart.goods_number + excluded.goods_number",
          "version" -> "cart.version + 1", "updated_at" -> "excluded.updated_at")
      case Dialect.MySQL =>
        Seq("goods_number" -> "goods_number + VALUES(goods_number)",
          "version" -> "version + 1", "updated_at" -> "VALUES(updated_at)")
    val sql = Sql.upsert(db.dialect, "cart", columns, columns.map(_ => "?"),
      Seq("user_id", "goods_id", "attr_signature"), assignments)
    db.execute(conn, sql, Seq(userId, goodsId, "", quantity, 1, ApiJson.nowUtc, ApiJson.nowUtc))

  /**
   * `PATCH /api/v1/me/cart/{id}` — the update carries the version it read, so a
   * concurrent write makes the WHERE clause match zero rows instead of silently
   * overwriting the other change.
   */
  def updateQuantity(conn: Connection, userId: Long, recId: Long, quantity: Long,
    version: Long): Either[CartFailure, JsObject] =
    byId(conn, userId, recId) match
      case None => Left(CartFailure.NotFound)
      case Some(row) if row.long("version") != version => Left(CartFailure.VersionMismatch)
      case Some(row) =>
        val stock = row.long("stock")
        if quantity > stock then Left(CartFailure.OutOfStock(row.str("goods_name"), stock))
        else if db.execute(conn,
          """UPDATE cart SET goods_number = ?, version = version + 1, updated_at = ?
            |WHERE rec_id = ? AND user_id = ? AND version = ?""".stripMargin,
          Seq(quantity, ApiJson.nowUtc, recId, userId, version)) != 1 then Left(CartFailure.VersionMismatch)
        else byId(conn, userId, recId).map(project).toRight(CartFailure.NotFound)

  /** `DELETE /api/v1/me/cart/{id}` — id and owner both have to match. */
  def delete(conn: Connection, userId: Long, recId: Long): Boolean =
    db.execute(conn, "DELETE FROM cart WHERE rec_id = ? AND user_id = ?", Seq(recId, userId)) == 1

  /**
   * The sellable lines with integer-cent prices.
   *
   * Quote and order placement both take their money from this single read, so the
   * two can never disagree about what the cart is worth (docs/03_api_contract.md).
   */
  def pricedLines(conn: Connection, userId: Long): List[PricedLine] =
    db.rows(conn,
      s"$Select AND ${GoodsRepo.Visible} ORDER BY c.rec_id".stripMargin, Seq(userId))
      .map { row =>
        PricedLine(project(row), row.long("goods_id"), row.str("goods_name"),
          CartRepo.toCents(Views.goodsPrice(row, ApiJson.nowUnix)), row.long("goods_number"), row.long("stock"))
      }

object CartRepo:
  /** Decimal money to integer cents, rounded the same way everywhere. */
  def toCents(value: BigDecimal): Long =
    value.setScale(2, scala.math.BigDecimal.RoundingMode.HALF_UP).bigDecimal.movePointRight(2).longValueExact

/** Why a cart write was refused; each case maps to one HTTP status. */
enum CartFailure:
  case GoodsNotFound, NotFound, VersionMismatch
  case OutOfStock(name: String, stock: Long)

/** One cart line priced in cents, ready for quoting or ordering. */
final case class PricedLine(item: play.api.libs.json.JsObject, goodsId: Long, name: String,
  priceCents: Long, quantity: Long, stock: Long):
  def subtotalCents: Long = priceCents * quantity
