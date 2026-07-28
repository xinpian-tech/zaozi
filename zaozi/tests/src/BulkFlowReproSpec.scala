// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozitest

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.Interface
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.testlib.*

import java.lang.foreign.Arena
import utest.*

case class BFParameter(width: Int) extends Parameter
given upickle.default.ReadWriter[BFParameter] = upickle.default.macroRW

class BFLayers(parameter: BFParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class BFProbe(parameter: BFParameter) extends DVBundle[BFParameter, BFLayers](parameter)

class BFEmptyIO(parameter: BFParameter) extends HWBundle(parameter)

class BFDec(
  width: Int
)(
  using TypeImpl,
  ConstructorApi)
    extends Bundle:
  val ready = Flipped(summon[ConstructorApi].Bool())
  val valid = Aligned(summon[ConstructorApi].Bool())
  val bits  = Aligned(summon[ConstructorApi].UInt(width))

class BFProducerIO(parameter: BFParameter) extends HWBundle(parameter):
  val out = Aligned(new BFDec(parameter.width))

class BFConsumerIO(parameter: BFParameter) extends HWBundle(parameter):
  val in = Flipped(new BFDec(parameter.width))

@generator
object BFProducer extends Generator[BFParameter, BFLayers, BFProducerIO, BFProbe]:
  def architecture(parameter: BFParameter) =
    val io = summon[Interface[BFProducerIO]]
    io.out.valid := io.out.ready

@generator
object BFConsumer extends Generator[BFParameter, BFLayers, BFConsumerIO, BFProbe]:
  def architecture(parameter: BFParameter) =
    val io = summon[Interface[BFConsumerIO]]
    io.in.ready := io.in.valid

@generator
object BFParentGood extends Generator[BFParameter, BFLayers, BFEmptyIO, BFProbe] with HasFirrtlTest with HasVerilogTest:
  def architecture(parameter: BFParameter) =
    val producer = BFProducer.instantiate(parameter)
    val consumer = BFConsumer.instantiate(parameter)
    consumer.io.in :<>= producer.io.out

@generator
object BFParentBad extends Generator[BFParameter, BFLayers, BFEmptyIO, BFProbe] with HasFirrtlTest with HasVerilogTest:
  def architecture(parameter: BFParameter) =
    val producer = BFProducer.instantiate(parameter)
    val consumer = BFConsumer.instantiate(parameter)
    producer.io.out :<>= consumer.io.in

@generator
object BFParentSilent
    extends Generator[BFParameter, BFLayers, BFEmptyIO, BFProbe]
    with HasFirrtlTest
    with HasVerilogTest:
  def architecture(parameter: BFParameter) =
    val producer = BFProducer.instantiate(parameter)
    val consumer = BFConsumer.instantiate(parameter)
    consumer.io.in :<>= producer.io.out
    producer.io.out.valid := consumer.io.in.ready

object BulkFlowReproSpec extends TestSuite:
  val tests = Tests:
    test("good order (consumer.io.in :<>= producer.io.out) lowers with the correct handshake direction"):
      BFParentGood.firrtlString(BFParameter(8))
      val v = BFParentGood.verilogString(BFParameter(8))
      assert(v.contains(".in_valid (_producer_out_valid)"))
      assert(v.contains(".out_ready (_consumer_in_ready)"))

    test("bad order (producer.io.out :<>= consumer.io.in) is rejected by the CIRCT pipeline"):
      val e = intercept[RuntimeException](BFParentBad.verilogString(BFParameter(8)))
      assert(e.getMessage.contains("invalid flow"))
      assert(e.getMessage.contains("source flow"))
      assert(BFParentBad.firrtlString(BFParameter(8)).contains("connect producer.out,"))

    test("silent direction typo (producer.io.out.valid := consumer.io.in.ready) is rejected by the CIRCT pipeline"):
      val e = intercept[RuntimeException](BFParentSilent.verilogString(BFParameter(8)))
      assert(e.getMessage.contains("invalid flow"))
      assert(e.getMessage.contains("source flow"))
