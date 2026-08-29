// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib.queue.default

import java.lang.foreign.Arena

import me.jiuyang.stdlib.{BKAIncrementerParameter, BrentKungAdder, BrentKungAdderParameter, Incrementer, PrefixAdderIO}
import me.jiuyang.stdlib.default.{Ram, RamParameter}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** Operation-oriented implementation of `DW_fifo_s2_sf`.
  *
  * The controller, directional pointer logic, and synchronizers are elaboration helpers. [[Ram]] and the arithmetic
  * primitives are the queue's child modules.
  */
case class AsyncQueueParameter(
  /** Number of bits stored in each queue entry. */
  width:                Int,
  /** Logical number of entries available to the queue. */
  depth:                Int,
  /** Assert push-domain almost-empty when its estimated occupancy is at most this value. */
  pushAlmostEmptyLevel: Int,
  /** Assert push-domain almost-full when its estimated occupancy reaches `depth - pushAlmostFullLevel`. */
  pushAlmostFullLevel:  Int,
  /** Assert pop-domain almost-empty when its estimated occupancy is at most this value. */
  popAlmostEmptyLevel:  Int,
  /** Assert pop-domain almost-full when its estimated occupancy reaches `depth - popAlmostFullLevel`. */
  popAlmostFullLevel:   Int,
  /** Keep overflow and underflow errors asserted until reset; otherwise report only the current illegal request. */
  stickyError:          Boolean,
  /** Synchronizer stages used to transfer the pop Gray pointer into the push clock domain. */
  pushSync:             Int,
  /** Synchronizer stages used to transfer the push Gray pointer into the pop clock domain. */
  popSync:              Int,
  /** Use asynchronous active-low reset when true, or synchronous active-low reset when false. */
  asyncReset:           Boolean,
  /** Reset all RAM entries to zero when true; otherwise reset only queue control state. */
  resetMem:             Boolean)
    extends Parameter:
  require(width >= 1 && width <= 256, s"AsyncQueue width must be 1..256, got $width")
  require(depth >= 4 && depth <= 256, s"AsyncQueue depth must be 4..256, got $depth")
  Seq(
    "pushAlmostEmptyLevel" -> pushAlmostEmptyLevel,
    "pushAlmostFullLevel"  -> pushAlmostFullLevel,
    "popAlmostEmptyLevel"  -> popAlmostEmptyLevel,
    "popAlmostFullLevel"   -> popAlmostFullLevel
  ).foreach: (name, level) =>
    require(level >= 1 && level <= depth - 1, s"AsyncQueue $name must be 1..depth-1, got $level")
  require(pushSync >= 1 && pushSync <= 3, s"AsyncQueue pushSync must be 1..3, got $pushSync")
  require(popSync >= 1 && popSync <= 3, s"AsyncQueue popSync must be 1..3, got $popSync")

  /** Number of bits used by the physical RAM addresses. */
  def addressWidth: Int = QueueHelper.bitWidth(depth)

  /** Physical RAM depth required by the centered non-power-of-two pointer mapping. */
  def effectiveDepth: Int = QueueHelper.effectiveDepth(depth)

given upickle.default.ReadWriter[AsyncQueueParameter] = upickle.default.macroRW

class AsyncQueueLayers(parameter: AsyncQueueParameter) extends LayerInterface(parameter):
  def layers = Seq(Layer("Verification"))

class AsyncQueuePortIO extends Bundle:
  /** Local clock for this push or pop endpoint. */
  val clock = Flipped(Clock())

  /** Active-low push or pop request sampled in this endpoint's clock domain. */
  val requestN = Flipped(Bool())

  /** Local synchronized view indicating that the queue contains no entries. */
  val empty = Aligned(Bool())

  /** Local synchronized view indicating occupancy at or below the configured almost-empty level. */
  val almostEmpty = Aligned(Bool())

  /** Local synchronized view indicating occupancy at or above half the logical depth. */
  val halfFull = Aligned(Bool())

  /** Local synchronized view indicating occupancy at or above the configured almost-full threshold. */
  val almostFull = Aligned(Bool())

  /** Local synchronized view indicating that all logical entries are occupied. */
  val full = Aligned(Bool())

  /** Registered overflow or underflow indication for this endpoint. */
  val error = Aligned(Bool())

class AsyncQueueIO(parameter: AsyncQueueParameter) extends HWBundle(parameter):
  /** Shared active-low reset for queue control state and, when enabled, RAM contents. */
  val resetN = Flipped(Reset())

  /** Push-clock-domain request and status interface. */
  val push = Aligned(new AsyncQueuePortIO)

  /** Pop-clock-domain request and status interface. */
  val pop = Aligned(new AsyncQueuePortIO)

  /** Data written when a push request is accepted. */
  val dataIn = Flipped(UInt(parameter.width))

  /** Data at the current pop address; consumed only by an accepted pop request. */
  val dataOut = Aligned(UInt(parameter.width))

class AsyncQueueProbe(parameter: AsyncQueueParameter) extends DVBundle[AsyncQueueParameter, AsyncQueueLayers](parameter)

/** Registers owned by one endpoint's clock domain. Only `grayPointer` is observed by the opposite domain. */
private final case class AsyncDirectionState(
  pointer:        Reg[UInt],
  address:        Reg[UInt],
  grayPointer:    Reg[UInt],
  wordCount:      Reg[UInt],
  notEmpty:       Reg[Bool],
  notAlmostEmpty: Reg[Bool],
  halfFull:       Reg[Bool],
  almostFull:     Reg[Bool],
  full:           Reg[Bool],
  error:          Reg[Bool])

/** Local-domain queue view and RAM address produced by one directional controller. */
private final case class AsyncDirectionOutput(
  address:     Referable[UInt],
  grayPointer: Referable[UInt],
  wordCount:   Referable[UInt],
  empty:       Referable[Bool],
  almostEmpty: Referable[Bool],
  halfFull:    Referable[Bool],
  almostFull:  Referable[Bool],
  full:        Referable[Bool],
  error:       Referable[Bool])

private enum AsyncDirectionKind:
  case Push, Pop

/** One half of the dual-clock controller. Push and pop instantiate the same logic with opposite pointer roles. */
private final class AsyncDirection(
  clock:              Ref[Clock],
  requestN:           Referable[Bool],
  parameter:          AsyncQueueParameter,
  geometry:           PointerGeometry,
  resetScope:         ResetScope,
  almostEmptyLevel:   Int,
  almostFullLevel:    Int,
  synchronizerStages: Int,
  kind:               AsyncDirectionKind
)(
  using Arena,
  Context,
  Block,
  sourcecode.File,
  sourcecode.Line,
  sourcecode.Name.Machine,
  InstanceContext):
  private val addressWidth = geometry.addressWidth
  private val pointerWidth = geometry.pointerWidth

  private val state =
    ClockScope.posedge(clock):
      resetScope:
        AsyncDirectionState(
          pointer = RegInit(0.U(pointerWidth)),
          address = RegInit(0.U(addressWidth)),
          grayPointer = RegInit(0.U(pointerWidth)),
          wordCount = RegInit(0.U(pointerWidth)),
          notEmpty = RegInit(false.B),
          notAlmostEmpty = RegInit(false.B),
          halfFull = RegInit(false.B),
          almostFull = RegInit(false.B),
          full = RegInit(false.B),
          error = RegInit(false.B)
        )

  def grayPointer: Referable[UInt] = state.grayPointer

  /** Synchronize the opposite endpoint's pointer and update this endpoint's local occupancy estimate and status. */
  def build(remoteGrayPointer: Referable[UInt]): AsyncDirectionOutput =
    ClockScope.posedge(clock):
      resetScope:
        val pointerMask    = (1 << pointerWidth) - 1
        // A non-power-of-two queue uses a centered interval of the binary pointer ring. The unused states are split
        // equally below and above that interval so wraparound remains symmetric.
        val startCount     = geometry.leftOverCount / 2
        val endCount       = pointerMask - startCount
        val startGray      = startCount ^ (startCount >> 1)
        val halfFullLevel  = (parameter.depth + 1) / 2
        val almostFullMark = parameter.depth - almostFullLevel

        // Only Gray code crosses the clock boundary, so adjacent source pointer values differ by one bit. The final
        // synchronizer stage is the only remote pointer consumed by this clock domain.
        val synchronizedRemotePointer =
          val stages = Seq.fill(synchronizerStages)(RegInit(0.U(pointerWidth)))
          stages.head := remoteGrayPointer
          stages.tail
            .zip(stages)
            .foreach: (sink, source) =>
              sink := source
          Node(stages.last)

        // Pointer registers use a reset-relative encoding whose reset value is zero. Restore the centered encoding
        // before doing pointer arithmetic, and apply the same bias to the synchronized Gray pointer.
        val startCountWord = startCount.U(pointerWidth)
        val biasedPointer  = (state.pointer.asBits ^ startCountWord.asBits).asUInt
        val remoteGray     = (synchronizedRemotePointer.asBits ^ startGray.U(pointerWidth).asBits).asUInt
        val remoteBinary   = QueueHelper.grayToBinary(remoteGray, pointerWidth)

        // A request at the local full/empty boundary is rejected and reported; only an accepted request advances.
        val atBoundary = kind match
          case AsyncDirectionKind.Push => Node(state.full)
          case AsyncDirectionKind.Pop  => !Node(state.notEmpty)
        val request    = !requestN
        val advance    = request & !atBoundary
        val errorEvent = request & atBoundary

        val pointerIncrementer = Incrementer.instantiate(BKAIncrementerParameter(pointerWidth))
        pointerIncrementer.io.A := biasedPointer.asBits
        val incrementedPointer = pointerIncrementer.io.SUM.asUInt
        val incremented        = advance ? (incrementedPointer, biasedPointer)
        val advancedPointer    =
          if geometry.needsCorrection then
            // Skip the reserved pointer-ring interval instead of producing an address outside the effective RAM.
            val result = Wire(UInt(pointerWidth))
            when((biasedPointer =/= endCount.U(pointerWidth)) | !advance) {
              result := incremented
            }.otherwise {
              result := startCountWord
            }
            Node(result)
          else incremented

        // Occupancy is always writePointer - readPointer, irrespective of which endpoint is being elaborated.
        val (writePointer, readPointer) = kind match
          case AsyncDirectionKind.Push => (advancedPointer, remoteBinary)
          case AsyncDirectionKind.Pop  => (remoteBinary, advancedPointer)
        val wordCountSubtractor         = BrentKungAdder.instantiate(BrentKungAdderParameter(pointerWidth, 4))
        val wordCountSubtractIO         =
          wordCountSubtractor.io.asInstanceOf[Interface[PrefixAdderIO[BrentKungAdderParameter]]]
        wordCountSubtractIO.A  := writePointer.asBits
        wordCountSubtractIO.B  := ~readPointer.asBits
        wordCountSubtractIO.CI := true.B
        val rawWordCount = wordCountSubtractIO.SUM.asUInt
        val wrapped      = writePointer < readPointer

        // Wrapped subtraction includes the reserved codes in a non-power-of-two ring. Remove those codes from the
        // occupancy and translate the centered pointer interval to a zero-based RAM address.
        val (correctedWordCount, nextAddress): (Node[UInt], Node[UInt]) =
          if !geometry.needsCorrection then (rawWordCount, advancedPointer.asBits.bits(addressWidth - 1, 0).asUInt)
          else
            val highWidth = pointerWidth - geometry.shift
            val rawHigh   = rawWordCount.asBits.bits(pointerWidth - 1, geometry.shift).asUInt
            val correctedHigh: Node[UInt] =
              if geometry.residual == 1 then
                val decrementSubtractor = BrentKungAdder.instantiate(BrentKungAdderParameter(highWidth, 4))
                val decrementSubtractIO =
                  decrementSubtractor.io.asInstanceOf[Interface[PrefixAdderIO[BrentKungAdderParameter]]]
                decrementSubtractIO.A  := rawHigh.asBits
                decrementSubtractIO.B  := ~1.U(highWidth).asBits
                decrementSubtractIO.CI := true.B
                val decremented = decrementSubtractIO.SUM.asUInt
                wrapped ? (decremented, rawHigh)
              else
                val correctionSubtractor = BrentKungAdder.instantiate(BrentKungAdderParameter(highWidth, 4))
                val correctionSubtractIO =
                  correctionSubtractor.io.asInstanceOf[Interface[PrefixAdderIO[BrentKungAdderParameter]]]
                correctionSubtractIO.A  := rawHigh.asBits
                correctionSubtractIO.B  := ~geometry.residual.U(highWidth).asBits
                correctionSubtractIO.CI := true.B
                val subtracted   = correctionSubtractIO.SUM.asUInt
                // Keep this selection bitwise to preserve the reference combinational structure without GTECH cells.
                val selectedBits = Vector.tabulate(highWidth): bit =>
                  wrapped ? (subtracted.asBits.bit(bit), rawHigh.asBits.bit(bit))
                Node(
                  selectedBits.tail.foldLeft[Referable[Bits]](selectedBits.head.asBits): (word, bit) =>
                    bit.asBits ## word
                ).asUInt

            val correctedCount: Node[UInt] =
              if geometry.shift == 0 then correctedHigh
              else (correctedHigh.asBits ## rawWordCount.asBits.bits(geometry.shift - 1, 0)).asUInt

            val addressShift      = geometry.shift - 1
            val addressHighWidth  = pointerWidth - addressShift
            val addressSubtractor = BrentKungAdder.instantiate(BrentKungAdderParameter(addressHighWidth, 4))
            val addressSubtractIO =
              addressSubtractor.io.asInstanceOf[Interface[PrefixAdderIO[BrentKungAdderParameter]]]
            addressSubtractIO.A  := advancedPointer.asBits.bits(pointerWidth - 1, addressShift)
            addressSubtractIO.B  := ~(startCount >> addressShift).U(addressHighWidth).asBits
            addressSubtractIO.CI := true.B
            val addressHigh = addressSubtractIO.SUM.asUInt
            val correctedAddress: Node[UInt] =
              if addressShift == 0 then addressHigh
              else (addressHigh.asBits ## advancedPointer.asBits.bits(addressShift - 1, 0)).asUInt

            (correctedCount, correctedAddress.asBits.bits(addressWidth - 1, 0).asUInt)

        // Status is computed from the prospective local pointer and synchronized remote pointer, then registered in
        // this endpoint's clock domain. It is intentionally a local, synchronization-delayed occupancy view.
        val nextEmpty       = correctedWordCount === 0.U(pointerWidth)
        val nextFull        = correctedWordCount === parameter.depth.U(pointerWidth)
        val nextAlmostEmpty = correctedWordCount <= (almostEmptyLevel & pointerMask).U(pointerWidth)
        val nextHalfFull    = correctedWordCount >= (halfFullLevel & pointerMask).U(pointerWidth)
        val nextAlmostFull  = correctedWordCount >= (almostFullMark & pointerMask).U(pointerWidth)
        val nextError       = if parameter.stickyError then errorEvent | state.error else errorEvent

        // Reapply the reset-relative bias before storing binary and Gray pointers; both registers therefore reset to
        // zero even though arithmetic uses the centered pointer interval.
        val nextGray       = QueueHelper.binaryToGray(advancedPointer, pointerWidth)
        val nextPointer    = (advancedPointer.asBits ^ startCountWord.asBits).asUInt
        val nextStoredGray = (nextGray.asBits ^ startGray.U(pointerWidth).asBits).asUInt

        when(advance) {
          state.pointer     := nextPointer
          state.address     := nextAddress
          state.grayPointer := nextStoredGray
        }
        state.wordCount      := correctedWordCount
        state.notEmpty       := !nextEmpty
        state.notAlmostEmpty := !nextAlmostEmpty
        state.halfFull       := nextHalfFull
        state.almostFull     := nextAlmostFull
        state.full           := nextFull
        state.error          := nextError

        AsyncDirectionOutput(
          address =
            // Corrected addresses must be registered; a power-of-two queue can use the current pointer bits directly.
            if geometry.needsCorrection then state.address
            else biasedPointer.asBits.bits(addressWidth - 1, 0).asUInt,
          grayPointer = state.grayPointer,
          wordCount = state.wordCount,
          empty = !state.notEmpty,
          almostEmpty = !state.notAlmostEmpty,
          halfFull = state.halfFull,
          almostFull = state.almostFull,
          full = state.full,
          error = state.error
        )

@generator
object AsyncQueue extends Generator[AsyncQueueParameter, AsyncQueueLayers, AsyncQueueIO, AsyncQueueProbe]:
  override def moduleName(p: AsyncQueueParameter): String =
    s"AsyncQueue_width${p.width}_depth${p.depth}_pushAlmostEmptyLevel${p.pushAlmostEmptyLevel}" +
      s"_pushAlmostFullLevel${p.pushAlmostFullLevel}_popAlmostEmptyLevel${p.popAlmostEmptyLevel}" +
      s"_popAlmostFullLevel${p.popAlmostFullLevel}" +
      s"_stickyError${p.stickyError}_pushSync${p.pushSync}_popSync${p.popSync}" +
      s"_asyncReset${p.asyncReset}_resetMem${p.resetMem}"

  def expectedGtechCells(p: AsyncQueueParameter): Map[String, Int] = Map.empty

  def architecture(parameter: AsyncQueueParameter) =
    val io           = summon[Interface[AsyncQueueIO]]
    given ClockScope = ClockScope.posedge(io.push.clock)

    val geometry             = PointerGeometry(parameter.depth)
    val controllerResetScope =
      if parameter.asyncReset then ResetScope.asyncActiveLow(io.resetN)
      else ResetScope.syncActiveLow(io.resetN)

    val pushDirection = new AsyncDirection(
      parameter = parameter,
      geometry = geometry,
      resetScope = controllerResetScope,
      clock = io.push.clock,
      requestN = io.push.requestN,
      almostEmptyLevel = parameter.pushAlmostEmptyLevel,
      almostFullLevel = parameter.pushAlmostFullLevel,
      synchronizerStages = parameter.pushSync,
      kind = AsyncDirectionKind.Push
    )
    val popDirection  = new AsyncDirection(
      parameter = parameter,
      geometry = geometry,
      resetScope = controllerResetScope,
      clock = io.pop.clock,
      requestN = io.pop.requestN,
      almostEmptyLevel = parameter.popAlmostEmptyLevel,
      almostFullLevel = parameter.popAlmostFullLevel,
      synchronizerStages = parameter.popSync,
      kind = AsyncDirectionKind.Pop
    )

    // Each endpoint synchronizes only the other endpoint's Gray pointer. Queue data crosses through the dual-clock RAM.
    val push = pushDirection.build(popDirection.grayPointer)
    val pop  = popDirection.build(pushDirection.grayPointer)

    io.push.empty       := push.empty
    io.push.almostEmpty := push.almostEmpty
    io.push.halfFull    := push.halfFull
    io.push.almostFull  := push.almostFull
    io.push.full        := push.full
    io.push.error       := push.error
    io.pop.empty        := pop.empty
    io.pop.almostEmpty  := pop.almostEmpty
    io.pop.halfFull     := pop.halfFull
    io.pop.almostFull   := pop.almostFull
    io.pop.full         := pop.full
    io.pop.error        := pop.error

    layer("Verification"):
      val lastAddress = (parameter.depth - 1) & ((1 << geometry.addressWidth) - 1)

      posedge(io.push.clock):
        val pushRequest  = !io.push.requestN
        val pushAccepted = pushRequest & !push.full
        Cover(pushAccepted.S, io.resetN.asBool, "async_queue_push_accept")
        Cover((pushAccepted & push.empty).S, io.resetN.asBool, "async_queue_push_empty_to_nonempty")
        Cover(push.halfFull.S, io.resetN.asBool, "async_queue_push_reach_half_full")
        Cover(push.almostFull.S, io.resetN.asBool, "async_queue_push_reach_almost_full")
        Cover(push.full.S, io.resetN.asBool, "async_queue_push_reach_full")
        Cover(
          (pushAccepted & (push.address === lastAddress.U(geometry.addressWidth))).S,
          io.resetN.asBool,
          "async_queue_write_wrap"
        )
        Cover((pushRequest & push.full).S, io.resetN.asBool, "async_queue_overflow_request")
        if parameter.stickyError then Cover(push.error.S, io.resetN.asBool, "async_queue_push_sticky_error_retained")

      posedge(io.pop.clock):
        val popRequest  = !io.pop.requestN
        val popAccepted = popRequest & !pop.empty
        Cover(popAccepted.S, io.resetN.asBool, "async_queue_pop_accept")
        Cover(
          (popAccepted & (pop.wordCount === 1.U(geometry.pointerWidth))).S,
          io.resetN.asBool,
          "async_queue_pop_to_empty"
        )
        Cover(pop.halfFull.S, io.resetN.asBool, "async_queue_pop_reach_half_full")
        Cover(
          (popAccepted & (pop.address === lastAddress.U(geometry.addressWidth))).S,
          io.resetN.asBool,
          "async_queue_read_wrap"
        )
        Cover((popRequest & pop.empty).S, io.resetN.asBool, "async_queue_underflow_request")
        if parameter.stickyError then Cover(pop.error.S, io.resetN.asBool, "async_queue_pop_sticky_error_retained")

    // The push clock owns RAM writes. An active-low request writes only when the push-domain full flag is clear.
    val writeEnable = !(push.full | io.push.requestN)
    val ram         = Ram.instantiate(
      RamParameter(parameter.width, parameter.effectiveDepth, parameter.asyncReset, parameter.resetMem)
    )
    ram.io.clock        := io.push.clock
    ram.io.resetN       := io.resetN
    ram.io.chipSelectN  := false.B
    ram.io.writeN       := !writeEnable
    ram.io.readAddress  := pop.address
    ram.io.writeAddress := push.address
    ram.io.writeData    := io.dataIn

    io.dataOut := ram.io.readData
