package me.jiuyang.syntheke.demo.zaoziimpl

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.stdlib.mmio.*
import upickle.default.ReadWriter

case class PowerControllerP(base: Long, addrBits: Int, dataBits: Int, idBits: Int)
    extends Parameter derives ReadWriter:
  require(dataBits == 32, s"power controller requires 32-bit data, got $dataBits")
  require(base >= 0 && base % 4 == 0 && BigInt(base) + 8 < (BigInt(1) << addrBits), "power controller registers exceed address width")
  def shape: AxiShape = AxiShape(addrBits, dataBits, idBits)
  val controlField = RegField("control", 2).readValue.writeValue
  val cpu0Status = RegField("cpu0Status", 4).readValue
  val cpu1Status = RegField("cpu1Status", 4).readValue
  val regMap = RegMapDefinition(addrBits - 2, 32, 1, true, Layer("Verification"), Seq(
    RegMapRegister(BigInt(base), Seq(controlField)),
    RegMapRegister(BigInt(base) + 4, Seq(cpu0Status)),
    RegMapRegister(BigInt(base) + 8, Seq(cpu1Status))
  ))

class PowerControllerPLayers(p: PowerControllerP) extends LayerInterface(p):
  def layers = Seq(p.regMap.assertionLayer)
class PowerControllerPProbe(p: PowerControllerP) extends DVBundle[PowerControllerP, PowerControllerPLayers](p)
class PowerControllerPIO(p: PowerControllerP) extends HWBundle(p):
  val clk = Flipped(new ClockBundle)
  val in = Flipped(new AxiPortBundle(p.shape))
  val cpu0 = Aligned(new PowerControlBundle)
  val cpu1 = Aligned(new PowerControlBundle)

@generator
object PowerControllerGen extends Generator[PowerControllerP, PowerControllerPLayers, PowerControllerPIO, PowerControllerPProbe]:
  def architecture(p: PowerControllerP) =
    val io = summon[Interface[PowerControllerPIO]]
    given ClockScope = ClockScope.posedge(io.clk.clock)
    given ResetScope = ResetScope.asyncActiveHigh(io.clk.reset)

    val control = RegInit(3.B(2))
    io.cpu0.requestOn := control.bits(0, 0).asBool
    io.cpu1.requestOn := control.bits(1, 1).asBool

    val (req, rsp) = AxiRegMap(io.in, p.shape)
    p.regMap(req, rsp)(
      p.controlField.read(control),
      p.controlField.write(control),
      p.cpu0Status.read(io.cpu0.isolated.asBits ## io.cpu0.powerGood.asBits ## io.cpu0.busy.asBits ## io.cpu0.on.asBits),
      p.cpu1Status.read(io.cpu1.isolated.asBits ## io.cpu1.powerGood.asBits ## io.cpu1.busy.asBits ## io.cpu1.on.asBits)
    )
