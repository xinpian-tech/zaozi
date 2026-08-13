// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

/** Structural facts harvested from a design's HW-dialect MLIR, to be transported to a formal
  * engine as strengthening invariants.
  *
  * The recognizers work at the `hw`/`comb`/`seq` level firtool and `circt-verilog` lower to,
  * where a Verilog `always`/`case` has become a tree of `comb.mux`es over `seq.firreg`s. That
  * loses the source `case` structure but keeps exactly what a counter-bound invariant needs:
  * which registers are FSMs, which are bounded counters, and how each counter updates.
  */

/** A register whose next value is its own value, its value plus a constant, or a constant —
  * the increment/clear/hold shape of a bounded counter.
  */
final case class CounterFact(
  name:       String,
  width:      Int,
  increments: Boolean,
  clears:     Boolean)

/** A register selected against its own value by equality tests and reset to a constant state
  * — a finite state machine.
  */
final case class FsmFact(
  name:       String,
  width:      Int,
  resetState: Option[Int])

final case class Harvest(
  counters: Seq[CounterFact],
  fsms:     Seq[FsmFact])

object Harvester:

  def harvest(mlir: os.Path, module: String): Harvest =
    val design = MlirText.parse(os.read(mlir), module)
    Harvest(
      counters = design.registers.flatMap(counterFact(design, _)),
      fsms = design.registers.flatMap(fsmFact(design, _))
    )

  /** A counter's driver cone contains only the register itself, constants, `comb.add` /
    * `comb.sub` of the register with a constant, and the `comb.mux`es that select among them.
    */
  private def counterFact(design: MlirDesign, reg: MlirRegister): Option[CounterFact] =
    val self = reg.name
    var increments = false
    var clears     = false
    var ok         = true

    def walk(value: String, seen: Set[String]): Unit =
      if value == self || seen(value) then ()
      else
        design.node(value) match
          case Some(MlirNode("hw.constant", _, _)) =>
            if design.constantValue(value).contains(BigInt(0)) then clears = true
          case Some(MlirNode("comb.mux", operands, _))                                =>
            operands.tail.foreach(walk(_, seen + value))
          case Some(MlirNode(op @ ("comb.add" | "comb.sub"), operands, _))            =>
            // A step by a constant on the register itself.
            val touchesSelf = operands.contains(self)
            val byConstant  = operands.exists(design.node(_).exists(_.op == "hw.constant"))
            if touchesSelf && byConstant then increments = true else ok = false
          case _                                                                      =>
            ok = false

    walk(reg.driver, Set.empty)
    Option.when(ok && (increments || clears))(CounterFact(self.stripPrefix("%"), reg.width, increments, clears))

  /** An FSM holds its own value unless a guard fires, and its own value is compared for
    * equality somewhere in the design (the `case` dispatch, now `comb.icmp eq`).
    */
  private def fsmFact(design: MlirDesign, reg: MlirRegister): Option[FsmFact] =
    val self = reg.name
    val holds = design.node(reg.driver) match
      case Some(MlirNode("comb.mux", operands, _)) => operands.lastOption.contains(self)
      case _                                       => false
    val dispatched = design.nodes.exists { node =>
      node.op == "comb.icmp" && node.operands.contains(self) &&
        design.icmpPredicate(node.result).exists(pred => pred == "eq" || pred == "ceq")
    }
    Option.when(holds && dispatched)(FsmFact(self.stripPrefix("%"), reg.width, reg.resetValue))
