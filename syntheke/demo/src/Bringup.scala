package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.zaoziimpl.DmP

object Bringup:

  val chipName: String = "syntheke-demo"

  def designEnv(config: SocConfig): String =
    s"""JTAG_BRIDGE=127.0.0.1:${config.jtagPort}
       |CHIP=$chipName
       |LOAD=0x${config.loadBase.toHexString}
       |UART_BASE=0x${config.uartBase.toHexString}
       |POWER_BASE=0x${config.powerBase.toHexString}
       |""".stripMargin

  def probeRsTarget(resolved: ResolvedDesign): String =
    val root  = ModuleId.root
    val tap   = resolved.edgeAt(ModuleNodeId(root / "harness", "jtagPins")).edgeAs(Jtag)
    val harts = resolved.generatorModule(root / "debug" / "dm").get.fullParam.asInstanceOf[DmP].harts
    val ram   = resolved.edgeAt(ModuleNodeId(root / "memory", "in")).edgeAs(Axi4).slave.slaves.head.address.head
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
