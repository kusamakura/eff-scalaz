package org.atnos.eff

import scala.util.control.NonFatal
import scalaz._, Scalaz._
import Eff._
import Interpret._
import org.atnos.eff.EvalEffect.Eval
import scala.reflect.ClassTag

/**
 * Effect for computation which can fail and return a Throwable, or just stop with a failure
 *
 * This effect is a mix of Eval and \/ in the sense that every computation passed to this effect (with the ok
 * method) is considered "impure" or "faulty" by default.
 *
 * The type F is used to represent the failure type.
 *
 */
trait ErrorEffect[F] extends
  ErrorCreation[F] with
  ErrorInterpretation[F]

trait ErrorTypes[F] {

  /** type of errors: exceptions or failure messages */
  type Error = Throwable \/ F

  /**
   * base type for this effect: either an error or a computation to evaluate
   * scala.Name represents "by-name" value: values not yet evaluated
   */
  type ErrorOrOk[A] = Error \/ Eval[A]
}

trait ErrorCreation[F] extends ErrorTypes[F] {
  /** create an Eff value from a computation */
  def ok[R, A](a: => A)(implicit m: ErrorOrOk <= R): Eff[R, A] =
    send[ErrorOrOk, R, A](\/.right(Need(a)))

  /** create an Eff value from a computation */
  def eval[R, A](a: Eval[A])(implicit m: ErrorOrOk <= R): Eff[R, A] =
    send[ErrorOrOk, R, A](\/.right(a))

  /** create an Eff value from an error */
  def error[R, A](error: Error)(implicit m: ErrorOrOk <= R): Eff[R, A] =
    send[ErrorOrOk, R, A](\/.left(error))

  /** create an Eff value from a failure */
  def fail[R, A](failure: F)(implicit m: ErrorOrOk <= R): Eff[R, A] =
    error(\/.right(failure))

  /** create an Eff value from an exception */
  def exception[R, A](t: Throwable)(implicit m: ErrorOrOk <= R): Eff[R, A] =
    error(\/.left(t))
}

trait ErrorInterpretation[F] extends ErrorCreation[F] {
  outer =>

  /**
   * Run an error effect.
   *
   * Stop all computation if there is an exception or a failure.
   */
  def runError[R <: Effects, U <: Effects, A](r: Eff[R, A])(implicit m: Member.Aux[ErrorOrOk, R, U]): Eff[U, Error \/ A] = {
    val recurse = new Recurse[ErrorOrOk, U, Error \/ A] {
      def apply[X](m: ErrorOrOk[X]) =
        m match {
          case -\/(e) =>
            \/.right(EffMonad[U].point(\/.left(e)))

          case \/-(a) =>
            try \/.left(a.value)
            catch {
              case NonFatal(t) => \/.right(EffMonad[U].point(\/.left(\/.left(t))))
            }
        }
    }

    interpret1[R, U, ErrorOrOk, A, Error \/ A]((a: A) => \/-(a): Error \/ A)(recurse)(r)
  }

  /**
   * evaluate 1 action possibly having error effects
   *
   * Execute a second action whether the first is successful or not
   */
  def andFinally[R <: Effects, A](action: Eff[R, A], last: Eff[R, Unit])(implicit m: ErrorOrOk <= R): Eff[R, A] = {
    val recurse = new Recurse[ErrorOrOk, R, A] {
      def apply[X](current: ErrorOrOk[X]): X \/ Eff[R, A] =
        current match {
          case -\/(e) => \/.right(last.flatMap(_ => outer.error[R, A](e)))
          case \/-(x) =>
            try \/.left(x.value)
            catch {
              case NonFatal(t) => \/.right(last.flatMap(_ => outer.exception[R, A](t)))
            }
        }
    }
    intercept[R, ErrorOrOk, A, A]((a: A) => last.as(a), recurse)(action)
  }

  /**
   * evaluate 1 action possibly having error effects
   *
   * Execute a second action if the first one is not successful
   */
  def orElse[R <: Effects, A](action: Eff[R, A], onError: Eff[R, A])(implicit m: ErrorOrOk <= R): Eff[R, A] =
    whenFailed(action, _ => onError)

  /**
   * evaluate 1 action possibly having error effects
   *
   * Execute a second action if the first one is not successful, based on the error
   */
  def catchError[R <: Effects, A, B](action: Eff[R, A], pure: A => B, onError: Error => Eff[R, B])(implicit m: ErrorOrOk <= R): Eff[R, B] = {
    val recurse = new Recurse[ErrorOrOk, R, B] {
      def apply[X](current: ErrorOrOk[X]): X \/ Eff[R, B] =
        current match {
          case -\/(e) => \/-(onError(e))
          case \/-(x) =>
            try -\/[X](x.value)
            catch {
              case NonFatal(t) => \/-(onError(-\/(t)))
            }
        }
    }
    intercept1[R, ErrorOrOk, A, B](pure)(recurse)(action)
  }

  /**
   * evaluate 1 action possibly having error effects
   *
   * Execute a second action if the first one is not successful, based on the error
   *
   * The final value type is the same as the original type
   */
  def whenFailed[R <: Effects, A](action: Eff[R, A], onError: Error => Eff[R, A])(implicit m: ErrorOrOk <= R): Eff[R, A] =
    catchError(action, identity[A], onError)

  /**
   * ignore one possible exception that could be thrown
   */
  def ignoreException[R <: Effects, E <: Throwable : ClassTag, A](action: Eff[R, A])(implicit m: ErrorOrOk <= R): Eff[R, Unit] =
    catchError[R, A, Unit](action, (a: A) => (), { error: Error =>
      error match {
        case -\/(t) if implicitly[ClassTag[E]].runtimeClass.isInstance(t) =>
          EffMonad[R].point(())
        case other => outer.error(other)
      }
    })
}


/**
 * Simple instantiation of the ErrorEffect trait with String as a Failure type
 */
object ErrorEffect extends ErrorEffect[String] {

  def render(t: Throwable): String =
    s"Error[${t.getClass.getName}]" + (Option(t.getMessage) match {
      case None          => ""
      case Some(message) => s" $message"
    })

  def renderWithStack(t: Throwable): String =
    s"""============================================================
        |${render(t)}
        |------------------------------------------------------------
        |${traceWithIndent(t, "    ")}
        |============================================================
        |""".stripMargin

  def trace(t: Throwable): String =  {
    val out = new java.io.StringWriter
    t.printStackTrace(new java.io.PrintWriter(out))
    out.toString
  }

  def traceWithIndent(t: Throwable, indent: String): String =
    trace(t).lines.map(line => indent + line).mkString("\n")
}
