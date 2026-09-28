package me.jiuyang.syntheke.zaozi

import scala.compiletime.{erasedValue, summonInline}
import scala.deriving.Mirror
import me.jiuyang.zaozi.valuetpe.{CanProbe, Data}

trait ProbeShape[A]:
  def handles(value: A): Vector[Probe[?]]

object ProbeShape:
  given ProbeShape[String] with
    def handles(value: String): Vector[Probe[?]] = Vector.empty

  given ProbeShape[Int] with
    def handles(value: Int): Vector[Probe[?]] = Vector.empty

  given [T <: Data & CanProbe]: ProbeShape[Probe[T]] with
    def handles(value: Probe[T]): Vector[Probe[?]] = Vector(value)

  given [A: ProbeShape]: ProbeShape[Option[A]] with
    def handles(value: Option[A]): Vector[Probe[?]] = value.toVector.flatMap(summon[ProbeShape[A]].handles)

  given [A: ProbeShape]: ProbeShape[Vector[A]] with
    def handles(value: Vector[A]): Vector[Probe[?]] = value.flatMap(summon[ProbeShape[A]].handles)

  private inline def members[T <: Tuple]: List[ProbeShape[?]] = inline erasedValue[T] match
    case _: EmptyTuple => Nil
    case _: (a *: tail) => summonInline[ProbeShape[a]] :: members[tail]

  inline given derived[A](using mirror: Mirror.ProductOf[A]): ProbeShape[A] =
    val fields = members[mirror.MirroredElemTypes]
    new ProbeShape[A]:
      def handles(value: A): Vector[Probe[?]] =
        fields.zip(value.asInstanceOf[Product].productIterator.toList).toVector.flatMap { (field, element) =>
          field.asInstanceOf[ProbeShape[Any]].handles(element)
        }
