package ecs

import org.scalatestplus.play._
import play.api.libs.json.Json

/**
 * Contract-level unit tests for the pieces every endpoint shares: money formatting,
 * pagination bounds, body validation, signatures and the cart/idempotency helpers.
 *
 * Nothing here touches a database, so the whole suite runs on any machine and any JDK.
 */
class ContractSpec extends PlaySpec:

  "money" must {
    "always be two decimals, in cents or from a decimal" in {
      ApiJson.money(BigDecimal("99.8")) mustBe "99.80"
      ApiJson.money(BigDecimal("0.001")) mustBe "0.00"
      ApiJson.money(10780L) mustBe "107.80"
      ApiJson.money(0L) mustBe "0.00"
    }

    "parse a decimal string into integer cents and reject anything else" in {
      ApiJson.toCents("10.00") mustBe Some(1000L)
      ApiJson.toCents(" 9.99 ") mustBe Some(999L)
      ApiJson.toCents("10.004") mustBe Some(1000L)
      ApiJson.toCents("abc") mustBe None
      ApiJson.toCents("1.23456") mustBe None
    }

    "round cart prices the same way the database columns are read" in {
      CartRepo.toCents(BigDecimal("49.9")) mustBe 4990L
      CartRepo.toCents(BigDecimal("0.005")) mustBe 1L
    }
  }

  "timestamps" must {
    "round-trip through the DATETIME format the schema stores" in {
      val unix = 1_700_000_000L
      val text = ApiJson.utcOf(unix)
      ApiJson.unixOf(text) mustBe unix
      text must fullyMatch regex """\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}"""
    }
  }

  "pagination" must {
    "default to twenty rows and refuse an out-of-range page size" in {
      Paging.of(None, None) mustBe Right(Paging.Page(1, 20))
      Paging.of(Some(3), Some(100)).map(_.offset) mustBe Right(200L)
      Paging.of(Some(0), None).left.map(_.field) mustBe Left("page")
      Paging.of(Some(1), Some(101)).left.map(_.field) mustBe Left("page_size")
      Paging.of(Some(1), Some(0)).left.map(_.field) mustBe Left("page_size")
    }

    "keep sorting on a whitelist instead of the client string" in {
      val allowed = Map("price" -> "g.shop_price", "id" -> "g.goods_id")
      Paging.sort(Some("price"), allowed, "g.goods_id") mustBe "g.shop_price"
      Paging.sort(Some("shop_price; DROP TABLE users"), allowed, "g.goods_id") mustBe "g.goods_id"
      Paging.sort(None, allowed, "g.goods_id") mustBe "g.goods_id"
    }
  }

  "the body helper" must {
    "require, bound and reject fields per the contract" in {
      val body = Body(Json.obj("quantity" -> 3, "tag" -> "  cpp  ", "note" -> ""))
      body.long("quantity", min = 1, max = 999) mustBe Right(3L)
      body.long("missing", min = 1).left.map(_.field) mustBe Left("missing")
      body.long("quantity", max = 2).isLeft mustBe true
      body.str("tag", maxBytes = 10) mustBe Right("  cpp  ")
      body.str("tag", allowEmpty = false).isRight mustBe true
      body.str("note", allowEmpty = false).isLeft mustBe true
      body.rejectUnknown(Set("quantity", "tag", "note")) mustBe None
      body.rejectUnknown(Set("quantity")).map(_.field) mustBe Some("tag")
    }

    "read money only as a decimal string" in {
      Body(Json.obj("amount" -> "10.00")).cents("amount") mustBe Right(1000L)
      Body(Json.obj("amount" -> 10)).cents("amount").isLeft mustBe true
      Body(Json.obj("amount" -> "0.00")).cents("amount").isLeft mustBe true
    }

    "accept id lists only when they are integers, bounded and duplicate free" in {
      Body(Json.obj("ids" -> Json.arr(1, 2))).ids("ids") mustBe Right(List(1L, 2L))
      Body(Json.obj("ids" -> Json.arr(1, 1))).ids("ids").isLeft mustBe true
      Body(Json.obj("ids" -> Json.arr(1, "x"))).ids("ids").isLeft mustBe true
      Body(Json.obj("ids" -> Json.arr())).ids("ids").isLeft mustBe true
      Body(Json.obj()).optIds("ids") mustBe Right(Nil)
    }
  }

  "crypto helpers" must {
    "verify a password only against its own hash" in {
      val stored = Crypto.hashPassword("not-a-real-password")
      stored.startsWith("pbkdf2$sha256$") mustBe true
      Crypto.verifyPassword("not-a-real-password", stored) mustBe true
      Crypto.verifyPassword("wrong", stored) mustBe false
      Crypto.verifyPassword("wrong", "not-a-hash-format") mustBe false
    }

    "make the callback signature depend on the exact bytes" in {
      val payload = """{"order_sn":"EC1","amount":"10.00"}"""
      Crypto.hmacSha256Hex("secret", payload) mustBe
        Crypto.hmacSha256Hex("secret", payload)
      Crypto.hmacSha256Hex("secret", payload) must not equal Crypto.hmacSha256Hex("other", payload)
      Crypto.hmacSha256Hex("secret", payload + " ") must not equal Crypto.hmacSha256Hex("secret", payload)
      Crypto.constantTimeEquals(Crypto.hmacSha256Hex("s", "a"), Crypto.hmacSha256Hex("s", "a")) mustBe true
      Crypto.constantTimeEquals("short", "much-longer-value") mustBe false
    }

    "keep tokens opaque and unique" in {
      val tokens = (1 to 200).map(_ => Crypto.newToken()).toSet
      tokens.size mustBe 200
      tokens.forall(t => t.length == 64 && t.forall(c => "0123456789abcdef".contains(c))) mustBe true
    }

    "sign cart attributes deterministically but fingerprint field order independently" in {
      Crypto.attrSignature(Seq(3L, 1L, 2L)) mustBe Crypto.attrSignature(Seq(1L, 2L, 3L))
      Crypto.attrSignature(Seq(1L)) must not equal Crypto.attrSignature(Seq(2L))
      val a = Crypto.fingerprint("k" -> "v", "j" -> "w")
      val b = Crypto.fingerprint("j" -> "w", "k" -> "v")
      a mustBe b
      a must not equal Crypto.fingerprint("k" -> "v", "j" -> "x")
      Crypto.fingerprint("a" -> "1", "b" -> "2") must
        not equal Crypto.fingerprint("a" -> "1,b=2")
    }
  }

  "e-mail addresses" must {
    "accept real addresses and reject the shapes that used to leak through" in {
      EmailAddress.valid("alice@example.test") mustBe true
      EmailAddress.valid("no-at-sign") mustBe false
      EmailAddress.valid("a@b") mustBe false
      EmailAddress.valid("@example.test") mustBe false
      EmailAddress.valid("a" * 120 + "@example.test") mustBe false
    }
  }

  "an Abort" must {
    "carry the failure back out of a rolled-back transaction" in {
      Abort.guarded[Int, String](throw Abort(42)) mustBe Left(42)
      Abort.guarded[Int, String](Right("fine")) mustBe Right("fine")
      Abort.guarded[Int, String](Left(7)) mustBe Left(7)
    }
  }
