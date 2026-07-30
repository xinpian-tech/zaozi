// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.tpe.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

/** Pin `port` to `kind` for every cycle in `cycles`. */
private def pinKind(
  cycles: Range,
  port:   String,
  kind:   TxnKind
)(
  using Arena,
  Context,
  Block,
  TxnRecipe
): Unit =
  summon[TxnRecipe].addKindConstraint { r =>
    cycles.foreach(c => smtAssert(r.kind(c, port) === kind.id.S))
  }

/** Require an enqueue on `port` in every cycle of `cycles`. */
def mustEnqueue(
  cycles: Range,
  port:   String
)(
  using Arena,
  Context,
  Block,
  TxnRecipe
): Unit = pinKind(cycles, port, TxnKind.Enqueue)

/** Require a dequeue on `port` in every cycle of `cycles`. */
def mustDequeue(
  cycles: Range,
  port:   String
)(
  using Arena,
  Context,
  Block,
  TxnRecipe
): Unit = pinKind(cycles, port, TxnKind.Dequeue)

/** Require `port` to be idle in every cycle of `cycles`. */
def mustIdle(
  cycles: Range,
  port:   String
)(
  using Arena,
  Context,
  Block,
  TxnRecipe
): Unit = pinKind(cycles, port, TxnKind.Idle)

/** Constrain `port`'s payload to the inclusive range `[lo, hi]`. */
def payloadIn(
  cycles: Range,
  port:   String,
  lo:     BigInt,
  hi:     BigInt
)(
  using Arena,
  Context,
  Block,
  TxnRecipe
): Unit =
  require(lo.isValidInt && hi.isValidInt, s"payloadIn bounds must fit in an Int, got [$lo, $hi]")
  summon[TxnRecipe].addPayloadConstraint { r =>
    cycles.foreach { c =>
      val v = r.payload(c, port)
      smtAssert(v >= lo.toInt.S & v <= hi.toInt.S)
    }
  }

/** Require every payload on `port` across `cycles` to differ. */
def distinctPayloads(
  cycles: Range,
  port:   String
)(
  using Arena,
  Context,
  Block,
  TxnRecipe
): Unit =
  summon[TxnRecipe].addPayloadConstraint { r =>
    smtAssert(smtDistinct(cycles.map(c => r.payload(c, port)).toSeq*))
  }

/** Require at least `n` cycles of the sequence to carry `kind` on `port`.
  *
  * Encoded without an integer sum: the sequence is split into `n` contiguous buckets and each bucket is required to
  * contain at least one `kind` slot. That is slightly stronger than "n anywhere" — it also spreads them out — but it
  * stays inside QF_LIA and solves fast, and spread-out stimulus is what a unit test usually wants anyway.
  */
def atLeast(
  n:    Int,
  kind: TxnKind,
  port: String
)(
  using Arena,
  Context,
  Block,
  TxnRecipe
): Unit =
  val recipe = summon[TxnRecipe]
  require(n >= 1 && n <= recipe.cycles, s"atLeast($n) must be within 1..${recipe.cycles}")
  recipe.addKindConstraint { r =>
    val bucketSize = r.cycles / n
    (0 until n).foreach { b =>
      val from   = b * bucketSize
      val to     = if b == n - 1 then r.cycles else (b + 1) * bucketSize
      val anyHit = (from until to).map(c => r.kind(c, port) === kind.id.S)
      smtAssert(smtOr(anyHit.toSeq*))
    }
  }

/** Require a run of `length` consecutive `kind` transactions on `port`. */
def backToBack(
  port:   String,
  kind:   TxnKind,
  length: Int
)(
  using Arena,
  Context,
  Block,
  TxnRecipe
): Unit =
  val recipe = summon[TxnRecipe]
  require(length >= 1 && length <= recipe.cycles, s"backToBack($length) must be within 1..${recipe.cycles}")
  recipe.addKindConstraint { r =>
    val windows = (0 to r.cycles - length).map { start =>
      smtAnd((start until start + length).map(c => r.kind(c, port) === kind.id.S).toSeq*)
    }
    smtAssert(smtOr(windows.toSeq*))
  }
