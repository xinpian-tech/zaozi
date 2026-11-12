package me.jiuyang.syntheke

import scala.collection.immutable.VectorMap

import scala.annotation.publicInBinary
import scala.compiletime.{erasedValue, error}

/** A node whose parameter a port parameter function can read: Down of an inward node, Up of an outward one. */
private[syntheke] trait ParameterValue:
  type Value

/** The sources a port parameter function reads: opposite nodes, whose parameters it gets, alone or in sequences and
  * tuples of them.
  */
private[syntheke] object ParameterInputs:
  type TypedValue[A] = ParameterValue { type Value = A }
  type Values[S]     = S match
    case TypedValue[a]           => a
    case EmptyTuple              => EmptyTuple
    case h *: t                  => Values[h] *: Values[t]
    case scala.collection.Seq[s] => Vector[Values[s]]

  inline def validate[S](inline outward: Boolean): Unit =
    inline erasedValue[S] match
      case _: (InwardNodeDraft[?] | InwardPort[?])   =>
        inline if !outward then error("an inward node derives Up parameters from outward nodes, not inward nodes")
      case _: (OutwardNodeDraft[?] | OutwardPort[?]) =>
        inline if outward then error("an outward node derives Down parameters from inward nodes, not outward nodes")
      case _: EmptyTuple                             => ()
      case _: (h *: t)                               =>
        validate[h](outward)
        validate[t](outward)
      case _: scala.collection.Seq[s]                => validate[s](outward)
      case _ =>
        error("derive expects opposite nodes, or sequences and tuples of them")

  private type Read = VectorMap[ModuleNodeId, Any] => Any

  private def reader(source: Any): (Vector[ModuleNodeId], Read) = source match
    case node:    NodeHandle[?]           => (Vector(node.id), values => values(node.id))
    case sources: Tuple                   =>
      val parts = sources.toList.map(reader)
      (parts.toVector.flatMap(_._1), values => Tuple.fromArray(parts.map(_._2(values)).toArray))
    case sources: scala.collection.Seq[?] =>
      val parts = sources.toVector.map(reader)
      (parts.flatMap(_._1), values => parts.map(_._2(values)))

  @publicInBinary private[syntheke] def derived[S](sources: S, compute: Values[S] => Either[Violation, Any])
    : NodeComputation =
    val (reads, read) = reader(sources)
    NodeComputation(
      reads.distinct,
      values => compute(read(values).asInstanceOf[Values[S]])
    )
