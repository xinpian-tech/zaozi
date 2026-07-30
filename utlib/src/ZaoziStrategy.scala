// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

import me.jiuyang.rvprobe.frontend.{PortDir, Transaction, TransactionInterface}

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

/** Bridges `rvprobe`'s [[TransactionInterface]] to this module's SMT solver, so the Zaozi leg of the `DutFrontend`
  * contract is backed by real constraint solving rather than its built-in smoke sequence.
  *
  * The dependency only runs this way: `utlib` depends on `rvprobe`, so `rvprobe` cannot call the solver directly. It
  * takes a strategy function instead, and this is the strategy that supplies one.
  */
object ZaoziStrategy:

  /** A strategy that solves `sequenceLength` cycles under `txnConstraints`.
    *
    * The parameter names differ from the members they feed (`sequenceLength` → `cycles`, `txnConstraints` →
    * `constraints`) because inside the anonymous `TxnSolver` a member named `cycles` defined as `= cycles` would refer
    * to itself, not to the enclosing parameter.
    */
  def solving(
    sequenceLength: Int
  )(txnConstraints: (Arena, Context, Block, TxnRecipe) ?=> Unit
  ): TransactionInterface => Seq[Transaction] = txnIface =>
    val dut    = DutInterface(
      dutName = txnIface.dutName,
      ports = txnIface.ports.map(p => PortSpec(p.name, p.dir, p.bitsWidth)),
      status = txnIface.status.map(_.name)
    )
    val solver = new TxnSolver:
      def iface:         DutInterface                                = dut
      def cycles:        Int                                         = sequenceLength
      def constraints(): (Arena, Context, Block, TxnRecipe) ?=> Unit = txnConstraints
    solver
      .solve()
      .txns
      .collect {
        case t if t.kind == TxnKind.Enqueue => Transaction.Enqueue(t.port, t.payload)
        case t if t.kind == TxnKind.Dequeue => Transaction.Dequeue(t.port)
      }
