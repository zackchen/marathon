package mesosphere.marathon

import com.wix.accord.Failure

trait Normalization[T] extends AnyRef {
  def normalized(t: T): Either[Failure, T]
  final def normalizedOrThrow(t: T): T = normalized(t) match {
    case Left(failure) =>
      throw new ValidationFailedException(t, failure)
    case Right(t) => t
  }

  final def apply(n: Normalization[T]): Normalization[T] = Normalization { t =>
    n.normalized(t).right.flatMap(normalized)
  }
}

object Normalization {

  implicit class Normalized[T](val a: T) extends AnyVal {
    def normalize(implicit f: Normalization[T]): Either[Failure, T] = f.normalized(a)
    def normalizeOrThrow(implicit f: Normalization[T]): T = f.normalizedOrThrow(a)
  }

  def apply[T](f: (T => Either[Failure, T])): Normalization[T] = new Normalization[T] {
    override def normalized(t: T): Either[Failure, T] = f(t)
  }
}
