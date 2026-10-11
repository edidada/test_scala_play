package ecs

/**
 * Rolls the surrounding `Db.inTransaction` back on a business failure.
 *
 * A multi-statement write (an order consuming stock, a merge moving snapshots)
 * cannot be aborted by returning `Left`, because `Left` is a normal value and the
 * transaction would still commit the statements that already ran. Repositories
 * therefore throw this with the failure inside; the controller catches it after the
 * transaction has been rolled back and maps `reason` onto an HTTP status.
 */
final case class Abort[+A](reason: A) extends RuntimeException(s"transaction aborted: $reason"):
  /** Keep the stack trace off; this is control flow, not a defect. */
  override def fillInStackTrace(): Throwable = this

object Abort:
  /** Run `body`, turning a thrown [[Abort]] back into a plain `Left`. */
  def guarded[A, B](body: => Either[A, B]): Either[A, B] =
    try body
    catch case abort: Abort[A @unchecked] => Left(abort.reason)
