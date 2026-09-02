// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}

val Core = new GeneratorEntry[CoreP]

/** One core ([[CoreGen]], the real DitDah32 RV32EC behind a widening shim): a boundary outward node with a local id
  * space; the master is named after the instance.
  */
final class CoreNodes(
  name:        String,
  idBits:      Int,
  maxFlight:   Int,
  resetPc:     Int,
  enableDebug: Boolean,
  enableTrace: Boolean
)(
  using GeneratorScope[CoreP])
    extends Nodes:
  val clk               = inward(ClockDomain).uFn(_ => Right(()))
  private val debugNode = Option.when(enableDebug) {
    given sourcecode.Name = sourcecode.Name("debug")
    inward(DebugInterrupt).uFn(_ => Right(DebugHartCap(CoreP.xlen)))
  }

  /** The hart's debug port, present only on a core built with one. */
  def debug: DebugInterrupt.Inward =
    require(debugNode.isDefined, s"core '$name' was built without a debug node")
    debugNode.get

  /** The hart's instruction trace. A declaration, not a connection: the framework carries every leaf to the testbench
    * on its own, so nothing in the topology mentions it.
    */
  private val traceSource = Option.when(enableTrace) {
    given sourcecode.Name = sourcecode.Name("trace")
    dvSource(RvTrace)(RvTraceShape(CoreP.xlen, CoreP.regIndexBits), traceLayer)
  }

  parameters { view =>
    val s = shapeOf(view, mem)
    Right(CoreP(resetPc, s.addrBits, s.dataBits, s.idBits, enableDebug, enableTrace))
  }
  val mem =
    outward(Axi4).dFn(_ =>
      Right(AxiMasterPort(Vector(AxiMasterParams(name, IdRange(0, 1 << idBits), maxFlight = Some(maxFlight)))))
    )

def core(
  idBits:      Int,
  maxFlight:   Int,
  resetPc:     Int,
  enableDebug: Boolean,
  enableTrace: Boolean
)(
  using
  ws:          WrapperScope,
  name:        sourcecode.Name,
  file:        sourcecode.File,
  line:        sourcecode.Line
): CoreNodes =
  generator(Core)(new CoreNodes(name.value, idBits, maxFlight, resetPc, enableDebug, enableTrace))
