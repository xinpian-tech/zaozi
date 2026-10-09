package me.jiuyang.syntheke.demo.zaoziimpl

import com.vowstar.ditdah32.AbstractCommandError
import me.jiuyang.stdlib.prcm.PRCMDomainPort
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.ClockEvent
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}
import upickle.default.ReadWriter

import java.lang.foreign.Arena

case class CpuPowerBoundaryP(
  axi: AxiShape,
  xlen: Int,
  settleOnCycles: Int,
  settleOffCycles: Int,
  maxOutstanding: Int)
    extends Parameter derives ReadWriter:
  require(xlen > 0, s"debug width must be positive, got $xlen")
  require(settleOnCycles > 0 && settleOffCycles > 0, s"power must take at least one cycle to settle, got $settleOnCycles/$settleOffCycles")
  require(maxOutstanding > 0, s"AXI outstanding bound must be positive, got $maxOutstanding")

class CpuPowerBoundaryPLayers(p: CpuPowerBoundaryP) extends LayerInterface(p):
  def layers = Seq(Layer("Verification"))
class CpuPowerBoundaryPProbe(p: CpuPowerBoundaryP)
    extends DVBundle[CpuPowerBoundaryP, CpuPowerBoundaryPLayers](p)
class CpuPowerBoundaryPIO(p: CpuPowerBoundaryP) extends HWBundle(p):
  val clk = Flipped(new ClockBundle)
  val port = Flipped(new PRCMDomainPort)
  val cpuClk = Aligned(new ClockBundle)
  val cpuMem = Flipped(new AxiPortBundle(p.axi))
  val bus = Aligned(new AxiPortBundle(p.axi))
  val debug = Flipped(new DebugHartBundle(p.xlen))
  val cpuDebug = Aligned(new DebugHartBundle(p.xlen))
  val retention = Aligned(new RetentionBundle)

/** The CPU side of one zaozi PR #159 PRCM port. The PRCM sequences the domain; the boundary switches its supply on
  * `powerRequest`, clamps its outputs on `isolationRequest` and, on `quiesceRequest`, halts the CPU and drains its
  * AXI traffic before it answers `idle`; while power is not good it holds the CPU's retention flops. Feedback is on the
  * management clock `clk`; the CPU runs on the clock and reset the PRCM drives.
  */
@generator
object CpuPowerBoundaryGen
    extends Generator[CpuPowerBoundaryP, CpuPowerBoundaryPLayers, CpuPowerBoundaryPIO, CpuPowerBoundaryPProbe]:
  def architecture(p: CpuPowerBoundaryP) =
    val io = summon[Interface[CpuPowerBoundaryPIO]]
    given ClockScope = ClockScope.posedge(io.clk.clock)
    given ResetScope = ResetScope.asyncActiveHigh(io.clk.reset)
    val aonReset = io.clk.reset.asBool

    val supply = PowerSwitch.instantiate(PowerSwitchP(p.settleOnCycles, p.settleOffCycles))
    supply.io.clock := io.clk.clock
    supply.io.reset := io.clk.reset
    supply.io.enable := io.port.powerRequest & !aonReset
    val powerGoodMeta = RegInit(false.B)
    val powerGood = RegInit(false.B)
    powerGoodMeta := supply.io.good
    powerGood := powerGoodMeta
    io.port.powerGood := powerGood
    // Power is good only once the switch acknowledged it; until then the CPU's retention flops hold.
    io.retention.sleep := !powerGood
    io.retention.reset := io.clk.reset

    val isolated = RegInit(true.B)
    isolated := io.port.isolationRequest
    io.port.isolationActive := isolated
    val active = !isolated & powerGood & !aonReset

    def shift(width: Int, value: Referable[Bits])(using Arena, Context, Block): Ref[Bits] =
      val cell = LevelShifter.instantiate(LevelShifterP(width))
      cell.io.in := value
      cell.io.out

    def isolate(width: Int, value: Referable[Bits], enable: Referable[Bool])(using Arena, Context, Block): Ref[Bits] =
      val cell = Isolation.instantiate(IsolationP(width))
      cell.io.in := value
      cell.io.isolate := !enable
      cell.io.out

    def fromCpu(width: Int, value: Referable[Bits])(using Arena, Context, Block): Ref[Bits] =
      isolate(width, shift(width, value), active)

    def toCpu(width: Int, value: Referable[Bits], enable: Referable[Bool])(using Arena, Context, Block): Ref[Bits] =
      shift(width, isolate(width, value, enable))

    // The PRCM gates the clock and releases the reset; both cross into the CPU supply as they are.
    io.cpuClk.clock := shift(1, io.port.clock.asBool.asBits).bit(0).asClock
    io.cpuClk.reset := shift(1, io.port.resetN.asBool.asBits).bit(0).asReset

    io.bus.aw.valid := fromCpu(1, io.cpuMem.aw.valid.asBits).bit(0)
    io.bus.aw.bits.id := fromCpu(p.axi.idBits, io.cpuMem.aw.bits.id)
    io.bus.aw.bits.addr := fromCpu(p.axi.addrBits, io.cpuMem.aw.bits.addr)
    io.bus.aw.bits.len := fromCpu(8, io.cpuMem.aw.bits.len)
    io.bus.aw.bits.size := fromCpu(3, io.cpuMem.aw.bits.size)
    io.bus.aw.bits.burst := fromCpu(2, io.cpuMem.aw.bits.burst)
    io.cpuMem.aw.ready := toCpu(1, io.bus.aw.ready.asBits, active).bit(0)

    io.bus.w.valid := fromCpu(1, io.cpuMem.w.valid.asBits).bit(0)
    io.bus.w.bits.data := fromCpu(p.axi.dataBits, io.cpuMem.w.bits.data)
    io.bus.w.bits.strb := fromCpu(p.axi.dataBits / 8, io.cpuMem.w.bits.strb)
    io.bus.w.bits.last := fromCpu(1, io.cpuMem.w.bits.last.asBits).bit(0)
    io.cpuMem.w.ready := toCpu(1, io.bus.w.ready.asBits, active).bit(0)

    io.cpuMem.b.valid := toCpu(1, io.bus.b.valid.asBits, active).bit(0)
    io.cpuMem.b.bits.id := toCpu(p.axi.idBits, io.bus.b.bits.id, active)
    io.cpuMem.b.bits.resp := toCpu(2, io.bus.b.bits.resp, active)
    io.bus.b.ready := fromCpu(1, io.cpuMem.b.ready.asBits).bit(0)

    io.bus.ar.valid := fromCpu(1, io.cpuMem.ar.valid.asBits).bit(0)
    io.bus.ar.bits.id := fromCpu(p.axi.idBits, io.cpuMem.ar.bits.id)
    io.bus.ar.bits.addr := fromCpu(p.axi.addrBits, io.cpuMem.ar.bits.addr)
    io.bus.ar.bits.len := fromCpu(8, io.cpuMem.ar.bits.len)
    io.bus.ar.bits.size := fromCpu(3, io.cpuMem.ar.bits.size)
    io.bus.ar.bits.burst := fromCpu(2, io.cpuMem.ar.bits.burst)
    io.cpuMem.ar.ready := toCpu(1, io.bus.ar.ready.asBits, active).bit(0)

    io.cpuMem.r.valid := toCpu(1, io.bus.r.valid.asBits, active).bit(0)
    io.cpuMem.r.bits.id := toCpu(p.axi.idBits, io.bus.r.bits.id, active)
    io.cpuMem.r.bits.data := toCpu(p.axi.dataBits, io.bus.r.bits.data, active)
    io.cpuMem.r.bits.resp := toCpu(2, io.bus.r.bits.resp, active)
    io.cpuMem.r.bits.last := toCpu(1, io.bus.r.bits.last.asBits, active).bit(0)
    io.bus.r.ready := fromCpu(1, io.cpuMem.r.ready.asBits).bit(0)

    val counterBits = 32 - Integer.numberOfLeadingZeros(p.maxOutstanding)
    val addresses = RegInit(0.U(counterBits))
    val writes = RegInit(0.U(counterBits))
    val reads = RegInit(0.U(counterBits))
    val partialWrite = RegInit(false.B)
    val awFire = io.bus.aw.valid & io.bus.aw.ready
    val wFire = io.bus.w.valid & io.bus.w.ready
    val wLastFire = wFire & io.bus.w.bits.last
    val bFire = io.bus.b.valid & io.bus.b.ready
    val arFire = io.bus.ar.valid & io.bus.ar.ready
    val rLastFire = io.bus.r.valid & io.bus.r.ready & io.bus.r.bits.last
    when(awFire & !bFire) { addresses := (addresses + 1.U(counterBits)).asBits.bits(counterBits - 1, 0).asUInt }
    when(bFire & !awFire) { addresses := (addresses - 1.U(counterBits)).asBits.bits(counterBits - 1, 0).asUInt }
    when(wLastFire & !bFire) { writes := (writes + 1.U(counterBits)).asBits.bits(counterBits - 1, 0).asUInt }
    when(bFire & !wLastFire) { writes := (writes - 1.U(counterBits)).asBits.bits(counterBits - 1, 0).asUInt }
    when(arFire & !rLastFire) { reads := (reads + 1.U(counterBits)).asBits.bits(counterBits - 1, 0).asUInt }
    when(rLastFire & !arFire) { reads := (reads - 1.U(counterBits)).asBits.bits(counterBits - 1, 0).asUInt }
    when(wFire) { partialWrite := !io.bus.w.bits.last }


    val cpuHalted = fromCpu(1, io.cpuDebug.hart.halted.asBits).bit(0)
    val cpuResumeAck = fromCpu(1, io.cpuDebug.hart.resumeAck.asBits).bit(0)
    val cpuResetAck = fromCpu(1, io.cpuDebug.hart.resetAck.asBits).bit(0)

    // Quiesce holds the CPU halted, from the moment its reset is released until the PRCM lets it run; it stays halted
    // after, as it does out of a cold reset, until the debugger resumes it. Halt reaches the CPU unisolated so that it
    // already holds while isolation is still up.
    val quiesce = io.port.quiesceRequest
    io.cpuDebug.halt := shift(1, (quiesce | io.debug.halt).asBits).bit(0)
    io.cpuDebug.haltOnReset := shift(1, io.debug.haltOnReset.asBits).bit(0)
    io.cpuDebug.resume := toCpu(1, (io.debug.resume & !quiesce).asBits, active).bit(0)
    io.cpuDebug.reset := toCpu(1, io.debug.reset.asBits, active).bit(0)

    val commandBusy = RegInit(false.B)
    val commandAllowed = active & !quiesce & !commandBusy
    val commandFire = io.debug.cmd.valid & commandAllowed
    io.cpuDebug.cmd.valid := toCpu(1, io.debug.cmd.valid.asBits, commandAllowed).bit(0)
    io.cpuDebug.cmd.kind := toCpu(2, io.debug.cmd.kind, commandAllowed)
    io.cpuDebug.cmd.write := toCpu(1, io.debug.cmd.write.asBits, commandAllowed).bit(0)
    io.cpuDebug.cmd.regno := toCpu(16, io.debug.cmd.regno, commandAllowed)
    io.cpuDebug.cmd.size := toCpu(3, io.debug.cmd.size, commandAllowed)
    io.cpuDebug.cmd.data := toCpu(p.xlen, io.debug.cmd.data, commandAllowed)
    io.cpuDebug.cmd.address := toCpu(p.xlen, io.debug.cmd.address, commandAllowed)
    val commandDone = fromCpu(1, io.cpuDebug.hart.cmdDone.asBits).bit(0)
    val commandError = fromCpu(3, io.cpuDebug.hart.cmdError)
    val commandRdata = fromCpu(p.xlen, io.cpuDebug.hart.cmdRdata)
    when(commandFire) { commandBusy := true.B }
    when(commandDone | !active) { commandBusy := false.B }

    val rejectedCommand = RegInit(false.B)
    rejectedCommand := io.debug.cmd.valid & !commandAllowed & !commandBusy
    io.debug.hart.halted := cpuHalted & active
    io.debug.hart.running := fromCpu(1, io.cpuDebug.hart.running.asBits).bit(0)
    io.debug.hart.resumeAck := cpuResumeAck & active
    io.debug.hart.resetAck := cpuResetAck & active
    io.debug.hart.cmdDone := rejectedCommand ? (true.B, commandDone)
    io.debug.hart.cmdError := rejectedCommand ? (AbstractCommandError.HALT_OR_RESUME.B(3), commandError)
    io.debug.hart.cmdRdata := rejectedCommand ? (0.B(p.xlen), commandRdata)

    // Idle answers quiesce: at once while the domain is isolated, otherwise once the CPU is halted and drained.
    val drained = cpuHalted & !commandBusy &
      (addresses === 0.U(counterBits)) & (writes === 0.U(counterBits)) & (reads === 0.U(counterBits)) &
      !partialWrite & !io.bus.aw.valid & !io.bus.w.valid & !io.bus.ar.valid
    val idle = RegInit(true.B)
    idle := quiesce & (!active | drained)
    io.port.idle := idle

    layer("Verification"):
      given ClockEvent = posedge(io.clk.clock)
      Cover((quiesce & active & drained).S, !aonReset, "cpu_quiesced")
      Cover((powerGood & !io.port.powerRequest).S, !aonReset, "cpu_power_falling")
