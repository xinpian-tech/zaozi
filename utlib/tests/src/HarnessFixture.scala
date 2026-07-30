// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib.tests

import me.jiuyang.utlib.*

/** One shared harness parameter, used by every test that needs an elaborated harness, so the stimulus literal is
  * written once.
  *
  * Two enqueues back to back (which fills the depth-2 FIFO, so `full` is reachable), then two dequeues (which drains
  * it, so `empty` is reachable again).
  */
object HarnessFixture:
  val stimulus: SolvedStimulus = SolvedStimulus(
    dut = "Fifo",
    cycles = 5,
    txns = Seq(
      SolvedTxn(0, "enq", TxnKind.Enqueue, BigInt(11)),
      SolvedTxn(1, "enq", TxnKind.Enqueue, BigInt(22)),
      SolvedTxn(2, "enq", TxnKind.Idle, BigInt(0)),
      SolvedTxn(3, "enq", TxnKind.Idle, BigInt(0)),
      SolvedTxn(4, "enq", TxnKind.Idle, BigInt(0)),
      SolvedTxn(0, "deq", TxnKind.Idle, BigInt(0)),
      SolvedTxn(1, "deq", TxnKind.Idle, BigInt(0)),
      SolvedTxn(2, "deq", TxnKind.Dequeue, BigInt(0)),
      SolvedTxn(3, "deq", TxnKind.Dequeue, BigInt(0)),
      SolvedTxn(4, "deq", TxnKind.Idle, BigInt(0))
    )
  )

  val coverpoints: Seq[Coverpoint] = Seq(
    Coverpoint("cover_enq_fire", "an enqueue handshake completed"),
    Coverpoint("cover_deq_fire", "a dequeue handshake completed"),
    Coverpoint("cover_full", "the FIFO reached full"),
    Coverpoint("cover_empty", "the FIFO reached empty")
  )

  val parameter: HarnessParameter = HarnessParameter(
    width = 8,
    stimulus = stimulus,
    coverpoints = coverpoints
  )
