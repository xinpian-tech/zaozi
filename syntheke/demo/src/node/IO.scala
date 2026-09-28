package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.syntheke.zaozi.zaozi

given GeneratorDefinition[SerialIOP] = zaozi(SerialIOGen)
given GeneratorDefinition[GpioIOP] = zaozi(GpioIOGen)
given GeneratorDefinition[JtagIOP] = zaozi(JtagIOGen)

final case class SerialIONodes(tx: IO.Outward, rx: IO.Outward)
final case class JtagIONodes(tms: IO.Outward, tdi: IO.Outward, trstN: IO.Outward, tdo: IO.Outward)

def serialIO(source: Serial.Outward)(using
  WrapperScope, sourcecode.Name, sourcecode.File, sourcecode.Line
): SerialIONodes =
  val (in, pins) = generator[SerialIOP] {
    val in =
      given sourcecode.Name = sourcecode.Name("in")
      inward(Serial)(source.domain(ClockDomain), source.domain(ResetDomain), PowerDomain).fixed(())
    val tx =
      given sourcecode.Name = sourcecode.Name("tx")
      outward(IO)(PowerDomain).fixed(())
    val rx =
      given sourcecode.Name = sourcecode.Name("rx")
      outward(IO)(PowerDomain).fixed(())
    parameters((_, _) => Right(SerialIOP()))
    ((in, SerialIONodes(tx, rx)), Vector.empty)
  }
  in <-- source
  pins

def gpioIO(source: GpioPins.Outward, width: Int)(using
  WrapperScope, sourcecode.Name, sourcecode.File, sourcecode.Line
): Vector[IO.Outward] =
  val (in, pins) = generator[GpioIOP] {
    val in =
      given sourcecode.Name = sourcecode.Name("in")
      inward(GpioPins)(source.domain(ClockDomain), source.domain(ResetDomain), PowerDomain).fixed(())
    val pins = Vector.tabulate(width) { i =>
      given sourcecode.Name = sourcecode.Name(s"pin$i")
      outward(IO)(PowerDomain).fixed(())
    }
    parameters { (view, _) =>
      val actual = view.edgeOf(in)
      if actual == width then Right(GpioIOP(width))
      else Left(Violation(s"GPIO has $actual bits but IO adapter declares $width pins"))
    }
    ((in, pins), Vector.empty)
  }
  in <-- source
  pins

def jtagIO(source: Jtag.Outward)(using
  WrapperScope, sourcecode.Name, sourcecode.File, sourcecode.Line
): JtagIONodes =
  val (in, pins) = generator[JtagIOP] {
    val in =
      given sourcecode.Name = sourcecode.Name("in")
      inward(Jtag)(source.domain(ClockDomain), source.domain(ResetDomain), PowerDomain).fixed(())
    val tms =
      given sourcecode.Name = sourcecode.Name("tms")
      outward(IO)(PowerDomain).fixed(())
    val tdi =
      given sourcecode.Name = sourcecode.Name("tdi")
      outward(IO)(PowerDomain).fixed(())
    val trstN =
      given sourcecode.Name = sourcecode.Name("trstN")
      outward(IO)(PowerDomain).fixed(())
    val tdo =
      given sourcecode.Name = sourcecode.Name("tdo")
      outward(IO)(PowerDomain).fixed(())
    parameters((_, _) => Right(JtagIOP()))
    ((in, JtagIONodes(tms, tdi, trstN, tdo)), Vector.empty)
  }
  in <-- source
  pins
