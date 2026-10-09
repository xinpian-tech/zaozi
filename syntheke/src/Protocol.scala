package me.jiuyang.syntheke

final case class Violation(message: String)

trait Protocol:
  type Down
  type Up
  type Edge

  type InwardDraft  = InwardNodeDraft[this.type]
  type OutwardDraft = OutwardNodeDraft[this.type]
  type Inward       = InwardPort[this.type]
  type Outward      = OutwardPort[this.type]

  /** Domain kinds the protocol's wires carry; the inward end of a bind is in the outward end's domain. */
  val carries: Set[DomainKind]

  /** What a bind may cross in every other domain kind its ends are in. A kind left out is an error. */
  def accepts: Seq[Accept]

  def negotiate(down: Down, up: Up): Either[Violation, Edge]

  def interface(edge: Edge): ProtocolInterface.Bundle

  def downWriter: upickle.default.Writer[Down]
  def upWriter:   upickle.default.Writer[Up]
  def edgeWriter: upickle.default.Writer[Edge]
