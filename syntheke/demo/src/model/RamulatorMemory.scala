package me.jiuyang.syntheke.demo.model

import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.zaozi.{DVBundle, Generator, HWBundle, LayerInterface, Parameter, VerilogParameter, VerilogWrapper}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.Interface
import upickle.default.ReadWriter

case class RamulatorMemoryP(configFile: String, base: Long, periodPs: Long, shape: AxiShape) extends Parameter
    derives ReadWriter:
  require(configFile.nonEmpty, "the memory subsystem model needs a Ramulator configuration")
  require(base >= 0, s"base 0x${base.toHexString} must be non-negative")
  require(periodPs > 0, s"clock period $periodPs ps must be positive")
  require(shape.dataBits == 128, s"the memory subsystem port carries 128-bit beats, got ${shape.dataBits}")
  require(shape.addrBits <= 32, s"the memory subsystem port addresses at most a 32-bit space, got ${shape.addrBits}")
  require(base <= 0xffffffffL, s"base 0x${base.toHexString} must fit the 32-bit space the port addresses")

class RamulatorMemoryPLayers(p: RamulatorMemoryP) extends LayerInterface(p):
  def layers = Seq.empty
class RamulatorMemoryPProbe(p: RamulatorMemoryP)  extends DVBundle[RamulatorMemoryP, RamulatorMemoryPLayers](p)
class RamulatorMemoryIO(p: RamulatorMemoryP)      extends HWBundle(p):
  val clk = Flipped(new ClockBundle)
  val in  = Flipped(new AxiPortRecord(p.shape))

@generator
object MemorySubsystemGen
    extends Generator[RamulatorMemoryP, RamulatorMemoryPLayers, RamulatorMemoryIO, RamulatorMemoryPProbe]:
  def architecture(p: RamulatorMemoryP) =
    val io    = summon[Interface[RamulatorMemoryIO]]
    val model = RamulatorMemory.instantiate(p)
    model.io.clk.clock := io.clk.clock
    model.io.clk.reset := io.clk.reset
    model.io.in :<>= io.in

case class RamulatorMemoryVerilogP(CONFIG: String, BASE: BigInt, PERIOD_PS: BigInt, ID_W: Int, ADDR_W: Int)
    extends VerilogParameter

@generator
object RamulatorMemory
    extends VerilogWrapper[
      RamulatorMemoryP,
      RamulatorMemoryPLayers,
      RamulatorMemoryIO,
      RamulatorMemoryPProbe,
      RamulatorMemoryVerilogP
    ]:
  def verilogModuleName(p: RamulatorMemoryP) = "DramDpi"
  def verilogParameter(p: RamulatorMemoryP)  =
    RamulatorMemoryVerilogP(p.configFile, BigInt(p.base), BigInt(p.periodPs), p.shape.idBits, p.shape.addrBits)
