// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.chaining

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.constraints.*
import me.jiuyang.rvprobe.cases.chaining.ChainingLib.*

// T1-compatible chaining hazard matrix tests.
//
// Output follows T1 test convention:
//   - User code in `test` function (called from t1_main.S which provides _start)
//   - Memory at SRAM (0x20000000), exit via 0xdeadbeef to 0x10000000
//
// Build:
//   riscv64-unknown-linux-gnu-gcc -march=rv64gcv -mabi=lp64d -nostdlib \
//     -T t1.ld t1_main.S ChainingT1Test.S -o chaining.elf
//
// Run:
//   nix develop -c t1-helper run -i t1emu -c blastoise -e verilator-emu chaining.elf
@main def ChainingT1Test(
  outputPath: String = "rvprobe/src/cases/output/asm/chaining/ChainingT1Test.S"
): Unit =
  // Each cell as a separate generator
  object D1C1 extends RVGenerator { val sets = Seq(isRVV()); def constraints() = explicitRAW_ALUxALU()     }
  object D1C2 extends RVGenerator { val sets = Seq(isRVV()); def constraints() = explicitRAW_ALUxLSU()     }
  object D1C3 extends RVGenerator { val sets = Seq(isRVV()); def constraints() = explicitRAW_MaskxALU()    }
  object D1C4 extends RVGenerator { val sets = Seq(isRVV()); def constraints() = explicitRAW_SlowxFast()   }
  object D1C5 extends RVGenerator { val sets = Seq(isRVV()); def constraints() = explicitRAW_WidenxNormal()}
  object D1C7 extends RVGenerator { val sets = Seq(isRVV()); def constraints() = explicitRAW_SlidexStore() }
  object D2C1 extends RVGenerator { val sets = Seq(isRVV()); def constraints() = implicitV0RAW_ALUxALU()   }
  object D3C1 extends RVGenerator { val sets = Seq(isRVV()); def constraints() = war_ALUxALU()             }
  object D3C2 extends RVGenerator { val sets = Seq(isRVV()); def constraints() = war_StorexALU()           }
  object D3C4 extends RVGenerator { val sets = Seq(isRVV()); def constraints() = war_SlowxFast()           }
  object D3C6 extends RVGenerator { val sets = Seq(isRVV()); def constraints() = war_GatherxALU()          }
  object D4C1 extends RVGenerator { val sets = Seq(isRVV()); def constraints() = waw_ALUxALU()             }
  object D4C2 extends RVGenerator { val sets = Seq(isRVV()); def constraints() = waw_SlowxLoad()           }
  object D4C4 extends RVGenerator { val sets = Seq(isRVV()); def constraints() = waw_SlowxFast()           }
  object D5C1 extends RVGenerator { val sets = Seq(isRVV()); def constraints() = implicitV0WAR_ALUxALU()   }

  val cells: Seq[(String, RVGenerator)] = Seq(
    "D1C1" -> D1C1, "D1C2" -> D1C2, "D1C3" -> D1C3, "D1C4" -> D1C4,
    "D1C5" -> D1C5, "D1C7" -> D1C7, "D2C1" -> D2C1, "D3C1" -> D3C1,
    "D3C2" -> D3C2, "D3C4" -> D3C4, "D3C6" -> D3C6, "D4C1" -> D4C1,
    "D4C2" -> D4C2, "D4C4" -> D4C4, "D5C1" -> D5C1
  )

  val cellBodies = cells.map { (name, gen) =>
    val asm = gen.toRecipeAsm().trim
    s"""test_$name:
       |    # --- $name ---
       |    $asm""".stripMargin
  }.mkString("\n\n")

  val content =
    s"""    .text
       |    .globl test
       |    .p2align 2
       |test:
       |    # Vector configuration: SEW=32, LMUL=1, vl=max
       |    li   t0, -1
       |    vsetvli t0, t0, e32, m1, ta, ma
       |    # Memory base for load/store cells
       |    la   t3, vbuf
       |
       |$cellBodies
       |
       |    ret
       |
       |    .section ".data","aw",@progbits
       |    .align 8
       |vbuf:
       |    .zero 256
       |""".stripMargin

  os.write.over(os.Path(outputPath, os.pwd), content, createFolders = true)
