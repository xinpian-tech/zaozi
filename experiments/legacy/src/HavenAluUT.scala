// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.stdlib

import me.jiuyang.utlib.{Generate, Probes, Sem, Txn, UT}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class HavenAluParameter() extends Parameter

given upickle.default.ReadWriter[HavenAluParameter] = upickle.default.macroRW

class HavenAluLayers(parameter: HavenAluParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

/** The HAVEN benchmark ALU's own port list (hdl/alu/rtl/alu_top.v): 32-bit ALU with FP add/sub, integer logic, shifts,
  * FP2INT; `done` pulses when a result lands.
  */
class HavenAluIO(parameter: HavenAluParameter) extends HWBundle(parameter):
  val clk    = Flipped(Clock())
  val rst_n  = Flipped(Bool())
  val a      = Flipped(Bits(32))
  val b      = Flipped(Bits(32))
  val op     = Flipped(Bits(4))
  val start  = Flipped(Bool())
  val result = Aligned(Bits(32))
  val flags  = Aligned(Bits(4))
  val done   = Aligned(Bool())

class HavenAluProbe(parameter: HavenAluParameter) extends DVBundle[HavenAluParameter, HavenAluLayers](parameter)

case class HavenAluVerilogParams() extends VerilogParameter

/** The first real HAVEN IP through the typed harness. The RTL is a vendored test resource
  * (`experiments/fixtures/haven/alu_top.v`), not inlined — the test hands it to circt-verilog and Verilator.
  */
@generator
object HavenAlu
    extends VerilogWrapper[HavenAluParameter, HavenAluLayers, HavenAluIO, HavenAluProbe, HavenAluVerilogParams]:
  def verilogModuleName(parameter: HavenAluParameter) = "alu_top"
  def verilogParameter(parameter:  HavenAluParameter) = HavenAluVerilogParams()

  // Parameterless wrapper: firld compares extmodule decls strictly, so the module name must be the plain
  // verilog name (see ExtAccum).
  override def moduleName(parameter: HavenAluParameter): String = verilogModuleName(parameter)

class HavenAluUTLayers(parameter: HavenAluParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

/** Model B drives one port: the two 32-bit operands travel packed as `A = b ## a` (64 bits exactly — the stimulus
  * codec's limit); the opcode is tied to XOR and `start` low, which also deactivates the FP cones for the solver.
  */
class HavenAluUTIO(parameter: HavenAluParameter) extends HWBundle(parameter):
  val clock  = Flipped(Clock())
  val reset  = Flipped(Reset())
  val A      = Flipped(Bits(64))
  val RESULT = Aligned(Bits(32))
  val DONE   = Aligned(Bool())

class HavenAluUTProbe(parameter: HavenAluParameter) extends DVBundle[HavenAluParameter, HavenAluUTLayers](parameter):
  val RESULT = ProbeRead(Bits(32), layers("Verification"))
  val DONE   = ProbeRead(Bits(1), layers("Verification"))

/** The real-IP formal-CRV example: C = "the ALU completes with result 0xBEEF" — solved through the benchmark's own XOR
  * datapath and done pipeline (the solver must schedule an input change and invert the XOR).
  */
@generator
object HavenAluUT
    extends Generator[HavenAluParameter, HavenAluUTLayers, HavenAluUTIO, HavenAluUTProbe]
    with UT[HavenAluParameter, HavenAluUTIO]:
  override def moduleName(p: HavenAluParameter): String = "HavenAluUT"

  def architecture(parameter: HavenAluParameter) =
    val io       = summon[Interface[HavenAluUTIO]]
    val instance = HavenAlu.instantiate(parameter)
    instance.io.clk   := io.clock
    instance.io.rst_n := !io.reset.asBool
    instance.io.a     := io.A.bits(31, 0)
    instance.io.b     := io.A.bits(63, 32)
    instance.io.op    := 4.U(4).asBits // OP_XOR
    instance.io.start := false.B
    io.RESULT         := instance.io.result
    io.DONE           := instance.io.done

    Txn.assumeResetLow(io.reset)

    // C as typed state semantics: the ALU's completion state — done with result 0xBEEF.
    Generate(Sem.state(instance.io.done & (instance.io.result === 48879.U(32).asBits)), "gen_alu_xor_beef")

    val probe = summon[ProbeInterface[HavenAluUTProbe]]
    layer("Verification"):
      Probes.expose(probe.RESULT, Bits(32), instance.io.result)
      Probes.expose(probe.DONE, Bits(1), instance.io.done.asBits)

/** Per-opcode directed generation: the parameter names the opcode this instance targets, so one intent per opcode
  * yields one witness, and the witnesses concatenate into a single UVM sequence.
  */
case class HavenAluOpParameter(targetOp: Int) extends Parameter:
  require(targetOp >= 0 && targetOp < 16, "opcode is 4 bits")

given upickle.default.ReadWriter[HavenAluOpParameter] = upickle.default.macroRW

class HavenAluOpLayers(parameter: HavenAluOpParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

/** Drive ports match the HAVEN transaction's fields by name (a, b, op, start), so the emitted sequence assigns straight
  * into its `seq_item` — no packing, because this stimulus goes to a UVM driver rather than the single-drive Model B
  * callback.
  */
class HavenAluOpUTIO(parameter: HavenAluOpParameter) extends HWBundle(parameter):
  val clock  = Flipped(Clock())
  val reset  = Flipped(Reset())
  val a      = Flipped(Bits(32))
  val b      = Flipped(Bits(32))
  val op     = Flipped(Bits(4))
  val start  = Flipped(Bool())
  val RESULT = Aligned(Bits(32))
  val DONE   = Aligned(Bool())

class HavenAluOpUTProbe(parameter: HavenAluOpParameter)
    extends DVBundle[HavenAluOpParameter, HavenAluOpLayers](parameter):
  val RESULT = ProbeRead(Bits(32), layers("Verification"))

/** C = "issue the parameterized opcode with start asserted". Deliberately simple: the point of the experiment is the
  * plumbing — a solved witness replacing the sequence layer of a template-generated testbench — not yet the cleverness
  * of the intent.
  */
@generator
object HavenAluOpUT
    extends Generator[HavenAluOpParameter, HavenAluOpLayers, HavenAluOpUTIO, HavenAluOpUTProbe]
    with UT[HavenAluOpParameter, HavenAluOpUTIO]:
  override def moduleName(p: HavenAluOpParameter): String = s"HavenAluOpUT_op${p.targetOp}"

  def architecture(parameter: HavenAluOpParameter) =
    val io       = summon[Interface[HavenAluOpUTIO]]
    val instance = HavenAlu.instantiate(HavenAluParameter())
    instance.io.clk   := io.clock
    instance.io.rst_n := !io.reset.asBool
    instance.io.a     := io.a
    instance.io.b     := io.b
    instance.io.op    := io.op
    instance.io.start := io.start
    io.RESULT         := instance.io.result
    io.DONE           := instance.io.done

    Txn.assumeResetLow(io.reset)

    Generate(
      Sem.value((io.op === parameter.targetOp.U(4).asBits) & io.start),
      s"gen_op${parameter.targetOp}"
    )

    val probe = summon[ProbeInterface[HavenAluOpUTProbe]]
    layer("Verification"):
      Probes.expose(probe.RESULT, Bits(32), instance.io.result)

/** Directed FP generation: the parameter names the opcode and the two operand values a residual-closing intent wants
  * driven, so one instance targets one corner case (NaN, infinity, overflow, denormal…).
  */
case class HavenAluFpParameter(op: Int, aVal: Long, bVal: Long) extends Parameter

given upickle.default.ReadWriter[HavenAluFpParameter] = upickle.default.macroRW

class HavenAluFpLayers(parameter: HavenAluFpParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

/** Same drive-port signature as [[HavenAluOpUTIO]] (a, b, op, start) so witnesses from either UT concatenate into one
  * stimulus stream.
  */
class HavenAluFpUTIO(parameter: HavenAluFpParameter) extends HWBundle(parameter):
  val clock  = Flipped(Clock())
  val reset  = Flipped(Reset())
  val a      = Flipped(Bits(32))
  val b      = Flipped(Bits(32))
  val op     = Flipped(Bits(4))
  val start  = Flipped(Bool())
  val RESULT = Aligned(Bits(32))
  val DONE   = Aligned(Bool())

class HavenAluFpUTProbe(parameter: HavenAluFpParameter)
    extends DVBundle[HavenAluFpParameter, HavenAluFpLayers](parameter):
  val RESULT = ProbeRead(Bits(32), layers("Verification"))

@generator
object HavenAluFpUT
    extends Generator[HavenAluFpParameter, HavenAluFpLayers, HavenAluFpUTIO, HavenAluFpUTProbe]
    with UT[HavenAluFpParameter, HavenAluFpUTIO]:
  override def moduleName(p: HavenAluFpParameter): String =
    s"HavenAluFpUT_op${p.op}_a${p.aVal.toHexString}_b${p.bVal.toHexString}"

  def architecture(parameter: HavenAluFpParameter) =
    val io       = summon[Interface[HavenAluFpUTIO]]
    val instance = HavenAlu.instantiate(HavenAluParameter())
    instance.io.clk   := io.clock
    instance.io.rst_n := !io.reset.asBool
    instance.io.a     := io.a
    instance.io.b     := io.b
    instance.io.op    := io.op
    instance.io.start := io.start
    io.RESULT         := instance.io.result
    io.DONE           := instance.io.done

    Txn.assumeResetLow(io.reset)

    Generate(
      Sem.value(
        (io.op === parameter.op.U(4).asBits) & io.start &
          (io.a === BigInt(parameter.aVal).U(32).asBits) & (io.b === BigInt(parameter.bVal).U(32).asBits)
      ),
      "gen_fp_corner"
    )

    val probe = summon[ProbeInterface[HavenAluFpUTProbe]]
    layer("Verification"):
      Probes.expose(probe.RESULT, Bits(32), instance.io.result)

/** Temporal intents over the FP pipeline: `notDoneCycles` says how many cycles after `start` the intent claims `done`
  * stays low. Satisfiable for a count the pipeline really takes; Infeasible once it exceeds the pipeline's latency —
  * and that verdict is the point.
  */
case class HavenAluSeqParameter(notDoneCycles: Int) extends Parameter

given upickle.default.ReadWriter[HavenAluSeqParameter] = upickle.default.macroRW

class HavenAluSeqLayers(parameter: HavenAluSeqParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

class HavenAluSeqUTIO(parameter: HavenAluSeqParameter) extends HWBundle(parameter):
  val clock  = Flipped(Clock())
  val reset  = Flipped(Reset())
  val a      = Flipped(Bits(32))
  val b      = Flipped(Bits(32))
  val op     = Flipped(Bits(4))
  val start  = Flipped(Bool())
  val RESULT = Aligned(Bits(32))
  val DONE   = Aligned(Bool())

class HavenAluSeqUTProbe(parameter: HavenAluSeqParameter)
    extends DVBundle[HavenAluSeqParameter, HavenAluSeqLayers](parameter):
  val DONE = ProbeRead(Bits(1), layers("Verification"))

/** C = "an FP add starts, and `done` stays low for the next `notDoneCycles` cycles" — a purely temporal intent, stated
  * with 时序语义 and no reference to internal state.
  */
@generator
object HavenAluSeqUT
    extends Generator[HavenAluSeqParameter, HavenAluSeqLayers, HavenAluSeqUTIO, HavenAluSeqUTProbe]
    with UT[HavenAluSeqParameter, HavenAluSeqUTIO]:
  override def moduleName(p: HavenAluSeqParameter): String = s"HavenAluSeqUT_n${p.notDoneCycles}"

  def architecture(parameter: HavenAluSeqParameter) =
    val io       = summon[Interface[HavenAluSeqUTIO]]
    val instance = HavenAlu.instantiate(HavenAluParameter())
    instance.io.clk   := io.clock
    instance.io.rst_n := !io.reset.asBool
    instance.io.a     := io.a
    instance.io.b     := io.b
    instance.io.op    := io.op
    instance.io.start := io.start
    io.RESULT         := instance.io.result
    io.DONE           := instance.io.done

    Txn.assumeResetLow(io.reset)

    given ClockEvent = posedge(io.clock)
    val fpStart      = (io.op === 0.U(4).asBits) & io.start
    val notDone      = !instance.io.done
    val chain        = (1 to parameter.notDoneCycles).foldLeft(fpStart.S)((acc, _) => acc ### notDone.S)
    Generate(Sem.temporal(chain), s"fp_not_done_for_${parameter.notDoneCycles}")

    val probe = summon[ProbeInterface[HavenAluSeqUTProbe]]
    layer("Verification"):
      Probes.expose(probe.DONE, Bits(1), instance.io.done.asBits)

/** Goal-directed generation: the intent names a *destination* over the DUT's outputs and says nothing about how to get
  * there, leaving the solver to search the 64-bit operand space.
  *
  * This is the arm that separates the two things the harness does. [[HavenAluFpUT]] still has the author choosing
  * operands — the solver only schedules the handshake around them — so a model driving it must reason about IEEE-754
  * arithmetic itself. Here the model states "underflow flag set, on FP_SUB" and the solver produces the operands, which
  * is the claim worth measuring: naming a destination is a different (and cheaper) task than computing a route to it.
  *
  * Every field is optional and unset fields stay free, so one parameter shape covers "any op reaching this flag" and
  * "this exact result on this opcode" alike. `flagsMask` selects which of the four flag bits `flagsValue` constrains.
  */
case class HavenAluGoalParameter(
  label:      String,
  flagsMask:  Int = 0,
  flagsValue: Int = 0,
  opIs:       Option[Int] = None,
  resultIs:   Option[Long] = None,
  requireDone: Boolean = true)
    extends Parameter

given upickle.default.ReadWriter[HavenAluGoalParameter] = upickle.default.macroRW

class HavenAluGoalLayers(parameter: HavenAluGoalParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

/** Drive-port signature identical to [[HavenAluFpUTIO]] and [[HavenAluOpUTIO]] (a, b, op, start), so goal-derived
  * witnesses concatenate with operand-directed ones into a single sequence.
  */
class HavenAluGoalUTIO(parameter: HavenAluGoalParameter) extends HWBundle(parameter):
  val clock  = Flipped(Clock())
  val reset  = Flipped(Reset())
  val a      = Flipped(Bits(32))
  val b      = Flipped(Bits(32))
  val op     = Flipped(Bits(4))
  val start  = Flipped(Bool())
  val RESULT = Aligned(Bits(32))
  val FLAGS  = Aligned(Bits(4))
  val DONE   = Aligned(Bool())

class HavenAluGoalUTProbe(parameter: HavenAluGoalParameter)
    extends DVBundle[HavenAluGoalParameter, HavenAluGoalLayers](parameter):
  val RESULT = ProbeRead(Bits(32), layers("Verification"))
  val FLAGS  = ProbeRead(Bits(4), layers("Verification"))

@generator
object HavenAluGoalUT
    extends Generator[HavenAluGoalParameter, HavenAluGoalLayers, HavenAluGoalUTIO, HavenAluGoalUTProbe]
    with UT[HavenAluGoalParameter, HavenAluGoalUTIO]:
  override def moduleName(p: HavenAluGoalParameter): String = s"HavenAluGoalUT_${p.label}"

  def architecture(parameter: HavenAluGoalParameter) =
    val io       = summon[Interface[HavenAluGoalUTIO]]
    val instance = HavenAlu.instantiate(HavenAluParameter())
    instance.io.clk   := io.clock
    instance.io.rst_n := !io.reset.asBool
    instance.io.a     := io.a
    instance.io.b     := io.b
    // `opIs` names the operation to be launched, and it is tied into the DUT rather than assumed of the port, because
    // an assumption would not hold: circt-bmc does not enforce a `verif.assume` on a port that feeds an `hw.instance`
    // (see `docs/date2027/circt-bmc-assume.md` and its reproducer), so the solver would launch whatever opcode it
    // liked and the witness would replay as a different operation. Tying it is structural and cannot be ignored; the
    // port stays in the signature so goal witnesses still concatenate with operand-directed ones, and the replayed
    // stimulus must carry this same constant.
    instance.io.op    := parameter.opIs.fold(io.op)(v => v.U(4).asBits)
    instance.io.start := io.start
    io.RESULT         := instance.io.result
    io.FLAGS          := instance.io.flags
    io.DONE           := instance.io.done

    Txn.assumeResetLow(io.reset)

    // The goal itself is a state predicate: a condition on what the DUT has produced, not on what was driven into it.
    val done   = if parameter.requireDone then instance.io.done else true.B
    val flags  =
      if parameter.flagsMask == 0 then true.B
      else
        (instance.io.flags & parameter.flagsMask.U(4).asBits) ===
          (parameter.flagsValue & parameter.flagsMask).U(4).asBits
    val result = parameter.resultIs.fold(true.B)(v => instance.io.result === BigInt(v).U(32).asBits)
    Generate(Sem.state(done & flags & result), s"goal_${parameter.label}")

    val probe = summon[ProbeInterface[HavenAluGoalUTProbe]]
    layer("Verification"):
      Probes.expose(probe.RESULT, Bits(32), instance.io.result)
      Probes.expose(probe.FLAGS, Bits(4), instance.io.flags)
