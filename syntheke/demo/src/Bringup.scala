// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.axi.Axi4
import me.jiuyang.syntheke.demo.zaoziimpl.DmP

/** How the design in [[Soc]] is started: the program a debugger downloads, where each hart begins, and the two files
  * that tell the tools about the chip. None of it is the design — a chip that halts out of reset with nothing in memory
  * is a complete design; this is what someone does to it afterwards.
  *
  * What it knows about the chip it reads back: the address map from [[Soc]], and the rest out of the settled edges,
  * which is the same thing the RTL was built from.
  */
object Bringup:

  /** The name the target description gives this chip, and the debugger is invoked with. */
  val chipName: String = "syntheke-demo"

  // The two harts get different work, so the printed line proves both halves of the debug module's hart array: hart 0
  // parks in the done-spin, hart 1 is the one that runs the program.
  val hart0Pc: Long = Soc.loadBase + 0x2c
  val hart1Pc: Long = Soc.loadBase

  /** The program, hand-assembled RV32E (registers x5–x8): walk the string at +0x40 and write each byte to the UART's
    * TXDATA, polling STATUS bit0 (txBusy) between bytes; a NUL ends the walk at the spin at +0x2c, where hart 0 parks.
    *
    * {{{
    * 00: 100002B7  lui  x5, 0x10000    ; x5 = UART base
    * 04: 80000337  lui  x6, 0x80000    ; x6 = DRAM base
    * 08: 04030313  addi x6, x6, 0x40   ; x6 = &"hello world\n"
    * 0c: 00030383  lb   x7, 0(x6)      ; next char
    * 10: 00038E63  beq  x7, x0, +0x1c  ; NUL -> 2c
    * 14: 0082A403  lw   x8, 8(x5)      ; STATUS
    * 18: 00147413  andi x8, x8, 1      ; txBusy
    * 1c: FE041CE3  bne  x8, x0, -8     ; busy -> 14
    * 20: 0072A023  sw   x7, 0(x5)      ; TXDATA = char
    * 24: 00130313  addi x6, x6, 1
    * 28: FE5FF06F  jal  x0, -0x1c      ; -> 0c
    * 2c: 0000006F  jal  x0, 0          ; done: spin (where hart 0 parks)
    * 40: "hello world\n\0"
    * }}}
    */
  val program: Vector[Long] = Vector(
    0x100002b7L, 0x80000337L, 0x04030313L, 0x00030383L, 0x00038e63L, 0x0082a403L, 0x00147413L, 0xfe041ce3L, 0x0072a023L,
    0x00130313L, 0xfe5ff06fL, 0x0000006fL, 0L, 0L, 0L, 0L, 0x6c6c6568L, 0x6f77206fL, 0x0a646c72L, 0L
  )

  /** The program as the debugger downloads it: little-endian words, which is how memory holds them. */
  def image: Array[Byte] =
    program.flatMap(w => (0 until 4).map(b => ((w >> (b * 8)) & 0xff).toByte)).toArray

  /** What the bring-up script needs about the design it is about to run: where the debugger attaches, what it downloads
    * where, and what each hart should then be seen doing. The script reads this instead of repeating it.
    */
  def env: String =
    f"""JTAG_BRIDGE=127.0.0.1:${Soc.jtagPort}
       |CHIP=$chipName
       |LOAD=0x${Soc.loadBase.toHexString}
       |HART0_PC=0x${hart0Pc.toHexString}
       |HART1_PC=0x${hart1Pc.toHexString}
       |HART1_FIRST='${hart1Pc}%08x: ${program.head}%08x'
       |""".stripMargin

  /** The target description probe-rs needs, read out of the settled design: the TAP it will find on the pins, the harts
    * the debug module holds, the memory the fabric decodes to RAM. Nothing here is stated twice — every number comes
    * from an edge the negotiation settled, which is the same thing the RTL was built from.
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
