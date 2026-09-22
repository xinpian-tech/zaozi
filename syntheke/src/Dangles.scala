package me.jiuyang.syntheke

sealed trait Dangles[A]:
  private[syntheke] def place(value: A, placement: DesignPlacement): A

object Dangles:
  given inward[P <: Protocol]: Dangles[InwardPort[P]] with
    private[syntheke] def place(value: InwardPort[P], placement: DesignPlacement): InwardPort[P] = placement.inward(value)

  given outward[P <: Protocol]: Dangles[OutwardPort[P]] with
    private[syntheke] def place(value: OutwardPort[P], placement: DesignPlacement): OutwardPort[P] = placement.outward(value)

  given domain[D <: Domain]: Dangles[DomainHandle[D]] with
    private[syntheke] def place(value: DomainHandle[D], placement: DesignPlacement): DomainHandle[D] = placement.handle(value)

  given probe[P]: Dangles[ProbeNode[P]] with
    private[syntheke] def place(value: ProbeNode[P], placement: DesignPlacement): ProbeNode[P] = placement.probe(value)

  given inwardBoundary[P <: Protocol]: Dangles[InwardBoundary[P]] with
    private[syntheke] def place(value: InwardBoundary[P], placement: DesignPlacement): InwardBoundary[P] = placement.inwardBoundary(value)

  given outwardBoundary[P <: Protocol]: Dangles[OutwardBoundary[P]] with
    private[syntheke] def place(value: OutwardBoundary[P], placement: DesignPlacement): OutwardBoundary[P] = placement.outwardBoundary(value)

  given option[A](using element: Dangles[A]): Dangles[Option[A]] with
    private[syntheke] def place(value: Option[A], placement: DesignPlacement): Option[A] = value.map(element.place(_, placement))

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
