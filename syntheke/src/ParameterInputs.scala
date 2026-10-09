package me.jiuyang.syntheke

import scala.annotation.publicInBinary
import scala.compiletime.{erasedValue, error}

private[syntheke] trait ParameterValue:
  type Value

private[syntheke] final class ReadPlan(val tokens: Vector[ReadToken])

private[syntheke] object ReadPlan:
  def apply(tokens: ReadToken*): ReadPlan = new ReadPlan(tokens.toVector.distinct)

private[syntheke] object ParameterInputs:
  type TypedValue[A] = ParameterValue { type Value = A }
  type Values[S] = S match
    case TypedValue[a] => a
    case scala.collection.Seq[s] => Vector[Values[s]]
    case Tuple => TupleValues[S & Tuple]

  type TupleValues[S <: Tuple] <: Tuple = S match
    case EmptyTuple => EmptyTuple
    case head *: tail => Values[head] *: TupleValues[tail]

  inline def validate[S](inline outward: Boolean): Unit =
    inline erasedValue[S] match
      case _: (InwardNodeDraft[?] | InwardPort[?]) =>
        inline if !outward then error("an inward node derives Up parameters from outward nodes, not inward nodes")
      case _: (OutwardNodeDraft[?] | OutwardPort[?]) =>
        inline if outward then error("an outward node derives Down parameters from inward nodes, not outward nodes")
      case _: scala.collection.Seq[s] => validate[s](outward)
      case _: EmptyTuple => ()
      case _: (head *: tail) =>
        validate[head](outward)
        validate[tail](outward)
      case _ => error("derive expects opposite-direction node declarations, or tuples/sequences of them")

  private final case class Inputs(tokens: Vector[ReadToken], read: ReadValues => Any)

  private def inputs(source: Any): Inputs =
    def atom(token: ReadToken): Inputs = Inputs(Vector(token), values => values.lookup(token))
    def aggregate(sources: Vector[Any], tuple: Boolean): Inputs =
      val children = sources.map(inputs)
      Inputs(children.flatMap(_.tokens), values =>
        val result = children.map(_.read(values))
        if tuple then Tuple.fromArray(result.toArray) else result)
    source match
      case node: (InwardNodeDraft[?] | InwardPort[?]) => atom(new DownReader[node.protocol.Down](node.id))
      case node: (OutwardNodeDraft[?] | OutwardPort[?]) => atom(new UpReader[node.protocol.Up](node.id))
      case sources: scala.collection.Seq[?] => aggregate(sources.toVector, false)
      case sources: Tuple => aggregate(sources.productIterator.toVector, true)
      case _ => throw IllegalArgumentException("invalid parameter dependency source")

  @publicInBinary private[syntheke] def inward[P <: Protocol, S](
    node: InwardNodeDraft[P], sources: S,
    compute: Values[S] => Either[Violation, Any]
  ): InwardPort[P] =
    val prepared = inputs(sources)
    node.scope.seal(node, ReadPlan(prepared.tokens*), values => compute(prepared.read(values).asInstanceOf[Values[S]]))

  @publicInBinary private[syntheke] def outward[P <: Protocol, S](
    node: OutwardNodeDraft[P], sources: S,
    compute: Values[S] => Either[Violation, Any]
  ): OutwardPort[P] =
    val prepared = inputs(sources)
    node.scope.seal(node, ReadPlan(prepared.tokens*), values => compute(prepared.read(values).asInstanceOf[Values[S]]))
