// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

import me.jiuyang.rvprobe.frontend.PortDir

/** What the testbench does with one port in one cycle.
  *
  * The ids are the values the SMT solver picks in stage 1, so they must be stable — they cross the solver boundary as
  * plain integers.
  */
enum TxnKind:
  case Idle, Enqueue, Dequeue

  def id: Int = ordinal

object TxnKind:
  def fromId(id: Int): TxnKind = TxnKind.values(id)

  given upickle.default.ReadWriter[TxnKind] =
    upickle.default.readwriter[Int].bimap[TxnKind](_.id, TxnKind.fromId)

/** One transaction port of a DUT, as the framework sees it.
  *
  * `dir` is from the testbench's point of view: [[PortDir.Drive]] means the testbench supplies `valid`/`bits` and
  * observes `ready`; [[PortDir.Monitor]] means the testbench supplies `ready` and observes `valid`/`bits`.
  */
final case class PortSpec(
  name:         String,
  dir:          PortDir,
  payloadWidth: Int)

object PortSpec:
  given upickle.default.ReadWriter[PortDir]  =
    upickle.default.readwriter[Int].bimap[PortDir](_.ordinal, PortDir.values(_))
  given upickle.default.ReadWriter[PortSpec] = upickle.default.macroRW

/** The DUT's transaction surface plus the internal status signals that coverpoints may reference. */
final case class DutInterface(
  dutName: String,
  ports:   Seq[PortSpec],
  status:  Seq[String])

object DutInterface:
  import PortSpec.given
  given upickle.default.ReadWriter[DutInterface] = upickle.default.macroRW

/** One solved transaction: at `cycle`, drive `port` with `kind` and, for an enqueue, the payload `payload`. Dequeues
  * and idles carry payload 0.
  */
final case class SolvedTxn(
  cycle:   Int,
  port:    String,
  kind:    TxnKind,
  payload: BigInt)

object SolvedTxn:
  import TxnKind.given
  given upickle.default.ReadWriter[SolvedTxn] = upickle.default.macroRW

/** A fully solved stimulus sequence for one DUT. */
final case class SolvedStimulus(
  dut:    String,
  cycles: Int,
  txns: Seq[SolvedTxn]):

  /** The transaction scheduled for `port` at `cycle`, if any. */
  def at(cycle: Int, port: String): Option[SolvedTxn] =
    txns.find(t => t.cycle == cycle && t.port == port)

object SolvedStimulus:
  import SolvedTxn.given
  given upickle.default.ReadWriter[SolvedStimulus] = upickle.default.macroRW

/** `@generator` synthesizes a mainargs CLI for every generator, which needs a [[mainargs.TokensReader]] for each field
  * of the parameter. A solved stimulus is passed as JSON on that command line. Declared at package level so it is in
  * scope wherever a generator carrying a [[SolvedStimulus]] is defined.
  */
given mainargs.TokensReader.Simple[SolvedStimulus] with
  def shortName:               String                         = "stimulus"
  def read(strs: Seq[String]): Either[String, SolvedStimulus] =
    Right(upickle.default.read[SolvedStimulus](strs.head))
