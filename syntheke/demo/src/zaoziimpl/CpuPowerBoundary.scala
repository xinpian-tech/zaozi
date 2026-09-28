package me.jiuyang.syntheke.demo.zaoziimpl

import com.vowstar.ditdah32.AbstractCommandError
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
  startupCycles: Int,
  maxOutstanding: Int,
  aonMillivolts: Int,
  cpuMillivolts: Int)
    extends Parameter derives ReadWriter:
  require(xlen > 0, s"debug width must be positive, got $xlen")
  require(startupCycles > 0, s"power startup must take at least one cycle, got $startupCycles")
  require(maxOutstanding > 0, s"AXI outstanding bound must be positive, got $maxOutstanding")

class CpuPowerBoundaryPLayers(p: CpuPowerBoundaryP) extends LayerInterface(p):
  def layers = Seq(Layer("Verification"))
class CpuPowerBoundaryPProbe(p: CpuPowerBoundaryP)
    extends DVBundle[CpuPowerBoundaryP, CpuPowerBoundaryPLayers](p)
class CpuPowerBoundaryPIO(p: CpuPowerBoundaryP) extends HWBundle(p):
  val clk = Flipped(new ClockBundle)
  val cpuClk = Aligned(new ClockBundle)
  val cpuMem = Flipped(new AxiPortBundle(p.axi))
  val bus = Aligned(new AxiPortBundle(p.axi))
  val debug = Flipped(new DebugHartBundle(p.xlen))
  val cpuDebug = Aligned(new DebugHartBundle(p.xlen))
  val control = Flipped(new PowerControlBundle)
  val retention = Aligned(new RetentionBundle)

@generator
object CpuPowerBoundaryGen
    extends Generator[CpuPowerBoundaryP, CpuPowerBoundaryPLayers, CpuPowerBoundaryPIO, CpuPowerBoundaryPProbe]:
  def architecture(p: CpuPowerBoundaryP) =
    val io = summon[Interface[CpuPowerBoundaryPIO]]
    given ClockScope = ClockScope.posedge(io.clk.clock)
    given ResetScope = ResetScope.asyncActiveHigh(io.clk.reset)

    val off = 0
    val starting = 1
    val initializing = 2
    val restoring = 3
    val connecting = 4
    val waking = 5
    val running = 6
    val draining = 7
    val saving = 8
    val isolating = 9
    val resetting = 10
    val stopping = 11
    val state = RegInit(off.U(4))
    val retained = RegInit(false.B)
    val resumeAfterPower = RegInit(false.B)
    val resumePending = RegInit(false.B)
    val resetPending = RegInit(false.B)
    val aonReset = io.clk.reset.asBool
    val powerEnable = (state =/= off.U(4)) & (state =/= stopping.U(4)) & !aonReset
    val supply = PowerSwitch.instantiate(PowerSwitchP(p.startupCycles, fallCycles = 2))
    supply.io.clock := io.clk.clock
    supply.io.reset := io.clk.reset
    supply.io.enable := powerEnable
    val powerGoodMeta = RegInit(false.B)
    val powerGood = RegInit(false.B)
    powerGoodMeta := supply.io.good
    powerGood := powerGoodMeta

    val live = (state === running.U(4)) | (state === draining.U(4)) | (state === waking.U(4))
    val active = live & powerGood & !aonReset
    val connected = ((state === connecting.U(4)) | (state === restoring.U(4)) |
      (state === saving.U(4)) | (state === isolating.U(4)) | live) & powerGood & !aonReset
    val resetHeld = (state === off.U(4)) | (state === starting.U(4)) | (state === initializing.U(4)) |
      (state === resetting.U(4)) | (state === stopping.U(4))
    val clockWanted = (state =/= off.U(4)) & (state =/= stopping.U(4))
    val clockEnabled = ClockScope.negedge(io.clk.clock) {
      val enabled = RegInit(false.B)
      enabled := clockWanted & powerGood
      enabled
    }

    def shift(width: Int, value: Referable[Bits], sourceMv: Int, targetMv: Int)(using Arena, Context, Block): Ref[Bits] =
      val cell = LevelShifter.instantiate(LevelShifterP(width, sourceMv, targetMv))
      cell.io.in := value
      cell.io.out

    def isolate(width: Int, value: Referable[Bits], enable: Referable[Bool])(using Arena, Context, Block): Ref[Bits] =
      val cell = Isolation.instantiate(IsolationP(width))
      cell.io.in := value
      cell.io.isolate := !enable
      cell.io.out

    def fromCpu(width: Int, value: Referable[Bits])(using Arena, Context, Block): Ref[Bits] =
      isolate(width, shift(width, value, p.cpuMillivolts, p.aonMillivolts), active)

    def toCpu(width: Int, value: Referable[Bits], enable: Referable[Bool])(using Arena, Context, Block): Ref[Bits] =
      shift(width, isolate(width, value, enable), p.aonMillivolts, p.cpuMillivolts)

    val gatedClock = io.clk.clock.asBool & clockEnabled
    io.cpuClk.clock := shift(1, gatedClock.asBits, p.aonMillivolts, p.cpuMillivolts).bit(0).asClock
    val cpuReset = aonReset | resetHeld | !powerGood
    io.cpuClk.reset := shift(1, cpuReset.asBits, p.aonMillivolts, p.cpuMillivolts).bit(0).asReset

    val retentionAccess = powerGood & clockEnabled & !aonReset
    val saveRequest = (state === saving.U(4)) & !resetPending & !io.debug.reset
    val restoreRequest = (state === restoring.U(4)) & retained & !resetPending & !io.debug.reset
    io.retention.save := toCpu(1, saveRequest.asBits, retentionAccess).bit(0)
    io.retention.restore := toCpu(1, restoreRequest.asBits, retentionAccess).bit(0)
    io.retention.reset := (aonReset | resetPending | io.debug.reset).asReset
    val saved = isolate(1,
      shift(1, io.retention.saved.asBits, p.cpuMillivolts, p.aonMillivolts), retentionAccess).bit(0)
    val restored = isolate(1,
      shift(1, io.retention.restored.asBits, p.cpuMillivolts, p.aonMillivolts), retentionAccess).bit(0)
    val cpuHalted = isolate(1,
      shift(1, io.cpuDebug.hart.halted.asBits, p.cpuMillivolts, p.aonMillivolts), connected).bit(0)
    val cpuResumeAck = isolate(1,
      shift(1, io.cpuDebug.hart.resumeAck.asBits, p.cpuMillivolts, p.aonMillivolts), connected).bit(0)
    val cpuResetAck = isolate(1,
      shift(1, io.cpuDebug.hart.resetAck.asBits, p.cpuMillivolts, p.aonMillivolts), connected).bit(0)

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

    val resetIdle = 0
    val resetQuiesce = 1
    val resetAssert = 2
    val resetRelease = 3
    val debugResetPhase = RegInit(resetIdle.U(2))
    val haltRequest = RegInit(false.B)
    val resetWaiting = (debugResetPhase === resetQuiesce.U(2)) |
      ((debugResetPhase === resetIdle.U(2)) & (io.debug.reset | resetPending))
    val accepting = (state === running.U(4)) & io.control.requestOn & active &
      (debugResetPhase === resetIdle.U(2)) & !io.debug.reset & !resetPending
    val wakeResume = (state === waking.U(4)) & io.control.requestOn &
      (resumeAfterPower | resumePending) & !io.debug.halt & !resetWaiting &
      (debugResetPhase === resetIdle.U(2))
    val resumeComplete = cpuResumeAck | ((state === waking.U(4)) & !cpuHalted &
      !resetWaiting & (debugResetPhase === resetIdle.U(2)))
    when(!live) {
      haltRequest := connected
    }
    when(live & (!io.bus.ar.valid | io.bus.ar.ready)) {
      haltRequest := io.debug.halt | (state === draining.U(4)) | !io.control.requestOn | resetWaiting
    }
    val forceHalt = (state === saving.U(4)) | (state === restoring.U(4)) | (state === connecting.U(4))
    io.cpuDebug.halt := toCpu(1, (haltRequest | forceHalt).asBits, connected).bit(0)
    io.cpuDebug.resume := toCpu(1,
      ((accepting & (io.debug.resume | resumePending) & !io.debug.halt) | wakeResume).asBits, active).bit(0)
    io.cpuDebug.reset := toCpu(1, (debugResetPhase === resetAssert.U(2)).asBits, active).bit(0)
    io.cpuDebug.haltOnReset := toCpu(1, io.debug.haltOnReset.asBits, connected).bit(0)

    val commandBusy = RegInit(false.B)
    val commandAllowed = accepting & !commandBusy
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
    when(commandDone) { commandBusy := false.B }

    val rejectedCommand = RegInit(false.B)
    rejectedCommand := io.debug.cmd.valid & !commandAllowed
    io.debug.hart.halted := cpuHalted & active
    io.debug.hart.running := fromCpu(1, io.cpuDebug.hart.running.asBits).bit(0)
    io.debug.hart.resumeAck := resumeComplete & (resumePending | io.debug.resume) & active
    io.debug.hart.resetAck := cpuResetAck & active
    io.debug.hart.cmdDone := rejectedCommand ? (true.B, commandDone)
    io.debug.hart.cmdError := rejectedCommand ? (AbstractCommandError.HALT_OR_RESUME.B(3), commandError)
    io.debug.hart.cmdRdata := rejectedCommand ? (0.B(p.xlen), commandRdata)

    val quiet = haltRequest & io.debug.hart.halted & !commandBusy &
      (addresses === 0.U(counterBits)) & (writes === 0.U(counterBits)) & (reads === 0.U(counterBits)) &
      !partialWrite & !io.bus.aw.valid & !io.bus.w.valid & !io.bus.ar.valid
    when(debugResetPhase === resetIdle.U(2)) {
      when(((state === running.U(4)) | (state === waking.U(4))) & io.control.requestOn &
        (io.debug.reset | resetPending)) {
        debugResetPhase := resetQuiesce.U(2)
      }
    }
    when(debugResetPhase === resetQuiesce.U(2)) {
      when(!io.control.requestOn) {
        debugResetPhase := resetIdle.U(2)
      }.otherwise {
        when(quiet) { debugResetPhase := resetAssert.U(2) }
      }
    }
    when(debugResetPhase === resetAssert.U(2)) {
      when(!io.debug.reset | !io.control.requestOn) { debugResetPhase := resetRelease.U(2) }
    }
    when(debugResetPhase === resetRelease.U(2)) {
      when(cpuResetAck) {
        debugResetPhase := resetIdle.U(2)
        resetPending := false.B
      }
    }
    val drained = quiet & (debugResetPhase === resetIdle.U(2))
    when(state === off.U(4)) {
      when(io.control.requestOn & !powerGood) {
        state := starting.U(4)
        when(!retained | resetPending | io.debug.reset) {
          resumeAfterPower := !io.debug.halt & !io.debug.haltOnReset
        }
      }
    }
    when(state === starting.U(4)) {
      when(powerGood & clockEnabled) { state := initializing.U(4) }
    }
    when(state === initializing.U(4)) {
      state := (retained & !resetPending & !io.debug.reset) ? (restoring.U(4), connecting.U(4))
    }
    when(state === restoring.U(4)) {
      when(resetPending | io.debug.reset) { state := initializing.U(4) }
        .otherwise {
          when(restored) { state := connecting.U(4) }
        }
    }
    when(state === connecting.U(4)) {
      when(cpuHalted & !saved & !restored) { state := waking.U(4) }
    }
    when(state === waking.U(4)) {
      when(!resetWaiting & (debugResetPhase === resetIdle.U(2))) {
        when(!wakeResume | resumeComplete) { state := running.U(4) }
      }
      when(!io.control.requestOn) {
        resumeAfterPower := (resumeAfterPower | !cpuHalted | resumePending | io.debug.resume) & !io.debug.halt
        state := draining.U(4)
      }
    }
    when(state === running.U(4)) {
      when(!io.control.requestOn) {
        resumeAfterPower := (!cpuHalted | io.debug.resume | resumePending) & !io.debug.halt
        state := draining.U(4)
      }
    }
    when(state === draining.U(4)) {
      when(drained) { state := saving.U(4) }
    }
    when(state === saving.U(4)) {
      when(resetPending | io.debug.reset) { state := isolating.U(4) }
        .otherwise {
          when(saved) {
            retained := true.B
            state := isolating.U(4)
          }
        }
    }
    when(state === isolating.U(4)) {
      when(!saved) { state := resetting.U(4) }
    }
    when(state === resetting.U(4)) {
      state := stopping.U(4)
    }
    when(state === stopping.U(4)) {
      when(!powerGood & !clockEnabled) { state := off.U(4) }
    }
    when(resumeComplete) {
      resumePending := false.B
      when((state === waking.U(4)) & io.control.requestOn) { resumeAfterPower := false.B }
    }
    when(io.debug.resume & !resumeComplete) { resumePending := true.B }
    when(io.debug.halt) {
      resumeAfterPower := false.B
      resumePending := false.B
    }
    when(io.debug.reset) {
      resetPending := true.B
      retained := false.B
      resumePending := false.B
      resumeAfterPower := !io.debug.halt & !io.debug.haltOnReset
    }

    io.control.on := (state === running.U(4)) & active
    io.control.busy := (state =/= off.U(4)) & (state =/= running.U(4))
    io.control.powerGood := powerGood
    io.control.isolated := !active

    layer("Verification"):
      given ClockEvent = posedge(io.clk.clock)
      Assert((!saveRequest | (cpuHalted & !commandBusy & !active & clockEnabled & !cpuReset)).S,
        !aonReset, "power_save_quiescent")
      Assert((!restoreRequest | (retained & !active & clockEnabled & !cpuReset)).S,
        !aonReset, "power_restore_isolated")
      Assert((powerEnable | (!active & cpuReset)).S, !aonReset, "power_off_isolated_reset")
      Assert(((state =/= stopping.U(4)) | retained | resetPending | io.debug.reset).S,
        !aonReset, "power_off_after_save_or_reset")
      Cover(((state === off.U(4)) & retained).S, !aonReset, "power_off_retained")
      Cover(restored.S, !aonReset, "power_restore_complete")
