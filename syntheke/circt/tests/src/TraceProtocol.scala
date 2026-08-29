// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.circt.tests

import me.jiuyang.syntheke.*
import upickle.default.ReadWriter

/** The core's instruction trace, as a verification protocol: what a hart publishes about every instruction it retires —
  * the commit itself, the register file traffic behind it, the memory access, the CSR access, and the trap and
  * interrupt state around it. This is DitDah32's whole trace surface, shaped by the hart's register widths.
  *
  * A [[me.jiuyang.syntheke.dvSource]] of this protocol is a declaration, not a connection: the framework forwards every
  * leaf up the hierarchy on its own, into the testbench's matching data input. Nothing in the design graph carries it.
  */
final case class RvTraceShape(xlen: Int, regIndexBits: Int) derives ReadWriter:
  require(xlen > 0, s"xlen $xlen must be positive")
  require(regIndexBits > 0, s"register index width $regIndexBits must be positive")

object RvTrace extends DVProtocol:
  type Down = RvTraceShape

  def interfaceOf(down: RvTraceShape, layer: LayerPath): ProtocolBundle =
    import ProtocolInterface.*
    val word                                      = UInt(down.xlen)
    def p(t:        ProtocolInterface)            = Probe(t, layer)
    def field(name: String, t: ProtocolInterface) = Field(name, p(t))
    ProtocolBundle(
      // the commit
      field("valid", Bool),
      field("pc", word),
      field("nextPc", word),
      field("instr", word),
      field("len", UInt(3)),
      // the register file behind it
      field("rdWe", Bool),
      field("rd", UInt(down.regIndexBits)),
      field("rdWdata", word),
      field("rs1Addr", UInt(5)),
      field("rs1Rdata", word),
      field("rs2Addr", UInt(5)),
      field("rs2Rdata", word),
      // the memory access
      field("memAddr", word),
      field("memRmask", UInt(4)),
      field("memWmask", UInt(4)),
      field("memRdata", word),
      field("memWdata", word),
      field("memFault", Bool),
      field("memFaultRmask", UInt(4)),
      field("memFaultWmask", UInt(4)),
      // the CSR access
      field("csrAddr", UInt(12)),
      field("csrRmask", word),
      field("csrWmask", word),
      field("csrRdata", word),
      field("csrWdata", word),
      // traps and interrupts
      field("trap", Bool),
      field("trapCause", UInt(4)),
      field("mstatus", word),
      field("mstatusPostCommit", word),
      field("mstatusPreTrap", word),
      field("mie", word),
      field("mtvec", word),
      field("mepc", word),
      field("mtval", word),
      field("mip", word),
      field("mcause", word),
      field("irqPendingMask", word)
    )

  val downRW: upickle.default.ReadWriter[RvTraceShape] = summon

/** The FIRRTL layer the trace is confined to — the same one the vendored core colours its probes with, so the release
  * netlist carries none of it.
  */
val traceLayer: LayerPath = LayerPath(Vector("DV"))
