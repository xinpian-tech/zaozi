// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke

/** Protocol-reported conflict description, returned as a value from `negotiate` / `resolve`. */
final case class TermViolation(message: String)

/** A design protocol: the negotiation contract of one edge (doc @sec-protocol-object).
  *
  * `Down`, `Up` and `Edge` are associated to the same protocol value; the source and target node of a bind use the same
  * protocol object, so parameters and the `negotiate` call on that edge share one set of types.
  */
trait Protocol:
  type Down
  type Up
  type Edge

  /** Handle types of this protocol object — `Axi4.Inward` reads naturally where the underlying singleton-typed builder
    * (`InwardNodeBuilder[Axi4.type]`) would be spelled.
    */
  type Node    = NodeBuilder[this.type]
  type Inward  = InwardNodeBuilder[this.type]
  type Outward = OutwardNodeBuilder[this.type]
  type Ref     = RefHandle[this.type]

  /** Per-edge settlement: combine the propagated Down and Up into the final edge parameter. */
  def negotiate(down: Down, up: Up): Either[TermViolation, Edge]

  /** Hardware interface of a settled edge; drives dangle-port planning and generator port checking. */
  def interfaceOf(edge: Edge): ProtocolBundle

  /** Canonical serialization of the three parameter types (upickle). */
  def downRW: upickle.default.ReadWriter[Down]
  def upRW:   upickle.default.ReadWriter[Up]
  def edgeRW: upickle.default.ReadWriter[Edge]

/** A verification protocol: one probe sink aggregates all its probe sources (doc @sec-dv-protocol). */
trait DVProtocol:
  type Down
  type Edge

  /** Handle types of this protocol object, mirroring [[Protocol.Inward]] / [[Protocol.Outward]]. */
  type Source = DVSourceRef[this.type]
  type Sink   = DVSinkRef[this.type]

  /** Aggregate the source `Down`s (in bind declaration order) into the sink's `Edge`. */
  def resolve(downs: Vector[Down]): Either[TermViolation, Edge]

  /** Interfaces for each source, the aggregated sink interface, and the per-source sink paths. `layers(i)` is the layer
    * path declared by source `i`; every Probe in `sources(i)` and in the sink subtree selected by `sinkPaths(i)` must
    * carry it.
    */
  def interfacesOf(edge: Edge, layers: Vector[LayerPath]): Either[TermViolation, DVInterfaces]

  /** Canonical serialization of the two parameter types (upickle). */
  def downRW: upickle.default.ReadWriter[Down]
  def edgeRW: upickle.default.ReadWriter[Edge]
