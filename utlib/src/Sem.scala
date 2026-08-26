// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.utlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

/** Typed verification semantics: the four kinds of meaning a constraint can carry, as distinct types that compose.
  *
  *   - **Value** (值语义): a predicate over one object's fields — ranges, equalities, bit predicates.
  *   - **Relation** (关系语义): a predicate relating *different* verification objects — here, different beats of a
  *     stream, drawn from a declared [[Txn.TxnWindow]]. The intent node keeps the window, and the lowering conjoins
  *     its reality guard (`ready`): a relation can never fire on fake history.
  *   - **Temporal** (时序语义): ordering and dependency between events, as a clocked SVA [[Sequence]] — constructing
  *     one already demands a `ClockEvent`, so an unclocked temporal constraint is a compile error.
  *   - **State** (状态语义): a predicate tying the verification condition to *design state* — an observation of the
  *     DUT's outputs, probes, or status registers, rather than of the driven inputs.
  *
  * Kinds compose by conjunction (`&&`) into an [[Sem.Intent]], and [[Generate]] lowers the whole intent to the
  * `assert ¬C` reading: the solver's violation trace is a stimulus satisfying every conjunct. The composition rule is
  * fire-cycle semantics — all immediate kinds (value, relation, state) are evaluated at the cycle the scenario fires,
  * and each temporal sequence matches forward from that cycle.
  */
object Sem:

  /** The fire-cycle kinds — semantically distinct roles that share one lowering (a predicate at the fire cycle). */
  enum Kind:
    case Value, State

  sealed trait Intent:
    infix def &&(that: Intent): Intent = Intent.And(this, that)

  object Intent:
    final case class Immediate(kind: Kind, cond: Referable[Bool])           extends Intent
    final case class Relation(window: Txn.TxnWindow, cond: Referable[Bool]) extends Intent
    final case class Temporal(seq: Sequence, clock: ClockEvent)             extends Intent
    final case class And(l: Intent, r: Intent)                              extends Intent

  /** 值语义 — a predicate over the driven object's fields at the fire cycle. */
  def value(cond: Referable[Bool]): Intent = Intent.Immediate(Kind.Value, cond)

  /** 关系语义 — a predicate over different beats of the stream, drawn from `window`. The window rides along in the
    * intent node; the lowering conjoins its `ready` guard, so the relation only fires once the whole window is real
    * history.
    */
  def relation(window: Txn.TxnWindow)(cond: Txn.TxnWindow => Referable[Bool]): Intent =
    Intent.Relation(window, cond(window))

  /** 状态语义 — a predicate over an observation of design state (outputs, probes, status registers). */
  def state(cond: Referable[Bool]): Intent = Intent.Immediate(Kind.State, cond)

  /** 时序语义 — a clocked sequence matching forward from the fire cycle. The clock is captured here so the lowering
    * can lift any composed immediate kinds onto the same clock.
    */
  def temporal(seq: Sequence)(using clock: ClockEvent): Intent = Intent.Temporal(seq, clock)

  private[utlib] def flatten(intent: Intent): (Seq[Intent.Immediate | Intent.Relation], Seq[Intent.Temporal]) =
    intent match
      case i: Intent.Immediate => (Seq(i), Seq.empty)
      case r: Intent.Relation  => (Seq(r), Seq.empty)
      case t: Intent.Temporal  => (Seq.empty, Seq(t))
      case Intent.And(l, r)    =>
        val (li, ls) = flatten(l)
        val (ri, rs) = flatten(r)
        (li ++ ri, ls ++ rs)

/** Lower a composed [[Sem.Intent]] to the generation reading: `assert ¬C` in the module body, so a circt-bmc
  * violation trace IS a stimulus satisfying the intent.
  */
object Generate:
  def apply(
    intent: Sem.Intent,
    label:  String
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Unit =
    val (immediates, temporals) = Sem.flatten(intent)
    require(immediates.nonEmpty || temporals.nonEmpty, "an intent needs at least one semantic")
    val imm                     = immediates
      .map {
        case Sem.Intent.Immediate(_, cond)     => cond
        case Sem.Intent.Relation(window, cond) => window.ready & cond
      }
      .reduceOption(_ & _)
    (imm, temporals) match
      case (Some(i), Nil) => Assert((!i).I, label)
      case (None, ts)     => Assert(!ts.map(_.seq).reduce(_ & _), label)
      case (Some(i), ts)  =>
        // Lift the immediate side onto the temporal clock, so the whole conjunction stays a clocked sequence.
        // (Several temporal conjuncts are expected to share one clock; the first one's is used for the lift.)
        given ClockEvent = ts.head.clock
        Assert(!(ts.map(_.seq).reduce(_ & _) & i.S), label)
