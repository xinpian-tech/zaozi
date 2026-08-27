// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke

/** Serialization schema plus canonical encode / decode for one parameter type. */
trait Codec[T]:
  def schema:                     ujson.Value
  def encode(value: T):           ujson.Value
  def decode(value: ujson.Value): Either[String, T]

object Codec:
  /** Codec from a upickle ReadWriter with a caller-supplied schema. */
  def fromReadWriter[T](
    schemaValue: ujson.Value
  )(
    using rw:    upickle.default.ReadWriter[T]
  ): Codec[T] =
    new Codec[T]:
      def schema                     = schemaValue
      def encode(value: T)           = upickle.default.writeJs(value)
      def decode(value: ujson.Value) =
        try Right(upickle.default.read[T](value))
        catch case e: Exception => Left(e.getMessage)

/** Protocol-reported conflict description, returned as a value from `negotiate` / `resolve`. */
final case class TermViolation(message: String)

/** Visualization output of `render`: a display label plus named attributes. Attribute names are unique and encoded
  * sorted by name (doc @sec-protocol-object).
  */
final case class RenderedValue(label: String, attributes: Map[String, String]):
  def encoded: ujson.Value = ujson.Obj(
    "label"      -> ujson.Str(label),
    "attributes" -> ujson.Obj.from(attributes.toVector.sortBy(_._1).map((k, v) => k -> ujson.Str(v)))
  )

/** A design protocol: the negotiation contract of one edge (doc @sec-protocol-object).
  *
  * `Down`, `Up` and `Edge` are associated to the same protocol value; the source and target node of a bind use the same
  * protocol object, so parameters and the `negotiate` call on that edge share one set of types.
  */
trait Protocol:
  type Down
  type Up
  type Edge

  /** Must have kind [[ProtocolKind.Design]]; checked by the structural pass. */
  def id: ProtocolId

  /** Per-edge settlement: combine the propagated Down and Up into the final edge parameter. */
  def negotiate(down: Down, up: Up): Either[TermViolation, Edge]

  /** Hardware interface of a settled edge; drives dangle-port planning and generator port checking. */
  def interfaceOf(edge: Edge): ProtocolBundle

  /** Visualization label and attributes for a settled edge. */
  def render(edge: Edge): RenderedValue

  def downCodec: Codec[Down]
  def upCodec:   Codec[Up]
  def edgeCodec: Codec[Edge]

/** A verification protocol: one probe sink aggregates all its probe sources (doc @sec-dv-protocol). */
trait DVProtocol:
  type Down
  type Edge

  /** Must have kind [[ProtocolKind.Verification]]; checked by the structural pass. */
  def id: ProtocolId

  /** Aggregate the source `Down`s (in bind declaration order) into the sink's `Edge`. */
  def resolve(downs: Vector[Down]): Either[TermViolation, Edge]

  /** Interfaces for each source, the aggregated sink interface, and the per-source sink paths. `layers(i)` is the layer
    * path declared by source `i`; every Probe in `sources(i)` and in the sink subtree selected by `sinkPaths(i)` must
    * carry it.
    */
  def interfacesOf(edge: Edge, layers: Vector[LayerPath]): Either[TermViolation, DVInterfaces]

  def render(edge: Edge): RenderedValue

  def downCodec: Codec[Down]
  def edgeCodec: Codec[Edge]
