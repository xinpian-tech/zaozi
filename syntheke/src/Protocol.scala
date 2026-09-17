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

  val carries: Set[Domain]

  def negotiate(down: Down, up: Up, domains: EdgeDomains): Either[Violation, (Edge, Vector[Constraint])]

  def interface(edge: Edge): ProtocolInterface.Bundle

  def downWriter: upickle.default.Writer[Down]
  def upWriter:   upickle.default.Writer[Up]
  def edgeWriter: upickle.default.Writer[Edge]
