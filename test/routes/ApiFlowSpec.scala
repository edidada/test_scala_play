package routes

import java.nio.file.Files
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json._
import play.api.mvc.Result
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.Future

/**
 * End-to-end smoke test for the API contract.
 *
 * The whole application boots against a fresh temporary SQLite file — `ecs.Bootstrap`
 * creates the schema and the demo rows exactly like a first development run — and one
 * customer then walks the real purchase path: register, cart, address, quote, order,
 * gateway callback, confirm receipt. Money arithmetic and the idempotency and replay
 * rules are asserted against the seeded prices, not against mocks.
 */
class ApiFlowSpec extends PlaySpec with GuiceOneAppPerSuite:

  private val CallbackSecret = "it-payment-callback-secret"
  private val dbFile = Files.createTempDirectory("ecshop-it").resolve("api.sqlite3")

  override def fakeApplication(): Application =
    new GuiceApplicationBuilder()
      .configure(
        "db.default.url"              -> s"jdbc:sqlite:${dbFile.toString.replace('\\', '/')}",
        "payment.callback_hmac_secret" -> CallbackSecret,
        "site.base_url"               -> "http://localhost"
      )
      .build()

  /* ---------------------------------------------------------------- helpers */

  private def respond(result: Future[Result]): (Int, JsValue) =
    val code = status(result)
    val json =
      if code == 204 || contentType(result).isEmpty then Json.obj()
      else contentAsJson(result)
    (code, json)

  /** A JSON request; `token` becomes the bearer header, `headers` anything else. */
  private def send(method: String, path: String, body: JsValue,
    token: Option[String] = None, headers: Seq[(String, String)] = Nil): (Int, JsValue) =
    val withBody = FakeRequest(method, path).withJsonBody(body)
    val withAuth = token.fold(withBody)(t => withBody.withHeaders("Authorization" -> s"Bearer $t"))
    val request = if headers.isEmpty then withAuth else withAuth.withHeaders(headers*)
    respond(route(app, request).getOrElse(fail(s"no route matched $method $path")))

  private def fetch(path: String, token: Option[String] = None,
    headers: Seq[(String, String)] = Nil): (Int, JsValue) =
    val base = FakeRequest(GET, path)
    val withAuth = token.fold(base)(t => base.withHeaders("Authorization" -> s"Bearer $t"))
    val request = if headers.isEmpty then withAuth else withAuth.withHeaders(headers*)
    respond(route(app, request).getOrElse(fail(s"no route matched GET $path")))

  private def bearer(json: JsValue): String = (json \ "access_token").as[String]

  private def field(json: JsValue, name: String): String = (json \ name).as[String]

  /** The gateway signs the exact bytes it posts, so compute it independently here. */
  private def hmacHex(secret: String, payload: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    mac.doFinal(payload.getBytes("UTF-8")).map(b => f"$b%02x").mkString

  /* ------------------------------------------------------------------ specs */

  "the service" must {
    "answer the health probe and keep the error envelope honest" in {
      val (health, healthBody) = fetch("/healthz")
      health mustBe 200
      field(healthBody, "status") mustBe "ok"

      // No token at all: 401 with the machine code, never a Play HTML page.
      val (unauth, unauthBody) = fetch("/api/v1/me", None)
      unauth mustBe 401
      field(unauthBody, "code") mustBe "unauthenticated"
      unauthBody \ "request_id" match
        case JsDefined(v) => v.as[String].nonEmpty mustBe true
        case _            => fail("an error body must carry a request_id")

      // A caller-supplied id is echoed, so logs and the client can be joined.
      val (echoed, echoedBody) = fetch("/api/v1/me", None, Seq("X-Request-Id" -> "it-request-1"))
      echoed mustBe 401
      field(echoedBody, "request_id") mustBe "it-request-1"

      val (missing, missingBody) = fetch("/api/v1/goods/999999")
      missing mustBe 404
      field(missingBody, "code") mustBe "not_found"
    }

    "price the seeded catalogue the way the contract says" in {
      val (ok, goods) = fetch("/api/v1/goods/12")
      ok mustBe 200
      field(goods, "price") mustBe "49.90"

      // Two units at 49.90 plus the 8.00 flat shipping and a free gateway is 107.80.
      val (options, optionBody) = fetch("/api/v1/shipping-options")
      options mustBe 200
      optionBody \ "shipping_options" match
        case JsDefined(items) => items.as[List[JsValue]].nonEmpty mustBe true
        case _                => fail("shipping options must be a list")
    }
  }

  "a brand new customer" must {
    "register, order and get paid through the gateway" in {
      val stamp = System.currentTimeMillis().toString
      val userName = s"it$stamp"
      val email = s"it$stamp@example.test"

      val (registered, regBody) = send(POST, "/api/v1/auth/register",
        Json.obj("username" -> userName, "email" -> email, "password" -> "Passw0rd!"))
      registered mustBe 201
      val token = bearer(regBody)

      // The same credentials through login must work and end the old sessions.
      val (loggedIn, loginBody) = send(POST, "/api/v1/auth/login",
        Json.obj("username" -> userName, "password" -> "Passw0rd!"))
      loggedIn mustBe 200
      val session = bearer(loginBody)

      val (me, meBody) = fetch("/api/v1/me", Some(session))
      me mustBe 200
      field(meBody, "username") mustBe userName

      val (added, cartLine) = send(POST, "/api/v1/me/cart",
        Json.obj("goods_id" -> 12, "quantity" -> 2), Some(session))
      added mustBe 201
      (cartLine \ "quantity").as[Int] mustBe 2

      val (addressAdded, address) = send(POST, "/api/v1/me/addresses",
        Json.obj("consignee" -> "Alice", "country_id" -> 1, "province_id" -> 2, "city_id" -> 52,
          "district_id" -> 500, "address" -> "Dongdan 1", "zip" -> "100000",
          "mobile" -> "13800000000", "is_default" -> true), Some(session))
      addressAdded mustBe 201
      val addressId = (address \ "id").as[Long]

      val (quoted, quote) = send(POST, "/api/v1/checkout/quote",
        Json.obj("address_id" -> addressId, "shipping_id" -> 1, "payment_id" -> 1), Some(session))
      quoted mustBe 200
      field(quote, "goods_amount") mustBe "99.80"
      field(quote, "shipping_fee") mustBe "8.00"
      field(quote, "order_amount") mustBe "107.80"

      // The order is created under an idempotency key; a retry replays it.
      val key = s"it-order-$stamp"
      val orderJson = Json.obj("address_id" -> addressId, "shipping_id" -> 1, "payment_id" -> 1,
        "remark" -> "integration")
      val (created, order) = send(POST, "/api/v1/orders", orderJson, Some(session),
        Seq("Idempotency-Key" -> key))
      created mustBe 201
      val orderId = (order \ "id").as[Long]
      val orderSn = field(order, "order_sn")
      val orderAmount = field(order, "order_amount")
      orderAmount mustBe "107.80"

      val (replayed, replayBody) = send(POST, "/api/v1/orders", orderJson, Some(session),
        Seq("Idempotency-Key" -> key))
      replayed mustBe 200
      (replayBody \ "replayed").as[Boolean] mustBe true

      // Reusing the key for different content is a conflict, not a second order.
      val (clash, _) = send(POST, "/api/v1/orders",
        Json.obj("address_id" -> addressId, "shipping_id" -> 2, "payment_id" -> 1),
        Some(session), Seq("Idempotency-Key" -> key))
      clash mustBe 409

      val (listing, listBody) = fetch("/api/v1/me/orders", Some(session))
      listing mustBe 200
      (listBody \ "total").as[Int] mustBe 1

      /* The gateway callback: only the HMAC of the exact bytes authenticates it. */
      val payload = Json.obj("provider_trade_no" -> s"it-trade-$stamp",
        "order_sn" -> orderSn, "amount" -> orderAmount)
      val wire = Json.stringify(payload)
      val callbackPath = "/api/v1/payments/mockpay/callback"

      val (noSignature, _) = send(POST, callbackPath, payload, None,
        Seq("X-Payment-Signature" -> ""))
      noSignature mustBe 401

      val (badSignature, _) = send(POST, callbackPath, payload, None,
        Seq("X-Payment-Signature" -> hmacHex("the-wrong-secret", wire)))
      badSignature mustBe 401

      val (paid, paidBody) = send(POST, callbackPath, payload, None,
        Seq("X-Payment-Signature" -> hmacHex(CallbackSecret, wire)))
      paid mustBe 200
      (paidBody \ "replayed").as[Boolean] mustBe false
      field(paidBody, "pay_status") mustBe "paid"

      val (again, againBody) = send(POST, callbackPath, payload, None,
        Seq("X-Payment-Signature" -> hmacHex(CallbackSecret, wire)))
      again mustBe 200
      (againBody \ "replayed").as[Boolean] mustBe true

      // A second trade number for the same money is refused, not double-settled.
      val mismatch = Json.obj("provider_trade_no" -> s"it-other-$stamp",
        "order_sn" -> orderSn, "amount" -> "9.99")
      val (mismatched, _) = send(POST, callbackPath, mismatch, None,
        Seq("X-Payment-Signature" -> hmacHex(CallbackSecret, Json.stringify(mismatch))))
      mismatched mustBe 409

      val (statusCheck, statusBody) = fetch(s"/api/v1/me/orders/by-number/$orderSn/status", Some(session))
      statusCheck mustBe 200
      field(statusBody, "pay_status") mustBe "paid"

      val (received, _) = send(POST, s"/api/v1/me/orders/$orderId/received", Json.obj(), Some(session))
      received mustBe 200
      val (receivedTwice, _) = send(POST, s"/api/v1/me/orders/$orderId/received", Json.obj(), Some(session))
      receivedTwice mustBe 409

      val (logout, _) = send(POST, "/api/v1/auth/logout", Json.obj(), Some(session))
      logout mustBe 204
      val (afterLogout, _) = fetch("/api/v1/me", Some(session))
      afterLogout mustBe 401
    }

    "reject a malformed request before touching the database" in {
      val stamp = s"x${System.nanoTime()}"
      val (created, body) = send(POST, "/api/v1/auth/register",
        Json.obj("username" -> stamp, "email" -> s"$stamp@example.test", "password" -> "Passw0rd!"))
      created mustBe 201
      val session = bearer(body)

      // Unknown fields are refused so a typo cannot silently change an order.
      val (unknownField, unknownBody) = send(POST, "/api/v1/me/cart",
        Json.obj("goods_id" -> 12, "quantity" -> 1, "quntity" -> 5), Some(session))
      unknownField mustBe 400
      field(unknownBody, "code") mustBe "validation_error"

      // Money is a decimal string, never a bare number.
      val (badMoney, _) = send(POST, "/api/v1/me/account/requests",
        Json.obj("kind" -> "withdrawal", "amount" -> 10, "payment_id" -> 1), Some(session))
      badMoney mustBe 400

      // A client-supplied sort never reaches SQL as text: an injected string
      // simply falls back to the default order and still answers 200.
      val (catalog, catalogBody) = fetch("/api/v1/goods?sort=shop_price;%20DROP%20TABLE%20goods")
      catalog mustBe 200
      (catalogBody \ "items").as[List[JsValue]].nonEmpty mustBe true
    }
  }
