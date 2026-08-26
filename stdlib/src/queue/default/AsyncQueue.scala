// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib.queue.default

import java.lang.foreign.Arena

import me.jiuyang.stdlib.default.{Ram, RamParameter}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** Operation-oriented implementation of `DW_fifo_s2_sf`.
  *
  * The controller, directional pointer logic, and synchronizers are elaboration helpers. [[Ram]] is the only queue
  * child module.
  */
case class AsyncQueueParameter(
  width:                Int,
  depth:                Int,
  pushAlmostEmptyLevel: Int,
  pushAlmostFullLevel:  Int,
  popAlmostEmptyLevel:  Int,
  popAlmostFullLevel:   Int,
  stickyError:          Boolean,
  pushSync:             Int,
  popSync:              Int,
  asyncReset:           Boolean,
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
  def addressWidth:   Int = QueueHelper.bitWidth(depth)
  def effectiveDepth: Int = QueueHelper.effectiveDepth(depth)

given upickle.default.ReadWriter[AsyncQueueParameter] = upickle.default.macroRW

class AsyncQueueLayers(parameter: AsyncQueueParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class AsyncQueuePortIO extends Bundle:
  val clock       = Flipped(Clock())
  val requestN    = Flipped(Bool())
  val empty       = Aligned(Bool())
  val almostEmpty = Aligned(Bool())
  val halfFull    = Aligned(Bool())
  val almostFull  = Aligned(Bool())
  val full        = Aligned(Bool())
  val error       = Aligned(Bool())

class AsyncQueueIO(parameter: AsyncQueueParameter) extends HWBundle(parameter):
  val resetN  = Flipped(Reset())
  val push    = Aligned(new AsyncQueuePortIO)
  val pop     = Aligned(new AsyncQueuePortIO)
  val dataIn  = Flipped(UInt(parameter.width))
  val dataOut = Aligned(UInt(parameter.width))

class AsyncQueueProbe(parameter: AsyncQueueParameter) extends DVBundle[AsyncQueueParameter, AsyncQueueLayers](parameter)

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

  def build(remoteGrayPointer: Referable[UInt]): AsyncDirectionOutput =
    ClockScope.posedge(clock):
      resetScope:
        val pointerMask    = (1 << pointerWidth) - 1
        val startCount     = geometry.leftOverCount / 2
        val endCount       = pointerMask - startCount
        val startGray      = startCount ^ (startCount >> 1)
        val halfFullLevel  = (parameter.depth + 1) / 2
        val almostFullMark = parameter.depth - almostFullLevel

        val synchronizedRemotePointer =
          val stages = Seq.fill(synchronizerStages)(RegInit(0.U(pointerWidth)))
          stages.head := remoteGrayPointer
          stages.tail
            .zip(stages)
            .foreach: (sink, source) =>
              sink := source
          Node(stages.last)

        val startCountWord = startCount.U(pointerWidth)
        val biasedPointer  = (state.pointer.asBits ^ startCountWord.asBits).asUInt
        val remoteGray     = (synchronizedRemotePointer.asBits ^ startGray.U(pointerWidth).asBits).asUInt
        val remoteBinary   = QueueHelper.grayToBinary(remoteGray, pointerWidth)

        val atBoundary = kind match
          case AsyncDirectionKind.Push => Node(state.full)
          case AsyncDirectionKind.Pop  => !Node(state.notEmpty)
        val request    = !requestN
        val advance    = request & !atBoundary
        val errorEvent = request & atBoundary

        val increment       = advance ? (1.U(pointerWidth), 0.U(pointerWidth))
        val incremented     = (biasedPointer + increment).asBits.bits(pointerWidth - 1, 0).asUInt
        val advancedPointer =
          if geometry.needsCorrection then
            val result = Wire(UInt(pointerWidth))
            when((biasedPointer =/= endCount.U(pointerWidth)) | !advance) {
              result := incremented
            }.otherwise {
              result := startCountWord
            }
            Node(result)
          else incremented

        val (writePointer, readPointer) = kind match
          case AsyncDirectionKind.Push => (advancedPointer, remoteBinary)
          case AsyncDirectionKind.Pop  => (remoteBinary, advancedPointer)
        val rawWordCount                = (writePointer - readPointer).asBits.bits(pointerWidth - 1, 0).asUInt
        val wrapped                     = writePointer < readPointer

        val (correctedWordCount, nextAddress): (Node[UInt], Node[UInt]) =
          if !geometry.needsCorrection then (rawWordCount, advancedPointer.asBits.bits(addressWidth - 1, 0).asUInt)
          else
            val highWidth = pointerWidth - geometry.shift
            val rawHigh   = rawWordCount.asBits.bits(pointerWidth - 1, geometry.shift).asUInt
            val correctedHigh: Node[UInt] =
              if geometry.residual == 1 then
                val decremented = (rawHigh - 1.U(highWidth)).asBits.bits(highWidth - 1, 0).asUInt
                wrapped ? (decremented, rawHigh)
              else
                val subtracted   =
                  (rawHigh - geometry.residual.U(highWidth)).asBits.bits(highWidth - 1, 0).asUInt
                val selectedBits = Vector.tabulate(highWidth): bit =>
                  wrapped ? (subtracted.asBits.bit(bit), rawHigh.asBits.bit(bit))
                Node(
                  selectedBits.tail.foldLeft[Referable[Bits]](selectedBits.head.asBits): (word, bit) =>
                    bit.asBits ## word
                ).asUInt

            val correctedCount: Node[UInt] =
              if geometry.shift == 0 then correctedHigh
              else (correctedHigh.asBits ## rawWordCount.asBits.bits(geometry.shift - 1, 0)).asUInt

            val addressShift     = geometry.shift - 1
            val addressHighWidth = pointerWidth - addressShift
            val addressHigh      =
              (advancedPointer.asBits.bits(pointerWidth - 1, addressShift).asUInt -
                (startCount >> addressShift).U(addressHighWidth)).asBits.bits(addressHighWidth - 1, 0).asUInt
            val correctedAddress: Node[UInt] =
              if addressShift == 0 then addressHigh
              else (addressHigh.asBits ## advancedPointer.asBits.bits(addressShift - 1, 0)).asUInt

            (correctedCount, correctedAddress.asBits.bits(addressWidth - 1, 0).asUInt)

        val nextEmpty       = correctedWordCount === 0.U(pointerWidth)
        val nextFull        = correctedWordCount === parameter.depth.U(pointerWidth)
        val nextAlmostEmpty = correctedWordCount <= (almostEmptyLevel & pointerMask).U(pointerWidth)
        val nextHalfFull    = correctedWordCount >= (halfFullLevel & pointerMask).U(pointerWidth)
        val nextAlmostFull  = correctedWordCount >= (almostFullMark & pointerMask).U(pointerWidth)
        val nextError       = if parameter.stickyError then errorEvent | state.error else errorEvent

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
