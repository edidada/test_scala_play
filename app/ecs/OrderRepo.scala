package ecs

import play.api.libs.json.{JsObject, Json}

import java.sql.Connection
import javax.inject.{Inject, Singleton}

/**
 * Orders (PHP `flow.php?step=done`, `user.php?act=order_list|order_detail|cancel_order|
 * affirm_received|return_to_cart|merge_order|order_query|act_edit_surplus|act_edit_payment|
 * save_order_address|track_packages|clear_history`).
 *
 * Placing an order is the one place where several guarded writes have to either all
 * happen or none: stock is taken with `goods_number >= ?`, a state change is written
 * with the old status in the `WHERE` clause, and a failure part-way through throws
 * [[Abort]] so the caller's transaction rolls back instead of committing half an order.
 * All money is computed in integer cents from database values.
 */
@Singleton
class OrderRepo @Inject() (db: Db, cart: CartRepo):

  private val Pending   = "pending_payment"
  private val Paid      = "paid"
  private val Unshipped = "unshipped"

  private val OrderColumns =
    """order_id, order_sn, user_id, order_status, shipping_status, pay_status, consignee, email,
      |address, mobile, shipping_id, pay_id, goods_amount, shipping_fee, payment_fee,
      |discount_amount, order_amount, remark, add_time, pay_time, shipping_time""".stripMargin

  private def decimal(cents: Long): BigDecimal = BigDecimal(cents) / 100

  private def moneyCents(row: Row, column: String): Long = CartRepo.toCents(row.dec(column))

  private def summary(row: Row): JsObject =
    Json.obj(
      "id"              -> row.long("order_id"),
      "order_sn"        -> row.str("order_sn"),
      "status"          -> row.str("order_status"),
      "shipping_status" -> row.str("shipping_status"),
      "pay_status"      -> row.str("pay_status"),
      "goods_amount"    -> ApiJson.money(row.dec("goods_amount")),
      "shipping_fee"    -> ApiJson.money(row.dec("shipping_fee")),
      "payment_fee"     -> ApiJson.money(row.dec("payment_fee")),
      "discount_amount" -> ApiJson.money(row.dec("discount_amount")),
      "order_amount"    -> ApiJson.money(row.dec("order_amount")),
      "remark"          -> row.str("remark"),
      "created_at"      -> Views.timestamp(row, "add_time"),
      "paid_at"         -> row.optStr("pay_time").getOrElse(""),
      "shipped_at"      -> row.optStr("shipping_time").getOrElse("")
    )

  private def orderRow(conn: Connection, userId: Long, orderId: Long, lock: Boolean): Option[Row] =
    db.row(conn,
      s"""SELECT $OrderColumns FROM order_info o WHERE o.order_id = ? AND o.user_id = ?${Sql.forUpdate(db.dialect)}"""
        .stripMargin, Seq(orderId, userId))

  private def fresh(conn: Connection, userId: Long, orderId: Long): Option[Row] =
    orderRow(conn, userId, orderId, lock = false)

  /** Legacy order history line, always attributed to the acting customer. */
  private def audit(conn: Connection, orderId: Long, actorId: Long, note: String): Unit =
    db.execute(conn,
      """INSERT INTO order_action (order_id, actor_type, actor_id, action_note, created_at)
        |VALUES (?, 'user', ?, ?, ?)""".stripMargin, Seq(orderId, actorId, note, ApiJson.nowUtc))

  /** Integer-cent ledger entry, the audit source for balance and points moves. */
  private def ledger(conn: Connection, userId: Long, availableDelta: Long, frozenDelta: Long,
    reason: String, referenceId: Long): Unit =
    db.execute(conn,
      """INSERT INTO account_log (user_id, available_delta_cents, frozen_delta_cents, reason,
        |  reference_type, reference_id, created_at)
        |VALUES (?, ?, ?, ?, 'order_info', ?, ?)""".stripMargin,
      Seq(userId, availableDelta, frozenDelta, reason, referenceId, ApiJson.nowUtc))

  private def newOrderSn(salt: String): String =
    s"EC${ApiJson.nowUnix}${Crypto.sha256Hex(salt + Crypto.newToken()).take(6).toUpperCase}"

  /**
   * `POST /api/v1/orders`.
   *
   * The (user, idempotency key) pair is unique in `order_info`, so a replay of the
   * same fingerprint returns the original order and a different body under the same
   * key is refused. Stock is deducted with a guarded update per line and any refusal
   * throws, which rolls the whole transaction back.
   */
  def create(conn: Connection, userId: Long, idempotencyKey: String, fingerprint: String, addressId: Long,
    shippingId: Long, paymentId: Long, remark: String): Either[OrderFailure, (JsObject, Boolean)] =

    db.row(conn,
      s"""SELECT order_id, request_fingerprint FROM order_info WHERE user_id = ? AND idempotency_key = ?
         |${Sql.forUpdate(db.dialect)}""".stripMargin, Seq(userId, idempotencyKey)) match
      case Some(existing) =>
        if existing.str("request_fingerprint") == fingerprint then
          Right((summary(fresh(conn, userId, existing.long("order_id")).get), true))
        else Left(OrderFailure.IdempotencyConflict)
      case None =>
        // `user_address` has no e-mail column; the order's contact address is the
        // address row and the account e-mail comes from `users`.
        db.row(conn,
          """SELECT a.consignee, a.address, a.mobile, u.email
            |FROM user_address a JOIN users u ON u.user_id = a.user_id
            |WHERE a.address_id = ? AND a.user_id = ?""".stripMargin,
          Seq(addressId, userId)) match
          case None => Left(OrderFailure.AddressNotFound)
          case Some(address) =>
            val shipping = db.row(conn,
              "SELECT shipping_id, shipping_fee FROM shipping WHERE shipping_id = ? AND enabled = 1", Seq(shippingId))
            val payment = db.row(conn,
              "SELECT pay_id, pay_fee FROM payment WHERE pay_id = ? AND enabled = 1", Seq(paymentId))
            if shipping.isEmpty then Left(OrderFailure.ShippingUnavailable)
            else if payment.isEmpty then Left(OrderFailure.PaymentUnavailable)
            else
              val lines = cart.pricedLines(conn, userId)
              if lines.isEmpty then Left(OrderFailure.EmptyCart)
              else
                val goodsCents = lines.map(_.subtotalCents).sum
                val shippingCents = CartRepo.toCents(shipping.get.dec("shipping_fee"))
                val paymentCents = CartRepo.toCents(payment.get.dec("pay_fee"))
                val orderCents = goodsCents + shippingCents + paymentCents
                val orderSn = newOrderSn(s"$userId:$idempotencyKey")
                val orderId = db.insertReturningKey(conn,
                  """INSERT INTO order_info (order_sn, user_id, order_status, shipping_status, pay_status,
                    |  consignee, email, address, mobile, shipping_id, pay_id, goods_amount, shipping_fee,
                    |  payment_fee, discount_amount, order_amount, idempotency_key, request_fingerprint,
                    |  remark, add_time)
                    |VALUES (?, ?, 'pending_payment', 'unshipped', 'unpaid', ?, ?, ?, ?, ?, ?, ?, ?, ?, 0.00, ?, ?, ?, ?, ?)"""
                    .stripMargin,
                  Seq(orderSn, userId, address.str("consignee"), address.str("email"), address.str("address"),
                    address.str("mobile"), shippingId, paymentId, decimal(goodsCents), decimal(shippingCents),
                    decimal(paymentCents), decimal(orderCents), idempotencyKey, fingerprint, remark, ApiJson.nowUtc))

                lines.foreach { line =>
                  val took = db.execute(conn,
                    """UPDATE goods SET goods_number = goods_number - ?, last_update = ?
                      |WHERE goods_id = ? AND goods_number >= ?""".stripMargin,
                    Seq(line.quantity, ApiJson.nowUtc, line.goodsId, line.quantity))
                  if took != 1 then throw Abort(OrderFailure.OutOfStock(line.name, line.stock))
                }
                snapshot(conn, orderId, lines)
                db.execute(conn, "DELETE FROM cart WHERE user_id = ?", Seq(userId))
                audit(conn, orderId, userId, "order placed from cart")
                Right((summary(fresh(conn, userId, orderId).get), false))

  /** Freeze the bought rows into `order_goods` from live goods data. */
  private def snapshot(conn: Connection, orderId: Long, lines: List[PricedLine]): Unit =
    val ids = lines.map(_.goodsId)
    val rows = db.rows(conn,
      s"""SELECT goods_id, goods_sn, goods_name, market_price
         |FROM goods WHERE goods_id IN (${Sql.marks(ids.size)})""".stripMargin, ids)
    val byId = rows.map(r => r.long("goods_id") -> r).toMap
    lines.foreach { line =>
      val goods = byId.getOrElse(line.goodsId, throw Abort(OrderFailure.OutOfStock(line.name, 0L)))
      db.execute(conn,
        """INSERT INTO order_goods (order_id, goods_id, goods_name, goods_sn, goods_number,
          |  market_price, goods_price, goods_attr)
          |VALUES (?, ?, ?, ?, ?, ?, ?, '')""".stripMargin,
        Seq(orderId, line.goodsId, goods.str("goods_name"), goods.str("goods_sn"), line.quantity,
          goods.dec("market_price"), decimal(line.priceCents)))
    }

  /** `GET /api/v1/me/orders` — newest order first. */
  def list(conn: Connection, userId: Long, page: Paging.Page): JsObject =
    val total = db.count(conn, "SELECT COUNT(*) FROM order_info WHERE user_id = ?", Seq(userId))
    val rows = db.rows(conn,
      s"""SELECT $OrderColumns FROM order_info WHERE user_id = ?
         |ORDER BY order_id DESC
         |LIMIT ? OFFSET ?""".stripMargin, Seq(userId, page.pageSize, page.offset))
    ApiJson.page(page.page, page.pageSize, total, rows.map(summary))

  private def items(conn: Connection, orderId: Long): List[JsObject] =
    db.rows(conn,
      """SELECT rec_id, goods_id, goods_name, goods_sn, goods_number, market_price, goods_price
        |FROM order_goods WHERE order_id = ?
        |ORDER BY rec_id""".stripMargin, Seq(orderId))
      .map { r =>
        val subtotal = CartRepo.toCents(r.dec("goods_price")) * r.long("goods_number")
        Json.obj(
          "id"         -> r.long("rec_id"),
          "goods_id"   -> r.long("goods_id"),
          "name"       -> r.str("goods_name"),
          "goods_sn"   -> r.str("goods_sn"),
          "quantity"   -> r.long("goods_number"),
          "price"      -> ApiJson.money(r.dec("goods_price")),
          "subtotal"   -> ApiJson.money(subtotal)
        )
      }

  /** `GET /api/v1/me/orders/{id}` — consignee block plus the frozen snapshots. */
  def detail(conn: Connection, userId: Long, orderId: Long): Either[OrderFailure, JsObject] =
    fresh(conn, userId, orderId) match
      case None => Left(OrderFailure.NotFound)
      case Some(row) =>
        Right(summary(row) ++ Json.obj(
          "consignee" -> row.str("consignee"),
          "email"     -> row.str("email"),
          "address"   -> row.str("address"),
          "mobile"    -> row.str("mobile"),
          "items"     -> Json.toJson(items(conn, orderId))
        ))

  /** `GET /api/v1/me/orders/by-number/{order_sn}/status` — order number plus owner only. */
  def statusByNumber(conn: Connection, userId: Long, orderSn: String): Either[OrderFailure, JsObject] =
    db.row(conn, s"SELECT $OrderColumns FROM order_info WHERE order_sn = ? AND user_id = ?", Seq(orderSn, userId))
      .map { row =>
        Right(summary(row) ++ Json.obj(
          "pay_amount"  -> ApiJson.money(row.dec("order_amount")),
          "total_items" -> db.count(conn, "SELECT COUNT(*) FROM order_goods WHERE order_id = ?",
            Seq(row.long("order_id")))
        ))
      }.getOrElse(Left(OrderFailure.NotFound))

  /**
   * `POST /api/v1/me/orders/{id}/cancel`.
   *
   * The status is switched with a conditional update *before* stock is restored, so
   * only one of two concurrent cancels can ever reach the restock and refund legs.
   */
  def cancel(conn: Connection, userId: Long, orderId: Long): Either[OrderFailure, JsObject] =
    orderRow(conn, userId, orderId, lock = true) match
      case None => Left(OrderFailure.NotFound)
      case Some(row) if row.str("order_status") != Pending => Left(OrderFailure.WrongState)
      case Some(_) =>
        if db.execute(conn,
          """UPDATE order_info SET order_status = 'cancelled'
            |WHERE order_id = ? AND user_id = ? AND order_status = 'pending_payment'""".stripMargin,
          Seq(orderId, userId)) != 1 then throw Abort(OrderFailure.WrongState)
        db.rows(conn, "SELECT goods_id, goods_number FROM order_goods WHERE order_id = ?", Seq(orderId))
          .foreach { line =>
            db.execute(conn,
              """UPDATE goods SET goods_number = goods_number + ?, last_update = ?
                |WHERE goods_id = ?""".stripMargin, Seq(line.long("goods_number"), ApiJson.nowUtc, line.long("goods_id")))
          }
        val used = db.row(conn, "SELECT paid_cents FROM order_balance_payment WHERE order_id = ?", Seq(orderId))
          .map(_.long("paid_cents")).getOrElse(0L)
        if used > 0 then
          db.execute(conn, "UPDATE account_balance SET available_cents = available_cents + ? WHERE user_id = ?",
            Seq(used, userId))
          ledger(conn, userId, used, 0L, "order cancellation refund", orderId)
          db.execute(conn, "DELETE FROM order_balance_payment WHERE order_id = ? AND user_id = ?", Seq(orderId, userId))
        audit(conn, orderId, userId, "order cancelled by customer")
        Right(summary(fresh(conn, userId, orderId).get))

  /** `POST /api/v1/me/orders/{id}/received` — only a paid order can be affirmed. */
  def confirmReceived(conn: Connection, userId: Long, orderId: Long): Either[OrderFailure, JsObject] =
    orderRow(conn, userId, orderId, lock = true) match
      case None => Left(OrderFailure.NotFound)
      case Some(row) if row.str("order_status") != Paid => Left(OrderFailure.WrongState)
      case Some(_) =>
        if db.execute(conn,
          """UPDATE order_info SET order_status = 'received'
            |WHERE order_id = ? AND user_id = ? AND order_status = 'paid'""".stripMargin,
          Seq(orderId, userId)) != 1 then throw Abort(OrderFailure.WrongState)
        audit(conn, orderId, userId, "customer confirmed receipt")
        Right(summary(fresh(conn, userId, orderId).get))

  /**
   * `POST /api/v1/me/orders/{id}/cart` — merge the snapshot back into the cart,
   * capped by what is actually in stock right now.
   */
  def returnToCart(conn: Connection, userId: Long, orderId: Long): Either[OrderFailure, JsObject] =
    orderRow(conn, userId, orderId, lock = true) match
      case None => Left(OrderFailure.NotFound)
      case Some(_) =>
        val rows = db.rows(conn,
          """SELECT og.goods_id, og.goods_number, g.goods_number AS stock
            |FROM order_goods og
            |JOIN goods g ON g.goods_id = og.goods_id
            |WHERE og.order_id = ? AND g.is_delete = 0 AND g.is_on_sale = 1 AND g.goods_number > 0
            |ORDER BY og.rec_id""".stripMargin, Seq(orderId))
        if rows.isEmpty then Left(OrderFailure.NothingSellable)
        else
          rows.foreach { r =>
            cart.mergeQuantity(conn, userId, r.long("goods_id"), math.min(r.long("goods_number"), r.long("stock")))
          }
          Right(cart.list(conn, userId))

  /**
   * `PATCH /api/v1/me/orders/{id}/surplus` — pay part of the bill from the account
   * balance. The amount is truncated to what is still owed excluding the payment fee,
   * the balance is taken with a guarded update, and a fully covered order flips to
   * `paid` in the same statement.
   */
  def applySurplus(conn: Connection, userId: Long, orderId: Long,
    amountCents: Long): Either[OrderFailure, JsObject] =
    orderRow(conn, userId, orderId, lock = true) match
      case None => Left(OrderFailure.NotFound)
      case Some(row) if row.str("order_status") != Pending || row.str("pay_status") != "unpaid" =>
        Left(OrderFailure.WrongState)
      case Some(row) =>
        val orderCents = moneyCents(row, "order_amount")
        val paymentFee = moneyCents(row, "payment_fee")
        val alreadyPaid = db.row(conn, "SELECT paid_cents FROM order_balance_payment WHERE order_id = ?", Seq(orderId))
          .map(_.long("paid_cents")).getOrElse(0L)
        val payable = (orderCents - paymentFee - alreadyPaid).max(0L)
        if payable <= 0L then Left(OrderFailure.WrongState)
        else
          val applied = amountCents.min(payable)
          db.execute(conn,
            Sql.insertIgnore(db.dialect) + "account_balance (user_id, available_cents, frozen_cents) VALUES (?, 0, 0)",
            Seq(userId))
          if db.execute(conn,
            """UPDATE account_balance SET available_cents = available_cents - ?
              |WHERE user_id = ? AND available_cents >= ?""".stripMargin, Seq(applied, userId, applied)) != 1 then
            throw Abort(OrderFailure.InsufficientBalance)

          val columns = Seq("order_id", "user_id", "paid_cents", "updated_at")
          val assignments = db.dialect match
            case Dialect.SQLite => Seq("paid_cents" -> "order_balance_payment.paid_cents + excluded.paid_cents",
              "updated_at" -> "excluded.updated_at")
            case Dialect.MySQL  => Seq("paid_cents" -> "paid_cents + VALUES(paid_cents)",
              "updated_at" -> "VALUES(updated_at)")
          db.execute(conn,
            Sql.upsert(db.dialect, "order_balance_payment", columns, columns.map(_ => "?"),
              Seq("order_id"), assignments),
            Seq(orderId, userId, applied, ApiJson.nowUtc))

          val newTotal = orderCents - applied
          val settled = newTotal <= 0L
          db.execute(conn,
            """UPDATE order_info SET order_amount = ?, pay_status = ?, order_status = ?, pay_time = ?
              |WHERE order_id = ? AND user_id = ? AND order_status = 'pending_payment'""".stripMargin,
            Seq(decimal(newTotal), if settled then Paid else "unpaid",
              if settled then Paid else Pending, if settled then Some(ApiJson.nowUtc) else None, orderId, userId))
          ledger(conn, userId, -applied, 0L, "balance payment on order", orderId)
          audit(conn, orderId, userId, s"paid $applied cents of the order with the account balance")
          Right(summary(fresh(conn, userId, orderId).get))

  /**
   * `PATCH /api/v1/me/orders/{id}/payment` — swap the payment method of an unpaid,
   * unshipped order and recompute the fixed fee from the database rate.
   */
  def changePayment(conn: Connection, userId: Long, orderId: Long,
    paymentId: Long): Either[OrderFailure, JsObject] =
    orderRow(conn, userId, orderId, lock = true) match
      case None => Left(OrderFailure.NotFound)
      case Some(row) if row.str("order_status") != Pending || row.str("shipping_status") != Unshipped =>
        Left(OrderFailure.WrongState)
      case Some(row) if row.optLong("pay_id").contains(paymentId) => Left(OrderFailure.WrongState)
      case Some(row) =>
        val payment = db.row(conn, "SELECT pay_id, pay_fee FROM payment WHERE pay_id = ? AND enabled = 1", Seq(paymentId))
        payment match
          case None => Left(OrderFailure.PaymentUnavailable)
          case Some(method) =>
            val orderCents = moneyCents(row, "order_amount")
            val oldFee = moneyCents(row, "payment_fee")
            val remaining = (orderCents - oldFee).max(0L)
            val newFee = CartRepo.toCents(method.dec("pay_fee"))
            if db.execute(conn,
              """UPDATE order_info SET pay_id = ?, payment_fee = ?, order_amount = ?
                |WHERE order_id = ? AND user_id = ? AND order_status = 'pending_payment'""".stripMargin,
              Seq(paymentId, decimal(newFee), decimal(remaining + newFee), orderId, userId)) != 1 then
              throw Abort(OrderFailure.WrongState)
            audit(conn, orderId, userId, s"payment method changed to $paymentId")
            Right(summary(fresh(conn, userId, orderId).get))

  /**
   * `PATCH /api/v1/me/orders/{id}/address` — core fields live on `order_info`, the
   * full legacy consignee block on the one-to-one `order_delivery_address`.
   */
  def changeAddress(conn: Connection, userId: Long, orderId: Long,
    data: OrderAddress): Either[OrderFailure, JsObject] =
    orderRow(conn, userId, orderId, lock = true) match
      case None => Left(OrderFailure.NotFound)
      case Some(row) if row.str("order_status") != Pending || row.str("shipping_status") != Unshipped =>
        Left(OrderFailure.WrongState)
      case Some(_) =>
        db.execute(conn,
          """UPDATE order_info SET consignee = ?, email = ?, address = ?, mobile = ?
            |WHERE order_id = ? AND user_id = ? AND order_status = 'pending_payment'""".stripMargin,
          Seq(data.consignee, data.email, data.address, data.mobile, orderId, userId))
        val columns = Seq("order_id", "user_id", "email", "zipcode", "telephone", "sign_building", "best_time")
        val assignments = Seq("email", "zipcode", "telephone", "sign_building", "best_time").map(c => (c,
          if db.isSQLite then s"excluded.$c" else s"VALUES($c)"))
        db.execute(conn,
          Sql.upsert(db.dialect, "order_delivery_address", columns, columns.map(_ => "?"), Seq("order_id"), assignments),
          Seq(orderId, userId, data.email, data.zipcode, data.telephone, data.signBuilding, data.bestTime))
        audit(conn, orderId, userId, s"delivery address updated for ${data.consignee}")
        Right(summary(fresh(conn, userId, orderId).get))

  /**
   * `POST /api/v1/me/orders/merge`.
   *
   * Both orders are locked, must be the caller's own `pending_payment` orders, must
   * not have consumed balance, and must differ. The merged order takes the target's
   * consignee and fees, both snapshots move to it, and the two original rows lose
   * their payment and audit history before being deleted.
   */
  def merge(conn: Connection, userId: Long, fromId: Long,
    toId: Long): Either[OrderFailure, JsObject] =
    if fromId == toId then Left(OrderFailure.SameOrder)
    else
      val from = orderRow(conn, userId, fromId, lock = true)
      val to = orderRow(conn, userId, toId, lock = true)
      (from, to) match
        case (Some(source), Some(target))
          if source.str("order_status") == Pending && target.str("order_status") == Pending &&
            balanceUsed(conn, source.long("order_id")) == 0L && balanceUsed(conn, target.long("order_id")) == 0L =>
          val goodsCents = moneyCents(source, "goods_amount") + moneyCents(target, "goods_amount")
          val shippingCents = moneyCents(target, "shipping_fee")
          val paymentCents = moneyCents(target, "payment_fee")
          val orderCents = goodsCents + shippingCents + paymentCents
          val mergedId = db.insertReturningKey(conn,
            """INSERT INTO order_info (order_sn, user_id, order_status, shipping_status, pay_status, consignee,
              |  email, address, mobile, shipping_id, pay_id, goods_amount, shipping_fee, payment_fee,
              |  discount_amount, order_amount, idempotency_key, request_fingerprint, remark, add_time)
              |VALUES (?, ?, 'pending_payment', 'unshipped', 'unpaid', ?, ?, ?, ?, ?, ?, ?, ?, ?, 0.00, ?, ?, ?, ?, ?)"""
              .stripMargin,
            Seq(newOrderSn(s"$userId:$fromId:$toId"), userId, target.str("consignee"), target.str("email"),
              target.str("address"), target.str("mobile"), target.optLong("shipping_id"), target.optLong("pay_id"),
              decimal(goodsCents), decimal(shippingCents), decimal(paymentCents), decimal(orderCents),
              s"merge:$fromId:$toId:${ApiJson.nowUnix}", s"merged $fromId into $toId",
              target.str("remark"), ApiJson.nowUtc))

          db.execute(conn, "UPDATE order_goods SET order_id = ? WHERE order_id IN (?, ?)",
            Seq(mergedId, fromId, toId))
          db.row(conn,
            """SELECT promotion_id, promotion_type FROM order_promotion WHERE order_id IN (?, ?)
              |ORDER BY order_id""".stripMargin, Seq(fromId, toId)).foreach { link =>
                db.execute(conn,
                  """INSERT INTO order_promotion (order_id, promotion_id, promotion_type)
                    |VALUES (?, ?, ?)""".stripMargin,
                  Seq(mergedId, link.long("promotion_id"), link.str("promotion_type")))
              }
          List(fromId, toId).foreach { id =>
            db.execute(conn, "DELETE FROM order_promotion WHERE order_id = ?", Seq(id))
            db.execute(conn, "DELETE FROM pay_log WHERE order_id = ?", Seq(id))
            db.execute(conn, "DELETE FROM order_action WHERE order_id = ?", Seq(id))
            db.execute(conn, "DELETE FROM order_balance_payment WHERE order_id = ? AND user_id = ?", Seq(id, userId))
            db.execute(conn, "DELETE FROM order_delivery_address WHERE order_id = ? AND user_id = ?", Seq(id, userId))
            db.execute(conn, "DELETE FROM affiliate_log WHERE order_id = ?", Seq(id))
          }
          db.execute(conn,
            """DELETE FROM order_info WHERE user_id = ? AND order_status = 'pending_payment' AND order_id IN (?, ?)"""
              .stripMargin, Seq(userId, fromId, toId))
          audit(conn, mergedId, userId, s"orders $fromId and $toId merged")
          Right(summary(fresh(conn, userId, mergedId).get))
        case _ => Left(OrderFailure.WrongState)

  private def balanceUsed(conn: Connection, orderId: Long): Long =
    db.row(conn, "SELECT paid_cents FROM order_balance_payment WHERE order_id = ?", Seq(orderId))
      .map(_.long("paid_cents")).getOrElse(0L)

  /** `GET /api/v1/me/shipments` (PHP `user.php?act=track_packages`) — shipped orders. */
  def shipments(conn: Connection, userId: Long, page: Paging.Page): JsObject =
    val where = "o.user_id = ? AND o.shipping_status = 'shipped'"
    val total = db.count(conn, s"SELECT COUNT(*) FROM order_info o WHERE $where", Seq(userId))
    val rows = db.rows(conn,
      s"""SELECT o.order_id, o.order_sn, o.order_amount, o.shipping_time, o.consignee, o.mobile,
         |  o.shipping_id, s.shipping_name, s.shipping_code
         |FROM order_info o
         |LEFT JOIN shipping s ON s.shipping_id = o.shipping_id
         |WHERE $where
         |ORDER BY o.order_id DESC
         |LIMIT ? OFFSET ?""".stripMargin, Seq(userId, page.pageSize, page.offset))
    ApiJson.page(page.page, page.pageSize, total, rows.map { r =>
      Json.obj(
        "order_id"      -> r.long("order_id"),
        "order_sn"      -> r.str("order_sn"),
        "order_amount"  -> ApiJson.money(r.dec("order_amount")),
        "shipping_id"   -> r.optLong("shipping_id"),
        "shipping_name" -> r.str("shipping_name"),
        "shipping_code" -> r.str("shipping_code"),
        "consignee"     -> r.str("consignee"),
        "mobile"        -> r.str("mobile"),
        "shipped_at"    -> r.optStr("shipping_time").getOrElse("")
      )
    })

  /** `DELETE /api/v1/me/browsing-history` (PHP `user.php?act=clear_history`). */
  def clearBrowsingHistory(conn: Connection, userId: Long): Long =
    db.execute(conn, "DELETE FROM browsing_history WHERE user_id = ?", Seq(userId)).toLong

/** The full legacy consignee block accepted by `PATCH /me/orders/{id}/address`. */
final case class OrderAddress(consignee: String, email: String, address: String, mobile: String,
  zipcode: String, telephone: String, signBuilding: String, bestTime: String)

/** Why an order action was refused; each case maps to one HTTP status. */
enum OrderFailure:
  case NotFound, WrongState, IdempotencyConflict, AddressNotFound, ShippingUnavailable,
    PaymentUnavailable, EmptyCart, NothingSellable, SameOrder, InsufficientBalance
  case OutOfStock(name: String, stock: Long)
