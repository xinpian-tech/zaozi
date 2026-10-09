package me.jiuyang.syntheke

/** What a design hands out: its boundaries, which the instantiating design moves to the instance, alone or in
  * vectors and case classes of them. Ports, probes and domains leave a design only through boundaries.
  */
sealed trait Dangles[A]:
  private[syntheke] def place(value: A, placement: DesignPlacement): A

object Dangles:
  given inwardBoundary[P <: Protocol]: Dangles[InwardBoundary[P]] with
    private[syntheke] def place(value: InwardBoundary[P], placement: DesignPlacement): InwardBoundary[P] = placement.inwardBoundary(value)

  given outwardBoundary[P <: Protocol]: Dangles[OutwardBoundary[P]] with
    private[syntheke] def place(value: OutwardBoundary[P], placement: DesignPlacement): OutwardBoundary[P] = placement.outwardBoundary(value)

  given vector[A](using element: Dangles[A]): Dangles[Vector[A]] with
    private[syntheke] def place(value: Vector[A], placement: DesignPlacement): Vector[A] = value.map(element.place(_, placement))

  inline given product[A <: Product](using m: scala.deriving.Mirror.ProductOf[A]): Dangles[A] =
    productEvidence(m, elements[m.MirroredElemTypes])

  private def productEvidence[A <: Product](m: scala.deriving.Mirror.ProductOf[A], fields: List[Dangles[?]]): Dangles[A] =
    new Dangles[A]:
      private[syntheke] def place(value: A, placement: DesignPlacement): A =
        val values = fields.zip(value.productIterator.toList).map { (field, item) =>
          field.asInstanceOf[Dangles[Any]].place(item, placement)
        }
        m.fromProduct(Tuple.fromArray(values.toArray))

  private inline def elements[T <: Tuple]: List[Dangles[?]] =
    import scala.compiletime.{erasedValue, summonInline}
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (h *: t) => summonInline[Dangles[h]] :: elements[t]
