package controllers

import ecs.{Abort, ApiError, Body, CallbackFailure, Crypto, Db, ErrorCode, FieldError, PaymentCallbackRepo}
import javax.inject.Inject
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{AbstractController, Action, ControllerComponents, RawBuffer, Request, RequestHeader, Result}

/**
 * Payment gateway callbacks — maps PHP `respond.php`.
 *
 * There is no bearer token here, so the only authentication is the HMAC-SHA256 signature
 * of the *raw* request body in `X-Payment-Signature`. The body is therefore consumed as
 * bytes and only parsed after the signature has been checked against those exact bytes;
 * re-serialising the JSON first would make the signature depend on key order.
 */
class PaymentCallbackController @Inject() (cc: ControllerComponents, db: Db, config: play.api.Configuration,
  callbacks: PaymentCallbackRepo)
  extends AbstractController(cc) with ApiSupport:

  /** Gateways send small JSON documents; anything larger is refused before hashing. */
  private val MaxBodyBytes = 64L * 1024L

  private val ProviderPattern = "[A-Za-z0-9_-]{1,60}".r

  /** `POST /api/v1/payments/{provider}/callback` */
  def callback(provider: String): Action[RawBuffer] = Action(parse.raw) { req =>
    if !ProviderPattern.matches(provider) then
      ApiError.badRequest("provider must be a short adapter code", req, Some("provider"))
    else
      req.body.asBytes(MaxBodyBytes) match
        case None => ApiError.badRequest("the callback body is too large", req, Some("body"))
        case Some(bytes) =>
          val payload = bytes.utf8String
          signatureOf(req) match
            case None => ApiError.unauthorized("the X-Payment-Signature header is required", req)
            case Some(signature) =>
              // Constant-time comparison against the hex HMAC of the exact bytes received.
              val expected = Crypto.hmacSha256Hex(callbackSecret, payload)
              if !Crypto.constantTimeEquals(signature.toLowerCase, expected) then
                ApiError.unauthorized("payment signature is not valid", req)
              else settle(req, provider, payload)
  }

  private def callbackSecret: String = config.get[String]("payment.callback_hmac_secret")

  private def signatureOf(req: RequestHeader): Option[String] =
    req.headers.get("X-Payment-Signature").map(_.trim).filter(_.nonEmpty)

  private def settle(req: Request[RawBuffer], provider: String, payload: String): Result =
    val json: Either[FieldError, JsValue] =
      try Right(Json.parse(payload)) catch { case _: Exception => Left(FieldError("body", "a JSON payload is required")) }
    json match
      case Left(fe) => fail(fe, req)
      case Right(value) =>
        val body = Body(value)
        val parsed =
          for
            tradeNo     <- body.str("provider_trade_no", 120)
            orderSn     <- body.str("order_sn", 32)
            amountCents <- body.cents("amount")
          yield (tradeNo, orderSn, amountCents)

        orFail(parsed, req) { case (tradeNo, orderSn, amountCents) =>
          // Never log the payload: it carries the trade number and the exact amount.
          val outcome = Abort.guarded(db.inTransaction(conn =>
            callbacks.settle(conn, provider, tradeNo, orderSn, amountCents, payload)))
          outcome match
            case Right(document) => Ok(document)
            case Left(failure)   => failureResult(failure, req)
        }

  private def failureResult(failure: CallbackFailure, req: RequestHeader): Result = failure match
    case CallbackFailure.OrderNotFound    => ApiError.notFound("order not found", req)
    case CallbackFailure.AmountMismatch   =>
      ApiError.conflict(ErrorCode.Conflict, "the paid amount does not match the order total", req)
    case CallbackFailure.InvalidState     =>
      ApiError.conflict(ErrorCode.InvalidState, "the order is not waiting for payment", req)
    case CallbackFailure.TradeConflict    =>
      ApiError.conflict(ErrorCode.Conflict, "this trade number has already been used", req)
