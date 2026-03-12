// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.constraints.*

import utest.*

// Validates that SpecFor[T] givens inject the correct RISC-V spec constraints
// into the SMT problem.  The strategy is:
//
//   - "reserved" tests: user constraint forces a value the spec forbids
//     → solver must return "unsat"
//   - "valid" tests: user constraint picks a value the spec permits
//     → solver must return "sat"
//
// If a spec constraint is missing or wrong, the "unsat" tests become "sat"
// (or vice-versa), immediately catching the regression.

object SpecConstraintsTest extends TestSuite:
  val tests = Tests:

    // -------------------------------------------------------------------------
    // IsFence — fm field
    //   Spec §3.3: only fm=0 (FENCE) and fm=8 (FENCE.TSO) are defined;
    //              all other fm values are reserved.
    // -------------------------------------------------------------------------

    test("IsFence/fm=0 is valid (sat)"):
      object FenceFm0 extends RVGenerator with HasRVProbeTest:
        val sets          = isRV64GC()
        def constraints() =
          instruction(0, isFence()) { fmEqual(0) }
      FenceFm0.rvprobeTestArgZ3Output("sat")

    test("IsFence/fm=8 (TSO) is valid (sat)"):
      object FenceFm8 extends RVGenerator with HasRVProbeTest:
        val sets          = isRV64GC()
        def constraints() =
          instruction(0, isFence()) { fmEqual(8) }
      FenceFm8.rvprobeTestArgZ3Output("sat")

    test("IsFence/reserved fm=2 is rejected (unsat)"):
      object FenceFm2 extends RVGenerator with HasRVProbeTest:
        val sets          = isRV64GC()
        def constraints() =
          instruction(0, isFence()) { fmEqual(2) }
      FenceFm2.rvprobeTestArgZ3Output("unsat")

    test("IsFence/reserved fm=15 is rejected (unsat)"):
      object FenceFm15 extends RVGenerator with HasRVProbeTest:
        val sets          = isRV64GC()
        def constraints() =
          instruction(0, isFence()) { fmEqual(15) }
      FenceFm15.rvprobeTestArgZ3Output("unsat")

    // -------------------------------------------------------------------------
    // IsCAddi4spn — nzuimm field
    //   Spec §16.3: encoding with nzuimm=0 is reserved.
    // -------------------------------------------------------------------------

    test("IsCAddi4spn/nzuimm=0 is reserved (unsat)"):
      object CAddi4spnZero extends RVGenerator with HasRVProbeTest:
        val sets          = isRV64GC()
        def constraints() =
          instruction(0, isCAddi4spn()) { cNzuimm10Equal(0) }
      CAddi4spnZero.rvprobeTestArgZ3Output("unsat")

    test("IsCAddi4spn/nzuimm=4 is valid (sat)"):
      object CAddi4spnFour extends RVGenerator with HasRVProbeTest:
        val sets          = isRV64GC()
        def constraints() =
          instruction(0, isCAddi4spn()) { cNzuimm10Equal(4) }
      CAddi4spnFour.rvprobeTestArgZ3Output("sat")

    // -------------------------------------------------------------------------
    // IsCLui — nzimm field
    //   Spec §16.5: encoding with nzimm=0 (both hi and lo zero) is reserved.
    // -------------------------------------------------------------------------

    test("IsCLui/nzimm=0 (hi=0 lo=0) is reserved (unsat)"):
      object CLuiZero extends RVGenerator with HasRVProbeTest:
        val sets          = isRV64GC()
        def constraints() =
          instruction(0, isCLui()) {
            ArgConstraint(smtAnd(cNzimm18hiEqual(0).toRef, cNzimm18loEqual(0).toRef))
          }
      CLuiZero.rvprobeTestArgZ3Output("unsat")

    test("IsCLui/nzimm nonzero (hi=1) is valid (sat)"):
      object CLuiHiOne extends RVGenerator with HasRVProbeTest:
        val sets          = isRV64GC()
        def constraints() =
          instruction(0, isCLui()) { cNzimm18hiEqual(1) }
      CLuiHiOne.rvprobeTestArgZ3Output("sat")

    test("IsCLui/nzimm nonzero (lo=1) is valid (sat)"):
      object CLuiLoOne extends RVGenerator with HasRVProbeTest:
        val sets          = isRV64GC()
        def constraints() =
          instruction(0, isCLui()) { cNzimm18loEqual(1) }
      CLuiLoOne.rvprobeTestArgZ3Output("sat")

    // -------------------------------------------------------------------------
    // IsCLwsp — rd field
    //   Spec §16.5: encoding with rd=x0 is reserved.
    // -------------------------------------------------------------------------

    test("IsCLwsp/rd=x0 is reserved (unsat)"):
      object CLwspRd0 extends RVGenerator with HasRVProbeTest:
        val sets = isRV64GC()
        def constraints() =
          // rdN0Range(0, 1) = rdN0 >= 0 AND rdN0 < 1, i.e. only x0
          instruction(0, isCLwsp()) { rdN0Range(0, 1) }
      CLwspRd0.rvprobeTestArgZ3Output("unsat")

    test("IsCLwsp/rd=x1 is valid (sat)"):
      object CLwspRd1 extends RVGenerator with HasRVProbeTest:
        val sets          = isRV64GC()
        def constraints() =
          instruction(0, isCLwsp()) { rdN0Range(1, 2) }
      CLwspRd1.rvprobeTestArgZ3Output("sat")

    test("IsCLwsp/rd=x31 is valid (sat)"):
      object CLwspRd31 extends RVGenerator with HasRVProbeTest:
        val sets          = isRV64GC()
        def constraints() =
          instruction(0, isCLwsp()) { rdN0Range(31, 32) }
      CLwspRd31.rvprobeTestArgZ3Output("sat")
