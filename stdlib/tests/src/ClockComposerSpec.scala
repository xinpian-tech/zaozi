// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.clock

import utest.*

object ClockComposerSpec extends TestSuite:
  private def cell(json: String): ClockCellDeclaration = upickle.default.read[ClockCellDeclaration](json)

  private val nand = cell(
    """{"name":"NAND2","inputs":["A","B"],"output":"Y","function":[{"A":0,"Y":1},{"B":0,"Y":1},{"A":1,"B":1,"Y":0}]}"""
  )
  private val nor  = cell(
    """{"name":"NOR2","inputs":["A","B"],"output":"Y","function":[{"A":1,"Y":0},{"B":1,"Y":0},{"A":0,"B":0,"Y":1}]}"""
  )
  private val inv  = cell("""{"name":"INV","inputs":["A"],"output":"Y","function":[{"Y":"!A"}]}""")

  private def gate(pins: (String, String)*): ClockComposedGate = ClockComposedGate("NAND2", Map(pins*))

  private def found(role: ClockCellKind, cells: Seq[ClockCellDeclaration]): ClockComposition =
    ClockComposer.compose(role, cells) match
      case ClockComposer.Result.Found(composition) => composition
      case other                                   => throw new IllegalStateException(other.toString)

  val tests = Tests:
    test("one gate polarity and composed inverters build the other polarity"):
      val negative =
        cell("""{"name":"ICGN","sequential":{"type":"GateNegative","clock":"CPN","enable":"E","output":"Q"}}""")
      val composed = ClockCellLibrary.compose(Seq(nand, negative), Set(ClockCellKind.GatePositive))
      assert(composed.map(_.role) == Seq(ClockCellKind.Inverter))
      assert(
        ClockCellLibrary.resolve(Seq(nand, negative), composed).roles(ClockCellKind.GatePositive) ==
          ClockRole.Inverted(ClockCellKind.GateNegative)
      )

    test("xor from nand composes at depth three with five cells"):
      val xor = found(ClockCellKind.Xor, Seq(nand))
      assert(xor.depth == 3 && xor.gates.size == 5)
      assert(ClockComposer.verify(xor, Seq(nand)) == Right(()))

    test("the four-nand xor can glitch"):
      val textbook = ClockComposition(
        ClockCellKind.Xor,
        3,
        Seq(
          gate("A" -> "a", "B"  -> "b"),
          gate("A" -> "a", "B"  -> "g0"),
          gate("A" -> "b", "B"  -> "g0"),
          gate("A" -> "g1", "B" -> "g2")
        )
      )
      assert(ClockComposer.verify(textbook, Seq(nand)) == Left("it can glitch when a role input changes"))

    test("mux composes from nand alone or from nor and an inverter"):
      Seq(Seq(nand), Seq(nor, inv)).foreach: cells =>
        val mux = found(ClockCellKind.Mux, cells)
        assert(mux.depth == 3 && mux.gates.size == 4)
        assert(ClockComposer.verify(mux, cells) == Right(()))

    test("declaration order does not change the network"):
      assert(found(ClockCellKind.Mux, Seq(nor, inv, nand)) == found(ClockCellKind.Mux, Seq(nand, inv, nor)))

    test("a changed pin fails re-verification"):
      val xor     = found(ClockCellKind.Xor, Seq(nand))
      val swapped = xor.copy(gates =
        xor.gates.updated(
          0,
          xor.gates.head.copy(pins = xor.gates.head.pins.map:
            case (pin, "a") => pin -> "b"
            case (pin, "b") => pin -> "a"
            case other      => other)
        )
      )
      val error   = intercept[IllegalArgumentException](ClockCellLibrary.resolve(Seq(nand), Seq(swapped)))
      assert(error.getMessage.contains("rerun config"))
      assert(ClockCellLibrary.resolve(Seq(nand), Seq(xor)).roles(ClockCellKind.Xor) == ClockRole.Composed(xor))

    test("roles without a network name the reason"):
      val error = intercept[IllegalArgumentException](ClockCellLibrary.compose(Seq(inv), Set(ClockCellKind.Xor)))
      assert(
        error.getMessage == "clock role Xor cannot be built from the declared cells: the declared cells cannot express its function"
      )
