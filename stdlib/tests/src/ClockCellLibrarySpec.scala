// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.clock

import utest.*

object ClockCellLibrarySpec extends TestSuite:
  private def cell(json: String): ClockCellDeclaration = upickle.default.read[ClockCellDeclaration](json)

  private val inverter = cell("""{"name":"INV","inputs":["A"],"output":"Y","function":[{"Y":"!A"}]}""")
  private val positive = cell(
    """{"name":"ICG","sequential":{"type":"GatePositive","clock":"CK","enable":"E","test":"SE","output":"Q"}}"""
  )

  val tests = Tests:
    test("permuted pins bind by function"):
      val mux     = cell(
        """{"name":"MUX2","inputs":["S","I1","I0"],"output":"Y","function":[{"S":0,"Y":"I0"},{"S":1,"Y":"I1"}]}"""
      )
      val library = ClockCellLibrary.resolve(Seq(mux))
      assert(
        library.roles == Map(
          ClockCellKind.Mux -> ClockRole.Cell("MUX2", Map("a" -> "I0", "b" -> "I1", "select" -> "S", "outClock" -> "Y"))
        )
      )

    test("swapped mux data inputs do not bind in declared order"):
      val swapped = cell(
        """{"name":"MUXR","inputs":["I0","I1","S"],"output":"Y","function":[{"S":1,"Y":"I0"},{"S":0,"Y":"I1"}]}"""
      )
      assert(
        ClockCellLibrary.bind(swapped) ==
          Some(ClockCellKind.Mux -> Map("a" -> "I1", "b" -> "I0", "select" -> "S", "outClock" -> "Y"))
      )

    test("two cells for one role are rejected by name"):
      val error = intercept[IllegalArgumentException](
        ClockCellLibrary.resolve(Seq(inverter, inverter.copy(name = "INVX2")))
      )
      assert(error.getMessage.contains("INV and INVX2"))

    test("ties select the role function and drive constant pins"):
      val ao21    = cell(
        """{"name":"AO21","inputs":["A1","A2","B"],"output":"Y","tie":{"A2":1},"function":[""" +
          """{"A1":1,"A2":1,"Y":1},{"B":1,"Y":1},{"A1":0,"B":0,"Y":0},{"A2":0,"B":0,"Y":0}]}"""
      )
      val library = ClockCellLibrary.resolve(Seq(ao21))
      assert(library.roles.keySet == Set(ClockCellKind.Or))
      assert(ClockCellLibrary.bind(ao21.copy(tie = Map.empty)).isEmpty)

    test("one gate polarity and an inverter build the other polarity"):
      val library = ClockCellLibrary.resolve(Seq(positive, inverter))
      assert(library.roles(ClockCellKind.GateNegative) == ClockRole.Inverted(ClockCellKind.GatePositive))
      assert(!ClockCellLibrary.resolve(Seq(positive)).roles.contains(ClockCellKind.GateNegative))

    test("functions must define one output for every input"):
      def rejected(json: String, message: String): Unit =
        val error = intercept[IllegalArgumentException](cell(json))
        assert(error.getMessage.contains(message))
      rejected("""{"name":"X","inputs":["A"],"output":"Y","function":[{"A":0,"Y":1}]}""", "undefined for A=1")
      rejected("""{"name":"X","inputs":["A"],"output":"Y","function":[{"Y":"A"},{"A":1,"Y":0}]}""", "disagree")
      rejected("""{"name":"X","inputs":["A","B"],"output":"Y","function":[{"Y":"A"}]}""", "B changes no output")
      rejected("""{"name":"X","inputs":["A"],"output":"Y","tie":{"Y":1},"function":[{"Y":"A"}]}""", "ties its output")
      rejected("""{"name":"X","inputs":["A"],"output":"Y","tie":{"A":2},"function":[{"Y":"A"}]}""", "not 0 or 1")
      rejected("""{"name":"X","inputs":["A"],"output":"Y","function":[{"C":1,"Y":"A"}]}""", "unknown pin C")
      rejected("""{"name":"X","inputs":["A"],"output":"Y","function":[{"Y":"B"}]}""", "pin Y cannot be")
      rejected("""{"name":"ZaoziInv","inputs":["A"],"output":"Y","function":[{"Y":"!A"}]}""", "reserved")
