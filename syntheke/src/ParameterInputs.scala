package me.jiuyang.syntheke

import scala.annotation.publicInBinary
import scala.compiletime.{erasedValue, error}

/** A node whose parameter a port parameter function can read: Down of an inward node, Up of an outward one. */
private[syntheke] trait ParameterValue:
  type Value

/** The sources a port parameter function reads: one node, or a sequence of them. */
private[syntheke] object ParameterInputs:
  type TypedValue[A] = ParameterValue { type Value = A }
  type Values[S] = S match
    case TypedValue[a]           => a
    case scala.collection.Seq[s] => Vector[Values[s]]

  inline def validate[S](inline outward: Boolean): Unit =
    inline erasedValue[S] match
      case _: (InwardNodeDraft[?] | InwardPort[?])   =>
        inline if !outward then error("an inward node derives Up parameters from outward nodes, not inward nodes")
      case _: (OutwardNodeDraft[?] | OutwardPort[?]) =>
        inline if outward then error("an outward node derives Down parameters from inward nodes, not outward nodes")
      case _: scala.collection.Seq[s]                => validate[s](outward)
      case _                                         => error("derive expects opposite nodes, or a sequence of them")

  private def reader(source: Any): (Vector[ModuleNodeId], Map[ModuleNodeId, Any] => Any) = source match
    case node: NodeHandle[?]           => (Vector(node.id), _(node.id))
    case sources: scala.collection.Seq[?] =>
      val parts = sources.toVector.map(reader)
      (parts.flatMap(_._1), values => parts.map(_._2(values)))
    case _                             => throw IllegalArgumentException("invalid parameter source")

  @publicInBinary private[syntheke] def derived[S](sources: S, compute: Values[S] => Either[Violation, Any])
    : NodeComputation =
    val (reads, read) = reader(sources)
    NodeComputation.Derived(reads.distinct, values => compute(read(values).asInstanceOf[Values[S]]))
