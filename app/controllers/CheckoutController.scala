package controllers

import ecs.{ApiError, AuthedAction, Body, CheckoutFailure, CheckoutRepo, Db, ErrorCode}
import javax.inject.Inject
import play.api.libs.json.JsValue
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/**
 * Checkout helpers — maps PHP flow.php?step=checkout with the shipping and payment
 * selection steps folded into one quote.
 */
class CheckoutController @Inject() (cc: ControllerComponents, db: Db, checkout: CheckoutRepo, authed: AuthedAction)
  extends AbstractController(cc) with ApiSupport:

  /** `GET /api/v1/checkout/options` */
  def options: Action[AnyContent] = authed { req =>
    Ok(db.readOnly(conn => checkout.options(conn)))
  }

  /** `POST /api/v1/checkout/quote` — recomputed from database prices. */
  def quote: Action[JsValue] = authed(parse.json) { req =>
    val body = Body(req.body)
    val parsed =
      for
        addressId  <- body.long("address_id", min = 1)
        shippingId <- body.long("shipping_id", min = 1)
        paymentId  <- body.long("payment_id", min = 1)
        _          <- body.rejectUnknown(Set("address_id", "shipping_id", "payment_id")).toLeft(())
      yield (addressId, shippingId, paymentId)

    orFail(parsed, req) { case (addressId, shippingId, paymentId) =>
      val quote = db.readOnly(conn => checkout.quote(conn, req.user.id, addressId, shippingId, paymentId))
      quote match
        case Right(payload) => Ok(payload)
        case Left(CheckoutFailure.AddressNotFound) =>
          ApiError.notFound("address not found for this account", req)
        case Left(CheckoutFailure.ShippingUnavailable) => ApiError.notFound("shipping method is not available", req)
        case Left(CheckoutFailure.PaymentUnavailable)  => ApiError.notFound("payment method is not available", req)
        case Left(CheckoutFailure.EmptyCart) =>
          ApiError.conflict(ErrorCode.InvalidState, "the cart has no sellable goods", req)
    }
  }
