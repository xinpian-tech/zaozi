// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.utlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

/** Transaction-level capture: the machinery that lets a constraint relate *different* transactions of one stimulus.
  *
  * A cross-transaction constraint ("all payloads distinct", "the sum of the three beats is K", "beat i+1 references
  * beat i's result") is a relation between values at different cycles. [[history]] materializes that time axis as
  * typed state — a reset-initialized shift register of the signal's past beats plus a saturating beat counter — so the
  * relation becomes a plain boolean predicate over `(hist(n), …, hist(0), sig)`, guarded by "enough beats seen", and
  * solves through the same `assert ¬C` reading as every other constraint. Reset-initialized on purpose: an
  * uninitialized history register would be a free variable the solver could fill with fake history.
  *
  * A beat is a clock cycle here; handshake-qualified beats (`valid && ready`) are the caller's own gating.
  */
object Txn:

  /** The standing model assumption of a sequential UT: the external reset stays low, so the bounded model tracks the
    * testbench's post-reset run (the testbench holds reset itself before the first tick).
    */
  def assumeResetLow(
    reset: Referable[Reset]
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Unit =
    Assume((!reset.asBool).I, "rst_low")

  /** High for exactly the first post-reset cycle. Feed it (OR'd with the external reset) to a wrapped IP's reset so
    * the IP resets *itself* with its RTL's true reset values, in the formal model and the replay alike: in the model
    * the register is init-pinned to 1 so cycle 0 is a reset cycle; on the testbench it stays 1 through the reset
    * preamble and one cycle beyond, then drops. Both time axes see reset low from cycle 1 on — so imported-register
    * init pinning no longer has to guess reset values (0 is fine, the RTL overwrites them at the cycle-0 edge).
    */
  def firstCycle(
  )(
    using ClockScope,
    ResetScope
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Referable[Bool] =
    val first = RegInit(true.B)
    first := false.B
    first

  /** The last `depth` beats of `sig` — `hist(0)` is one beat old, `hist(depth - 1)` the oldest — and a beat counter
    * saturating at `depth`, so `beats === depth` means the whole window is real history. [[window]] is the typed
    * public view.
    */
  private[utlib] def history(
    sig:   Referable[Bits],
    width: Int,
    depth: Int
  )(
    using ClockScope,
    ResetScope
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): (Seq[Referable[Bits]], Referable[UInt]) =
    require(depth > 0, "history depth must be positive")
    val hist = Seq.fill(depth)(RegInit(0.B(width)))
    hist.head      := sig
    hist.sliding(2).foreach {
      case Seq(newer, older) => older := newer
      case _                 => ()
    }

    val cw    = 32 - Integer.numberOfLeadingZeros(depth)
    val beats = RegInit(0.U(cw))
    val next  = (beats + 1.U(cw)).asBits.bits(cw - 1, 0).asUInt
    beats := ((beats === depth.U(cw)) ? (beats, next))
    (hist, beats)

  /** The typed view [[Sem.relation]] draws from: `past(1)` is the previous beat, `past(depth)` the oldest, and
    * `ready` is the reality guard — high once every slot holds a real beat.
    *
    * The declared depth is a *type*: `past(i)` on a literal index outside `[1, D]` fails to compile.
    */
  final class TxnWindow[D <: Int] private[utlib] (
    private[utlib] val slots:    Seq[Referable[Bits]],
    val depth:                   Int,
    private[utlib] val readyRef: Referable[Bool]):
    inline def past(inline i: Int): Referable[Bits] =
      inline if i < 1 then scala.compiletime.error("past(i) starts at 1 — the previous beat")
      else
        inline if i > scala.compiletime.constValue[D] then
          scala.compiletime.error("past(i) reaches beyond the window's declared depth")
        else pastUnchecked(i)

    /** The runtime-checked accessor, for indices not known at compile time. */
    def pastUnchecked(i: Int): Referable[Bits] =
      require(i >= 1 && i <= depth, s"past($i) is outside the declared window depth $depth")
      slots(i - 1)

    def ready: Referable[Bool] = readyRef

  /** Declare a `depth`-beat window over `sig` — the capture machinery of [[history]] behind the typed accessor. The
    * literal depth becomes the window's type parameter.
    */
  def window[D <: Int & Singleton](
    sig:   Referable[Bits],
    width: Int,
    depth: D
  )(
    using ClockScope,
    ResetScope
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): TxnWindow[D] =
    val (hist, beats) = history(sig, width, depth)
    val cw            = 32 - Integer.numberOfLeadingZeros(depth)
    new TxnWindow[D](hist, depth, beats === depth.U(cw))
