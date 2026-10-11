package ecs

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

import play.api.libs.json.Json

/**
 * Password and token crypto.
 *
 * Passwords use PBKDF2-HMAC-SHA256 with a per-user random salt and are stored as
 * `pbkdf2$sha256$<iterations>$<salt-b64>$<hash-b64>`. Bearer tokens are opaque
 * random strings; only their SHA-256 hex digest is persisted (docs/03_api_contract.md).
 */
object Crypto:
  private val random = new SecureRandom()
  private val Iterations = 120000
  private val KeyBits = 256
  private val SaltBytes = 16
  private val TokenBytes = 32

  private def pbkdf2(password: String, salt: Array[Byte], iterations: Int): Array[Byte] =
    val spec = new PBEKeySpec(password.toCharArray, salt, iterations, KeyBits)
    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded

  def hashPassword(password: String): String =
    val salt = new Array[Byte](SaltBytes)
    random.nextBytes(salt)
    val b64 = Base64.getEncoder
    s"pbkdf2$$sha256$$${Iterations}$$${b64.encodeToString(salt)}$$${b64.encodeToString(pbkdf2(password, salt, Iterations))}"

  /** Constant-time verification; unknown formats never match. */
  def verifyPassword(password: String, stored: String): Boolean =
    stored.split("\\$") match
      case Array("pbkdf2", "sha256", iters, saltB64, hashB64) =>
        val salt = Base64.getDecoder.decode(saltB64)
        val expected = Base64.getDecoder.decode(hashB64)
        val actual = pbkdf2(password, salt, iters.toInt)
        MessageDigest.isEqual(expected, actual)
      case _ => false

  /** URL-safe opaque bearer token; the plaintext is returned to the client once. */
  def newToken(): String =
    val bytes = new Array[Byte](TokenBytes)
    random.nextBytes(bytes)
    bytes.map(b => f"${b & 0xff}%02x").mkString

  def sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8")).map(b => f"${b & 0xff}%02x").mkString

  /** Cryptographically random one-shot token used for e-mail flows. */
  def newEmailToken(): String = newToken()

  /** Hex HMAC-SHA256 of the raw callback body (docs/03_api_contract.md). */
  def hmacSha256Hex(secret: String, payload: String): String =
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    mac.doFinal(payload.getBytes("UTF-8")).map(b => f"${b & 0xff}%02x").mkString

  def constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.getBytes("UTF-8"), b.getBytes("UTF-8"))

  /** Deterministic cart attribute signature used by the cart unique key. */
  def attrSignature(attributeIds: Seq[Long]): String =
    sha256Hex(attributeIds.sorted.mkString(","))

  /** Request fingerprint that makes an idempotency-key replay detectable. */
  def fingerprint(parts: (String, String)*): String =
    Json.toJson(parts.toMap).toString
