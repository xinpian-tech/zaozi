// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke

/** Negotiation errors (doc @sec-error-semantics).
  *
  * Every error carries a category, the stable identifiers of its subjects, all relevant source locations, and parameter
  * snapshots where applicable. Negotiation fails fast: the first error found is thrown as a [[NegotiationException]] on
  * the spot.
  */
enum NegotiationError:
  /** N1: protocol identity or type inconsistency. */
  case ProtocolMismatch(
    detail:    String,
    protocols: Vector[ProtocolId],
    nodes:     Vector[ModuleNodeId],
    locs:      Vector[SourceLocation])

  /** N2: parameter propagation failure reported by a dFn or uFn. */
  case PropagationFailed(
    module:    ModuleId,
    node:      ModuleNodeId,
    direction: NodeDirection,
    deps:      Vector[ModuleNodeId],
    inputs:    Vector[ujson.Value],
    violation: PropagationViolation,
    locs:      Vector[SourceLocation])

  /** N3: per-edge `negotiate` or per-sink `resolve` failure. */
  case SettleFailed(
    subject:   SettleSubject,
    violation: TermViolation,
    locs:      Vector[SourceLocation])

  /** N4: illegal node or bind. */
  case IllegalBind(
    detail: String,
    nodes:  Vector[ModuleNodeId],
    binds:  Vector[BindId],
    locs:   Vector[SourceLocation])

  /** N5: generator capability check failure. */
  case CapabilityExceeded(
    module:    ModuleId,
    violation: CapabilityViolation,
    locs:      Vector[SourceLocation])

  /** N6: interface mapping violation (design ProtocolBundle or DVInterfaces contract). */
  case InterfaceViolation(
    detail:  String,
    subject: SettleSubject,
    locs:    Vector[SourceLocation])

  /** N7: cross-protocol reference failure. */
  case ReferenceFailed(
    detail:   String,
    referrer: ModuleNodeId,
    target:   ModuleNodeId,
    expected: ProtocolId,
    locs:     Vector[SourceLocation])

  /** N8: illegal verification topology. */
  case IllegalVerification(
    detail:  String,
    sources: Vector[DVSourceId],
    sinks:   Vector[DVSinkId],
    locs:    Vector[SourceLocation])

  /** N9: illegal structural name or parameter dependency (duplicates, bad endpoints, cycles). */
  case IllegalStructure(
    detail: String,
    ids:    Vector[String], // shown stable identifiers involved
    locs:   Vector[SourceLocation])

  /** N10: generator identity conflict. */
  case GeneratorConflict(
    generator: GeneratorId,
    locs:      Vector[SourceLocation])

  def category: Int = this match
    case _: ProtocolMismatch    => 1
    case _: PropagationFailed   => 2
    case _: SettleFailed        => 3
    case _: IllegalBind         => 4
    case _: CapabilityExceeded  => 5
    case _: InterfaceViolation  => 6
    case _: ReferenceFailed     => 7
    case _: IllegalVerification => 8
    case _: IllegalStructure    => 9
    case _: GeneratorConflict   => 10

  def show: String = this match
    case NegotiationError.ProtocolMismatch(d, ps, ns, locs)           =>
      s"N1 protocol mismatch: $d; protocols=${ps.map(_.show).mkString(",")} nodes=${ns.map(_.show).mkString(",")} at ${locs.map(_.show).mkString(",")}"
    case NegotiationError.PropagationFailed(m, n, dir, _, _, v, locs) =>
      s"N2 propagation failed at ${n.show} ($dir): ${v.message} at ${locs.map(_.show).mkString(",")}"
    case NegotiationError.SettleFailed(s, v, locs)                    =>
      s"N3 settle failed at ${s.show}: ${v.message} at ${locs.map(_.show).mkString(",")}"
    case NegotiationError.IllegalBind(d, ns, bs, locs)                =>
      s"N4 illegal bind: $d; nodes=${ns.map(_.show).mkString(",")} binds=${bs.map(_.show).mkString(",")} at ${locs.map(_.show).mkString(",")}"
    case NegotiationError.CapabilityExceeded(m, v, locs)              =>
      s"N5 capability exceeded at ${m.show}: ${v.message} at ${locs.map(_.show).mkString(",")}"
    case NegotiationError.InterfaceViolation(d, s, locs)              =>
      s"N6 interface violation at ${s.show}: $d at ${locs.map(_.show).mkString(",")}"
    case NegotiationError.ReferenceFailed(d, r, t, e, locs)           =>
      s"N7 reference failed: $d; ${r.show} -> ${t.show} expecting ${e.show} at ${locs.map(_.show).mkString(",")}"
    case NegotiationError.IllegalVerification(d, ss, ks, locs)        =>
      s"N8 illegal verification: $d; sources=${ss.map(_.show).mkString(",")} sinks=${ks.map(_.show).mkString(",")} at ${locs.map(_.show).mkString(",")}"
    case NegotiationError.IllegalStructure(d, ids, locs)              =>
      s"N9 illegal structure: $d; ids=${ids.mkString(",")} at ${locs.map(_.show).mkString(",")}"
    case NegotiationError.GeneratorConflict(g, locs)                  =>
      s"N10 generator conflict: ${g.show} at ${locs.map(_.show).mkString(",")}"

/** Thrown by [[Negotiator.negotiate]] at the first error found. */
final class NegotiationException(val error: NegotiationError) extends RuntimeException(error.show)

/** The subject of a settle-phase failure: a design bind or a probe sink. */
enum SettleSubject derives CanEqual:
  case Design(bind: BindId)
  case Verification(sink: DVSinkId)
  def show: String = this match
    case Design(b)       => b.show
    case Verification(s) => s.show
