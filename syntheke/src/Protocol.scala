// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke

/** Conflict description returned as a value — the expression channel of protocol and parameter functions (`negotiate`,
  * dFn / uFn, `parameters`). The framework throws on receipt.
  */
final case class Violation(message: String)

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
  def negotiate(down: Down, up: Up): Either[Violation, Edge]

  /** Hardware interface of a settled edge; drives dangle-port planning and generator port checking. */
  def interfaceOf(edge: Edge): ProtocolBundle

  /** Canonical serialization of the three parameter types (upickle). */
  def downRW: upickle.default.ReadWriter[Down]
  def upRW:   upickle.default.ReadWriter[Up]
  def edgeRW: upickle.default.ReadWriter[Edge]

/** A verification protocol: a probe source publishes read-only signals that the framework forwards to the root — into
  * the [[testbench]]'s matching inputs when one is declared, to top-level probe ports otherwise (doc
  * @sec-dv-protocol). There is no negotiation — the `Down` is given at the declaration and fully determines the
  * interface.
  */
trait DVProtocol:
  type Down

  /** Probe interface of one source: every interface leaf wrapped in `Probe` carrying `layer` (a probe's inner may be
    * an aggregate). Checked at the `dvSource` declaration.
    */
  def interfaceOf(down: Down, layer: LayerPath): ProtocolBundle

  /** Canonical serialization of the source parameter (upickle). */
  def downRW: upickle.default.ReadWriter[Down]
