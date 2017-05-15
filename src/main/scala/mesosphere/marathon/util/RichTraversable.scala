package mesosphere.marathon
package util

import scala.reflect.ClassTag

object RichTraversable {

  implicit class Helper[T](val seq: Traversable[T]) extends AnyVal {
    private def total[A, B](pf: PartialFunction[A, B], otherwise: B): A => B = {
      case t if pf.isDefinedAt(t) => pf(t)
      case _ => otherwise
    }

    def existsPF(pf: PartialFunction[T, Boolean]): Boolean = seq.exists(total(pf, otherwise = false))

    def findPF(pf: PartialFunction[T, Boolean]): Option[T] = seq.find(total(pf, otherwise = false))

    def filterPF(pf: PartialFunction[T, Boolean]): Traversable[T] = seq.filter(total(pf, otherwise = false))

    def existsAn[E](implicit tag: ClassTag[E]): Boolean = seq.exists(tag.runtimeClass.isInstance)
  }

}
