// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.btb

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.Register.*
import me.jiuyang.rvprobe.constraints.{*, given}
import me.jiuyang.rvprobe.cases.HTIFLib.*
import me.jiuyang.rvprobe.cases.privilege.PrivilegeProbeLib.*
import me.jiuyang.rvprobe.cases.privilege.{CSR, Cause}
import me.jiuyang.rvprobe.cases.btb.BTBProbeLib.*

// BTB alias A: jal trains BTB, then lui at the fall-through PC should NOT be predicted as a branch.
// Reproduces the BOOM v3 bug where BTB mis-predicts lui after jal, corrupting mepc.
// Pattern: jal → target → mret back → lui at jal+4 → should execute normally.
@main def BTBJalLuiAlias(outputPath: String): Unit =
  object BTBJalLuiAlias extends RVGenerator:
    val sets          = btbSets()
    def constraints() =
      textStartWithTrap()
      pmpOpenAll()
      mapGigapageIdentity(0xcfL)
      enableSv39()
      switchToSMode("s_code")

      label("s_code")
      // Train BTB: jal to a known target, then return via jalr
      jal(x1, "btb_target_a")
      // Fall-through after return: lui must NOT be mis-predicted as a branch
      lui(x11, 0x42)
      // If we reach here, lui executed correctly (no BTB mis-prediction)
      j("exit")

      label("btb_target_a")
      jalr(x0, x1, 0)

      finish()
      pageTableData()
  BTBJalLuiAlias.emit(outputPath)

// BTB alias B: jal trains BTB, then addi at fall-through should not be redirected.
// Tests that non-branch arithmetic instructions after jal are not corrupted by BTB aliasing.
@main def BTBJalAddiAlias(outputPath: String): Unit =
  object BTBJalAddiAlias extends RVGenerator:
    val sets          = btbSets()
    def constraints() =
      textStartWithTrap()
      pmpOpenAll()
      mapGigapageIdentity(0xcfL)
      enableSv39()
      switchToSMode("s_code")

      label("s_code")
      addi(x10, x0, 100)
      jal(x1, "btb_target_b")
      // Fall-through: addi must execute with correct value, not be redirected by BTB
      addi(x10, x10, 1)
      j("exit")

      label("btb_target_b")
      jalr(x0, x1, 0)

      finish()
      pageTableData()
  BTBJalAddiAlias.emit(outputPath)

// BTB alias C: repeated jal/ret cycle to heavily train BTB, then execute non-branch at the same PC.
// This maximizes BTB training pressure to expose aliasing bugs in the BTB indexing.
@main def BTBRepeatedTraining(outputPath: String): Unit =
  object BTBRepeatedTraining extends RVGenerator:
    val sets          = btbSets()
    def constraints() =
      textStartWithTrap()
      pmpOpenAll()
      mapGigapageIdentity(0xcfL)
      enableSv39()
      switchToSMode("s_code")

      label("s_code")
      // Train BTB by calling the same target multiple times
      addi(x20, x0, 4) // loop counter
      label("train_loop")
      jal(x1, "train_target")
      addi(x20, x20, -1)
      bnez(x20, "train_loop")

      // After training, execute non-branch instructions at the fall-through PCs
      lui(x10, 0x12345)
      addi(x10, x10, 0x678)
      j("exit")

      label("train_target")
      jalr(x0, x1, 0)

      finish()
      pageTableData()
  BTBRepeatedTraining.emit(outputPath)
