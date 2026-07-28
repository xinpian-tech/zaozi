// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class IllegalConnectFlow %s --
// RUN: rm -rf %t && mkdir -p %t && cd %t
// RUN: %{test} config %t/config.json --width 8
// RUN: %{test} design %t/config.json
// RUN: not firtool %t/IllegalConnectFlow*.mlirbc 2>&1 | FileCheck %s
// RUN: rm -rf %t

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.Interface
import me.jiuyang.zaozi.valuetpe.*

import java.lang.foreign.Arena

case class IllegalConnectFlowParameter(width: Int) extends Parameter
given upickle.default.ReadWriter[IllegalConnectFlowParameter] = upickle.default.macroRW

class IllegalConnectFlowLayers(parameter: IllegalConnectFlowParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class IllegalConnectFlowProbe(parameter: IllegalConnectFlowParameter)
    extends DVBundle[IllegalConnectFlowParameter, IllegalConnectFlowLayers](parameter)

class IllegalConnectFlowEmptyIO(parameter: IllegalConnectFlowParameter) extends HWBundle(parameter)

class Handshake(
  width: Int
)(
  using TypeImpl,
  ConstructorApi)
    extends Bundle:
  val ready = Flipped(summon[ConstructorApi].Bool())
  val valid = Aligned(summon[ConstructorApi].Bool())
  val bits  = Aligned(summon[ConstructorApi].UInt(width))

class ProducerIO(parameter: IllegalConnectFlowParameter) extends HWBundle(parameter):
  val out = Aligned(new Handshake(parameter.width))

class ConsumerIO(parameter: IllegalConnectFlowParameter) extends HWBundle(parameter):
  val in = Flipped(new Handshake(parameter.width))

@generator
object Producer
    extends Generator[IllegalConnectFlowParameter, IllegalConnectFlowLayers, ProducerIO, IllegalConnectFlowProbe]:
  def architecture(parameter: IllegalConnectFlowParameter) =
    val io = summon[Interface[ProducerIO]]
    io.out.valid := io.out.ready

@generator
object Consumer
    extends Generator[IllegalConnectFlowParameter, IllegalConnectFlowLayers, ConsumerIO, IllegalConnectFlowProbe]:
  def architecture(parameter: IllegalConnectFlowParameter) =
    val io = summon[Interface[ConsumerIO]]
    io.in.ready := io.in.valid

@generator
object IllegalConnectFlow
    extends Generator[
      IllegalConnectFlowParameter,
      IllegalConnectFlowLayers,
      IllegalConnectFlowEmptyIO,
      IllegalConnectFlowProbe
    ]:
  def architecture(parameter: IllegalConnectFlowParameter) =
    val producer = Producer.instantiate(parameter)
    val consumer = Consumer.instantiate(parameter)
    // CHECK: invalid flow
    // CHECK-SAME: source flow
    producer.io.out :<>= consumer.io.in
