package me.jiuyang.syntheke.demo.zaoziimpl

import java.lang.foreign.Arena
import me.jiuyang.stdlib.mmio.*
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}
import upickle.default.ReadWriter

case class GpioP(width: Int, base: Long, addrBits: Int, dataBits: Int, idBits: Int) extends Parameter derives ReadWriter:
  require(width >= 1 && width <= 32, s"gpio width $width must be within 1..32")
  require(dataBits == 32, s"gpio is a 32-bit single-beat slave, got dataBits $dataBits")
  require(base >= 0 && base % 4 == 0 && BigInt(base) + 8 < (BigInt(1) << addrBits))
  def shape: AxiShape = AxiShape(addrBits, dataBits, idBits)
  val output = Vector.tabulate((width + 7) / 8)(i =>
    RegField(s"output$i", math.min(8, width - i * 8)).readValue.writeReadyValid
  )
  val direction = Vector.tabulate((width + 7) / 8)(i =>
    RegField(s"direction$i", math.min(8, width - i * 8)).readValue.writeReadyValid
  )
  val input = RegField("input", width).readValue
  val regMap = RegMapDefinition(addrBits - 2, 32, 1, true, Layer("Verification"), Seq(
    RegMapRegister(BigInt(base), output),
    RegMapRegister(BigInt(base) + 4, direction),
    RegMapRegister(BigInt(base) + 8, Seq(input))
  ))

class GpioPLayers(p: GpioP) extends LayerInterface(p):
  def layers = Seq(p.regMap.assertionLayer)
class GpioPProbe(p: GpioP) extends DVBundle[GpioP, GpioPLayers](p)
class GpioPIO(p: GpioP) extends HWBundle(p):
  val clk  = Flipped(new ClockBundle)
  val in   = Flipped(new AxiPortBundle(p.shape))
  val pins = Aligned(new GpioPinsBundle(p.width))

@generator
object GpioGen extends Generator[GpioP, GpioPLayers, GpioPIO, GpioPProbe]:
  def architecture(p: GpioP) =
    val io           = summon[Interface[GpioPIO]]
    given ClockScope = ClockScope.posedge(io.clk.clock)
    given ResetScope = ResetScope.asyncActiveHigh(io.clk.reset)

    val outReg = RegInit(0.B(p.width))
    val dirReg = RegInit(0.B(p.width))
    val inMeta = RegInit(0.B(p.width))
    val inSync = RegInit(0.B(p.width))
    inMeta := io.pins.in
    inSync := inMeta
    io.pins.out := outReg
    io.pins.oe := dirReg

    val outputWrites = p.output.map(field => (Wire(Bool()), Wire(Bits(field.width))))
    val directionWrites = p.direction.map(field => (Wire(Bool()), Wire(Bits(field.width))))

    def update(reg: Reg[Bits], widths: Vector[Int], writes: Vector[(Wire[Bool], Wire[Bits])])(
      using Arena, Context, Block
    ): Unit =
      val mask = writes.zip(widths).reverse.map { case ((valid, _), width) =>
        valid ? (((BigInt(1) << width) - 1).B(width), 0.B(width))
      }.reduce(_ ## _)
      val data = writes.reverse.map(_._2: Referable[Bits]).reduce(_ ## _)
      when(writes.map(_._1: Referable[Bool]).reduce(_ | _)) {
        reg := (reg & ~mask) | (data & mask)
      }

    update(outReg, p.output.map(_.width), outputWrites)
    update(dirReg, p.direction.map(_.width), directionWrites)

    val (req, rsp) = AxiRegMap(io.in, p.shape)
    val outputAccesses = p.output.zipWithIndex.flatMap { (field, index) =>
      val low = index * 8
      Vector(
        field.read(outReg.bits(low + field.width - 1, low)),
        field.write(true.B, outputWrites(index)._1, outputWrites(index)._2)
      )
    }
    val directionAccesses = p.direction.zipWithIndex.flatMap { (field, index) =>
      val low = index * 8
      Vector(
        field.read(dirReg.bits(low + field.width - 1, low)),
        field.write(true.B, directionWrites(index)._1, directionWrites(index)._2)
      )
    }
    p.regMap(req, rsp)((outputAccesses ++ directionAccesses :+ p.input.read(inSync))*)
