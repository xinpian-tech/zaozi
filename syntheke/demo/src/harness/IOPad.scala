package me.jiuyang.syntheke.demo.harness

import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.zaozi.{DVBundle, Generator, HWBundle, LayerInterface, Parameter}
import me.jiuyang.zaozi.default.{generator as zaoziGenerator, *, given}
import me.jiuyang.zaozi.reftpe.Interface
import upickle.default.ReadWriter


case class IOPadP() extends Parameter derives ReadWriter

class IOPadPLayers(p: IOPadP) extends LayerInterface(p):
  def layers = Seq.empty
class IOPadPProbe(p: IOPadP) extends DVBundle[IOPadP, IOPadPLayers](p)
class IOPadPIO(p: IOPadP) extends HWBundle(p):
  val pin = Flipped(new IOBundle)
  val external = Flipped(Bool())
  val value = Aligned(Bool())

@zaoziGenerator
object IOPadGen extends Generator[IOPadP, IOPadPLayers, IOPadPIO, IOPadPProbe]:

  def architecture(p: IOPadP) =
    val io = summon[Interface[IOPadPIO]]
    io.value := io.pin.outputEnable ? (io.pin.outputValue, io.external)
    io.pin.inputValue := io.value & io.pin.inputEnable
