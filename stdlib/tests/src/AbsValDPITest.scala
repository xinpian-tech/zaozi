// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib

import me.jiuyang.utlib.*

import utest.*

/** The DPI contract as a dependent type on AbsVal's `(IO, Probe)`: drive/probe ports resolve
  * by name and are checked at compile time against the DUT's own interface types.
  */
object AbsValDPITest extends TestSuite:
  private val outputRoot = os.Path(sys.props("zaozi.utlib.outDir"), os.pwd)
  private val generator  =
    UTGenerator(AbsVal, AbsValParameter(8), cycles = 3, outputDirectory = outputRoot / "AbsValDPI")

  val tests: Tests = Tests:
    test("typed drive and probe ports resolve against the DUT interfaces"):
      val contract = generator.dpi
      assert(contract.spec.dut == "AbsVal_width8")

      // `A` is the DUT's input — a Drive port, checked against AbsValIO at compile time.
      assert(contract.drive.A.role == DPIRole.Drive)
      assert(contract.drive.A.width == 8)

      // `absval`/`a` are probe points, checked against AbsValProbe at compile time.
      assert(contract.probe.absval.role == DPIRole.Probe)
      assert(contract.probe.a.width == 8)
      // A field the DUT does not have — `generator.typedDpi.drive.NotAPort` — does not
      // compile; the macro rejects it. Verified out-of-band, not asserted here.
