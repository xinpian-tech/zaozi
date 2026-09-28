package me.jiuyang.syntheke

sealed trait Dangles[A]

object Dangles:
  private val evidence = new Dangles[Any] {}
  private def of[A]: Dangles[A] = evidence.asInstanceOf[Dangles[A]]

  given inward[P <: Protocol]:   Dangles[InwardPort[P]]   = of
  given outward[P <: Protocol]:  Dangles[OutwardPort[P]]  = of
  given domain[D <: Domain]: Dangles[DomainHandle[D]] = of
  given probe[P]:                Dangles[ProbeNode[P]]    = of
  given option[A](
    using Dangles[A]
  ): Dangles[Option[A]] = of
  given vector[A](
    using Dangles[A]
  ): Dangles[Vector[A]] = of
  inline given product[A <: Product](
    using m: scala.deriving.Mirror.ProductOf[A]
  ): Dangles[A] =
    allDangles[m.MirroredElemTypes]
    of

  private inline def allDangles[T <: Tuple]: Unit =
    import scala.compiletime.{erasedValue, summonInline}
    inline erasedValue[T] match
      case _: EmptyTuple => ()
      case _: (h *: t)   =>
        summonInline[Dangles[h]]
        allDangles[t]
