// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.axi.Axi4
import me.jiuyang.syntheke.demo.zaoziimpl.DmP

/** What a tool needs to be told about the design in [[Soc]] before it can do anything to it: where the debugger
  * attaches, what the fabric decodes where, and what the chip looks like to a debugger. All of it read back out of the
  * design — the address map from [[Soc]], the rest from the settled edges, which is what the RTL was built from too.
  *
  * The program itself is not here and not in Scala: it is `program/hello.S`, assembled against these same addresses,
  * and where each hart starts comes back from its symbol table rather than from an offset someone counted.
  */
object Bringup:

  /** The name the target description gives this chip, and the debugger is invoked with. */
  val chipName: String = "syntheke-demo"

  /** What the build needs from the design: the addresses the program is assembled against, and where the debugger
    * knocks. The bring-up reads this beside the program's own half.
    */
  def designEnv(config: SocConfig): String =
    s"""JTAG_BRIDGE=127.0.0.1:${config.jtagPort}
       |CHIP=$chipName
       |LOAD=0x${config.loadBase.toHexString}
       |UART_BASE=0x${config.uartBase.toHexString}
       |""".stripMargin

  /** The target description probe-rs needs, read out of the settled design: the TAP it will find on the pins, the harts
    * the debug module holds, the memory the fabric decodes to RAM. Nothing here is stated twice — every number comes
    * from an edge the negotiation settled.
    */
  def probeRsTarget(resolved: ResolvedDesign): String =
    val root  = ModuleId.root
    val tap   = resolved.edgeAt(ModuleNodeId(root / "harness", "jtagPins")).edgeAs(Jtag)
    val harts = resolved.generatorModule(root / "debug" / "dm").get.fullParam.asInstanceOf[DmP].harts
    val ram   = resolved.edgeAt(ModuleNodeId(root / "harness", "memPins")).edgeAs(Axi4).slave.slaves.head.address.head
    val cores = (0 until harts)
      .map(i => s"""      - name: hart$i
                   |        type: riscv
                   |        core_access_options: !Riscv
                   |          hart_id: $i""".stripMargin)
      .mkString("\n")
    s"""name: syntheke
       |variants:
       |  - name: $chipName
       |    cores:
       |$cores
       |    memory_map:
       |      - !Ram
       |        name: dram
       |        range:
       |          start: 0x${ram.base.toHexString}
       |          end: 0x${(ram.base + ram.mask + 1).toHexString}
       |        cores: [${(0 until harts).map(i => s"hart$i").mkString(", ")}]
       |    jtag:
       |      scan_chain:
       |        - name: dtm
       |          ir_len: ${tap.irLength}
       |      force_scan_chain: true
       |""".stripMargin
