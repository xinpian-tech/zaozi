// SPDX-License-Identifier: Apache-2.0
package me.jiuyang.tblib

import java.lang.foreign.Arena
import me.jiuyang.zaozi.HWInterface
import me.jiuyang.tblib.default.{FirrtlTestbench, given}
import org.llvm.mlir.scalalib.capi.ir.{Context, Module}

/** Builds simulation IR from complete DUT and clock circuits in the supplied context. Inputs are borrowed and remain
  * unchanged. The caller owns the returned module.
  */
object Testbench:
  def apply(
    dut:   Module,
    clock: Module
  )(
    using Arena,
    Context
  ): Module =
    val wrapper = FirrtlTestbench.build(dut, clock)
    wrapper.buildTestbench[HWInterface[?]]:
      summon[TestbenchContext[HWInterface[?]]].step()
