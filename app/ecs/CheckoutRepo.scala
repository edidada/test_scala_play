package ecs

import play.api.libs.json.{JsObject, Json}

import java.sql.Connection
import javax.inject.{Inject, Singleton}

/**
 * Checkout reads and quotes (PHP `flow.php?step=checkout|select_shipping|select_payment`).
 *
 * The quote is a *server-side* recomputation: prices, shipping and payment fees all
 * come out of the database in integer cents, and nothing the client measured is
 * trusted. Order placement runs the same calculation again.
 */
@Singleton
class CheckoutRepo @Inject() (db: Db, regions: RegionRepo, cart: CartRepo):

  /** Enabled payment methods, the same rows `myship`-style pickers expect. */
  def paymentOptions(conn: Connection): List[JsObject] =
    db.rows(conn,
      """SELECT pay_id, pay_name, pay_code, pay_fee
        |FROM payment WHERE enabled = 1
        |ORDER BY pay_id""".stripMargin)
      .map(r => Json.obj(
        "id"   -> r.long("pay_id"),
        "name" -> r.str("pay_name"),
        "code" -> r.str("pay_code"),
        "fee"  -> ApiJson.money(r.dec("pay_fee"))
      ))

  /** `GET /api/v1/checkout/options` — carriers and payment methods on offer. */
  def options(conn: Connection): JsObject =
    Json.obj(
      "shipping_options" -> Json.toJson(regions.shippingOptions(conn)),
      "payment_options"  -> Json.toJson(paymentOptions(conn))
    )

  private def enabledShipping(conn: Connection, shippingId: Long): Option[Row] =
    db.row(conn,
      "SELECT shipping_id, shipping_name, shipping_fee FROM shipping WHERE shipping_id = ? AND enabled = 1",
      Seq(shippingId))

  private def enabledPayment(conn: Connection, paymentId: Long): Option[Row] =
    db.row(conn, "SELECT pay_id, pay_name, pay_fee FROM payment WHERE pay_id = ? AND enabled = 1", Seq(paymentId))

  /**
   * `POST /api/v1/checkout/quote`.
   *
   * The address has to belong to the bearer user, both methods have to be enabled,
   * and the cart must contain at least one sellable line.
   */
  def quote(conn: Connection, userId: Long, addressId: Long, shippingId: Long,
    paymentId: Long): Either[CheckoutFailure, JsObject] =

    db.row(conn,
      "SELECT address_id, consignee FROM user_address WHERE address_id = ? AND user_id = ?",
      Seq(addressId, userId)) match
      case None => Left(CheckoutFailure.AddressNotFound)
      case Some(address) =>
        enabledShipping(conn, shippingId) match
          case None => Left(CheckoutFailure.ShippingUnavailable)
          case Some(shipping) =>
            enabledPayment(conn, paymentId) match
              case None => Left(CheckoutFailure.PaymentUnavailable)
              case Some(payment) =>
                val lines = cart.pricedLines(conn, userId)
                if lines.isEmpty then Left(CheckoutFailure.EmptyCart)
                else
                  val goodsCents    = lines.map(_.subtotalCents).sum
                  val shippingCents = CartRepo.toCents(shipping.dec("shipping_fee"))
                  val paymentCents  = CartRepo.toCents(payment.dec("pay_fee"))
                  Right(Json.obj(
                    "address_id"    -> address.long("address_id"),
                    "shipping_id"   -> shippingId,
                    "payment_id"    -> paymentId,
                    "items"         -> Json.toJson(lines.map(_.item)),
                    "goods_amount"  -> ApiJson.money(goodsCents),
                    "shipping_fee"  -> ApiJson.money(shippingCents),
                    "payment_fee"   -> ApiJson.money(paymentCents),
                    "order_amount"  -> ApiJson.money(goodsCents + shippingCents + paymentCents)
                  ))

/** Why a quote was refused; each case maps to one HTTP status. */
enum CheckoutFailure:
  case AddressNotFound, ShippingUnavailable, PaymentUnavailable, EmptyCart
