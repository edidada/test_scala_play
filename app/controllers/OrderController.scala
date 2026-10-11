package controllers

import ecs.{Abort, AffiliateRepo, ApiError, AuthedAction, Body, Crypto, Db, EmailAddress, ErrorCode, FieldError, GroupBuyRepo, OrderAddress, OrderFailure, OrderRepo, Paging, PointsFailure, PointsRepo}
import javax.inject.Inject
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents, RequestHeader, Result}

/**
 * Orders and the customer's own fulfilment history — maps `flow.php?step=done` plus the
 * order, group-buy, affiliate, shipment, points and history actions of `user.php`.
 *
 * Multi-statement writes go through `Abort.guarded(db.inTransaction(...))`: a business
 * refusal raised inside the transaction rolls the database back and still comes out of
 * the controller as an ordinary `Left`, so no half-written order can be committed.
 */
class OrderController @Inject() (cc: ControllerComponents, db: Db, orders: OrderRepo, groupBuys: GroupBuyRepo,
  affiliates: AffiliateRepo, points: PointsRepo, authed: AuthedAction)
  extends AbstractController(cc) with ApiSupport:

  /** `POST /api/v1/orders` — the Idempotency-Key header is mandatory. */
  def create: Action[JsValue] = authed(parse.json) { req =>
    val body = Body(req.body)
    val key = req.headers.get("Idempotency-Key").map(_.trim) match
      case Some(k) if k.nonEmpty && k.length <= 100 => Right(k)
      case Some(_) => Left(FieldError("Idempotency-Key", "the header must be at most 100 characters"))
      case None    => Left(FieldError("Idempotency-Key", "the Idempotency-Key header is required"))

    val parsed =
      for
        idem       <- key
        addressId  <- body.long("address_id", min = 1)
        shippingId <- body.long("shipping_id", min = 1)
        paymentId  <- body.long("payment_id", min = 1)
        remark     <- body.strOpt("remark", 255)
        _          <- body.rejectUnknown(Set("address_id", "shipping_id", "payment_id", "remark")).toLeft(())
      yield (idem, addressId, shippingId, paymentId, remark.getOrElse(""))

    orFail(parsed, req) { case (idem, addressId, shippingId, paymentId, remark) =>
      // The fingerprint is what makes a same-key/different-body replay detectable.
      val fingerprint = Crypto.fingerprint(
        "address_id"  -> addressId.toString,
        "shipping_id" -> shippingId.toString,
        "payment_id"  -> paymentId.toString,
        "remark"      -> remark)
      val outcome = Abort.guarded(
        db.inTransaction(conn => orders.create(conn, req.user.id, idem, fingerprint, addressId, shippingId, paymentId, remark)))
      outcome match
        case Right((payload, replayed)) =>
          val document = payload ++ Json.obj("replayed" -> replayed)
          if replayed then Ok(document) else Created(document)
        case Left(failure) => failureResult(failure, req)
    }
  }

  /** `GET /api/v1/me/orders` */
  def index(page: Option[Int], page_size: Option[Int]): Action[AnyContent] = authed { req =>
    orFail(Paging.of(page, page_size), req) { paging =>
      Ok(db.readOnly(conn => orders.list(conn, req.user.id, paging)))
    }
  }

  /** `POST /api/v1/me/orders/merge` — returns the newly merged order. */
  def merge: Action[JsValue] = authed(parse.json) { req =>
    val body = Body(req.body)
    val parsed =
      for
        fromId <- body.long("from_order_id", min = 1)
        toId   <- body.long("to_order_id", min = 1)
        _      <- body.rejectUnknown(Set("from_order_id", "to_order_id")).toLeft(())
      yield (fromId, toId)

    orFail(parsed, req) { case (fromId, toId) =>
      val outcome = Abort.guarded(db.inTransaction(conn => orders.merge(conn, req.user.id, fromId, toId)))
      createdOrFailed(outcome, req)
    }
  }

  /** `GET /api/v1/me/orders/{id}` */
  def show(id: Long): Action[AnyContent] = authed { req =>
    orFailResult(db.readOnly(conn => orders.detail(conn, req.user.id, id)), req)
  }

  /** `GET /api/v1/me/orders/by-number/{order_sn}/status` */
  def statusByNumber(orderSn: String): Action[AnyContent] = authed { req =>
    orFailResult(db.readOnly(conn => orders.statusByNumber(conn, req.user.id, orderSn)), req)
  }

  /** `POST /api/v1/me/orders/{id}/cancel` — restock and refund happen together. */
  def cancel(id: Long): Action[AnyContent] = authed { req =>
    updatedOrFailed(Abort.guarded(db.inTransaction(conn => orders.cancel(conn, req.user.id, id))), req)
  }

  /** `POST /api/v1/me/orders/{id}/received` */
  def confirmReceived(id: Long): Action[AnyContent] = authed { req =>
    updatedOrFailed(Abort.guarded(db.inTransaction(conn => orders.confirmReceived(conn, req.user.id, id))), req)
  }

  /** `POST /api/v1/me/orders/{id}/cart` — the merge result is the cart document. */
  def returnToCart(id: Long): Action[AnyContent] = authed { req =>
    orFailResult(Abort.guarded(db.inTransaction(conn => orders.returnToCart(conn, req.user.id, id))), req)
  }

  /** `PATCH /api/v1/me/orders/{id}/surplus` — `{"amount":"10.00"}`. */
  def applySurplus(id: Long): Action[JsValue] = authed(parse.json) { req =>
    val body = Body(req.body)
    val parsed =
      for
        amount <- body.cents("amount")
        _      <- body.rejectUnknown(Set("amount")).toLeft(())
      yield amount

    orFail(parsed, req) { amount =>
      updatedOrFailed(Abort.guarded(db.inTransaction(conn => orders.applySurplus(conn, req.user.id, id, amount))), req)
    }
  }

  /** `PATCH /api/v1/me/orders/{id}/payment` — the fee is recomputed from the database rate. */
  def changePayment(id: Long): Action[JsValue] = authed(parse.json) { req =>
    val body = Body(req.body)
    val parsed =
      for
        paymentId <- body.long("payment_id", min = 1)
        _         <- body.rejectUnknown(Set("payment_id")).toLeft(())
      yield paymentId

    orFail(parsed, req) { paymentId =>
      updatedOrFailed(Abort.guarded(db.inTransaction(conn => orders.changePayment(conn, req.user.id, id, paymentId))), req)
    }
  }

  /** `PATCH /api/v1/me/orders/{id}/address` — legacy full consignee block. */
  def changeAddress(id: Long): Action[JsValue] = authed(parse.json) { req =>
    val body = Body(req.body)
    val emailField = body.str("email", 60).flatMap { value =>
      if !EmailAddress.valid(value) then Left(FieldError("email", "email must be a valid address")) else Right(value)
    }
    val parsed =
      for
        consignee    <- body.str("consignee", 60)
        email        <- emailField
        address      <- body.str("address", 255)
        zipcode      <- body.strOpt("zipcode", 60)
        telephone    <- body.strOpt("tel", 60)
        mobile       <- body.strOpt("mobile", 60)
        signBuilding <- body.strOpt("sign_building", 120)
        bestTime     <- body.strOpt("best_time", 120)
        _            <- body.rejectUnknown(Set("consignee", "email", "address", "zipcode", "tel",
          "mobile", "sign_building", "best_time")).toLeft(())
      yield OrderAddress(consignee, email, address, mobile.getOrElse(""), zipcode.getOrElse(""),
        telephone.getOrElse(""), signBuilding.getOrElse(""), bestTime.getOrElse(""))

    orFail(parsed, req) { data =>
      updatedOrFailed(Abort.guarded(db.inTransaction(conn => orders.changeAddress(conn, req.user.id, id, data))), req)
    }
  }

  /** `GET /api/v1/me/group-buys` */
  def groupBuys(page: Option[Int], page_size: Option[Int]): Action[AnyContent] = authed { req =>
    orFail(Paging.of(page, page_size), req) { paging =>
      Ok(db.readOnly(conn => groupBuys.mine(conn, req.user.id, paging)))
    }
  }

  /** `GET /api/v1/me/group-buys/{id}` — `id` is the group-buy activity id. */
  def groupBuy(id: Long): Action[AnyContent] = authed { req =>
    orFailResult(db.readOnly(conn => groupBuys.detail(conn, req.user.id, id)), req)
  }

  /** `GET /api/v1/me/affiliate` */
  def affiliate(page: Option[Int], page_size: Option[Int]): Action[AnyContent] = authed { req =>
    orFail(Paging.of(page, page_size), req) { paging =>
      Ok(db.readOnly(conn => affiliates.mine(conn, req.user.id, paging)))
    }
  }

  /** `GET /api/v1/me/shipments` */
  def shipments(page: Option[Int], page_size: Option[Int]): Action[AnyContent] = authed { req =>
    orFail(Paging.of(page, page_size), req) { paging =>
      Ok(db.readOnly(conn => orders.shipments(conn, req.user.id, paging)))
    }
  }

  /** `GET /api/v1/me/points/conversion-options` */
  def pointOptions: Action[AnyContent] = authed { req =>
    Ok(db.readOnly(conn => points.options(conn, req.user.id)))
  }

  /** `POST /api/v1/me/points/conversions` */
  def convertPoints: Action[JsValue] = authed(parse.json) { req =>
    val body = Body(req.body)
    val parsed =
      for
        optionId <- body.long("option_id", min = 1)
        quantity <- body.long("quantity", min = 1, max = 100)
        _        <- body.rejectUnknown(Set("option_id", "quantity")).toLeft(())
      yield (optionId, quantity)

    orFail(parsed, req) { case (optionId, quantity) =>
      val outcome = Abort.guarded(db.inTransaction(conn => points.convert(conn, req.user.id, optionId, quantity)))
      outcome match
        case Right(payload) => Created(payload)
        case Left(PointsFailure.OptionUnavailable) =>
          ApiError.notFound("points conversion option is not available", req)
        case Left(PointsFailure.InsufficientPoints) =>
          ApiError.conflict(ErrorCode.InvalidState, "not enough points for this conversion", req)
    }
  }

  /** `DELETE /api/v1/me/browsing-history` */
  def clearBrowsingHistory: Action[AnyContent] = authed { req =>
    val removed = db.inTransaction(conn => orders.clearBrowsingHistory(conn, req.user.id))
    Ok(Json.obj("removed" -> removed))
  }

  private def failureResult(failure: OrderFailure, req: RequestHeader): Result = failure match
    case OrderFailure.NotFound            => ApiError.notFound("order not found", req)
    case OrderFailure.AddressNotFound     => ApiError.notFound("address not found for this account", req)
    case OrderFailure.ShippingUnavailable => ApiError.notFound("shipping method is not available", req)
    case OrderFailure.PaymentUnavailable  => ApiError.notFound("payment method is not available", req)
    case OrderFailure.OutOfStock(name, stock) => ApiError.outOfStock(s"only $stock of $name is in stock", req)
    case OrderFailure.SameOrder           =>
      ApiError.badRequest("from_order_id and to_order_id must differ", req, Some("to_order_id"))
    case other =>
      ApiError.conflict(if other == OrderFailure.IdempotencyConflict then ErrorCode.Conflict else ErrorCode.InvalidState,
        messageFor(other), req)

  private def messageFor(failure: OrderFailure): String = failure match
    case OrderFailure.IdempotencyConflict => "the idempotency key was already used for a different request"
    case OrderFailure.EmptyCart           => "the cart has no sellable goods"
    case OrderFailure.NothingSellable     => "none of the ordered goods are still on sale"
    case OrderFailure.InsufficientBalance => "the account balance does not cover this amount"
    case _                                => "the order is not in a state that allows this action"

  private def orFailResult(result: Either[OrderFailure, JsObject], req: RequestHeader): Result = result match
    case Right(payload) => Ok(payload)
    case Left(failure)  => failureResult(failure, req)

  private def createdOrFailed(result: Either[OrderFailure, JsObject], req: RequestHeader): Result = result match
    case Right(payload) => Created(payload)
    case Left(failure)  => failureResult(failure, req)

  private def updatedOrFailed(result: Either[OrderFailure, JsObject], req: RequestHeader): Result = result match
    case Right(payload) => Ok(payload)
    case Left(failure)  => failureResult(failure, req)
