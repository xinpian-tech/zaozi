// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.frontend

/** Direction of a Decoupled transaction port, from the testbench's view. */
enum PortDir:
  /** DUT-side `Flipped(Decoupled)` — the testbench drives it (e.g. Queue.enq). */
  case Drive

  /** DUT-side `Aligned(Decoupled)` — the testbench consumes it (e.g. Queue.deq). */
  case Monitor

/** A Decoupled transaction port lifted from a Zaozi module's typed IO. */
final case class DecoupledPort(name: String, dir: PortDir, bitsWidth: Int)

/** A white-box internal status signal (e.g. a Queue's empty/full/almostFull), exposed as a first-class constraint
  * dimension.
  */
final case class StatusSignal(name: String, category: String)

/** A structured description of a Zaozi DUT's transaction interface.
  *
  * Today this is supplied directly (or hand-derived from a module's `QueueIO` style typed IO). Auto-deriving it from
  * the Chisel/Zaozi Object Model is idea#9 L1; because Zaozi lowers to the same CIRCT IR as the constraints, the ports
  * and status signals are first-class there.
  */
final case class TransactionInterface(
  dutName: String,
  ports:   Seq[DecoupledPort],
  status:  Seq[StatusSignal])

/** One transaction in a solved sequence. */
enum Transaction:
  case Enqueue(port: String, value: BigInt)
  case Dequeue(port: String)

/** The Zaozi leg's solved artifact: the frontend-agnostic [[SolvedSequence]] plus the concrete transaction list the
  * ChiselSim backend renders.
  */
final case class ZaoziArtifact(
  sequence:     SolvedSequence,
  transactions: Seq[Transaction])
    extends SolvedArtifact

/** The Zaozi leg of the [[DutFrontend]] contract — a transaction-level frontend for Decoupled Zaozi modules (Queue,
  * FIFO, arbiters, …). It is the second leg, and it proves the contract generalizes beyond RISC-V:
  *
  *   - `alphabet` — transaction kinds derived from the Decoupled ports (Drive ⇒ enqueue, Monitor ⇒ dequeue), not an
  *     instruction set.
  *   - `whitebox` — the module's internal status signals (empty/full/…), the Decoupled-world analogue of RISC-V µarch
  *     predicates.
  *   - `backend` — a ChiselSim-style poke/peek/step driver, not GAS assembly.
  *
  * SCOPE (honest): `solve()` currently emits a straightforward deterministic smoke sequence (drive every Drive port
  * once, drain every Monitor port once). The SMT-backed *sequence-level* solving of transactions under Decoupled
  * handshake timing — the "adjacency" semantics of valid/ready, backpressure, and coverRAW/WAR/WAW lifted from
  * registers to addresses — is idea#9's L2 research core. When built, it replaces the body of `solve()` and reuses this
  * leg's alphabet, whitebox, and backend unchanged.
  */
final class ZaoziFrontend(iface: TransactionInterface) extends DutFrontend:
  type Artifact = ZaoziArtifact

  def name: String = iface.dutName

  lazy val alphabet: StimulusAlphabet = new StimulusAlphabet:
    lazy val kinds: Seq[StimulusKind] =
      iface.ports.zipWithIndex.map { case (p, idx) =>
        new StimulusKind:
          def id:       Int    = idx
          def mnemonic: String = p.dir match
            case PortDir.Drive   => s"enqueue.${p.name}"
            case PortDir.Monitor => s"dequeue.${p.name}"
      }

  override lazy val whitebox: Seq[WhiteboxPredicate] =
    iface.status.map { s =>
      new WhiteboxPredicate:
        def signal:   String = s.name
        def category: String = s.category
    }

  /** PLACEHOLDER sequence (see class scope note): drive each Drive port once with a small distinct value, then drain
    * each Monitor port once. The SolvedSequence records the transaction selection/fields so downstream tooling sees the
    * same shape it will once the SMT solver is wired.
    */
  def solve(): ZaoziArtifact =
    val drives   = iface.ports.filter(_.dir == PortDir.Drive)
    val monitors = iface.ports.filter(_.dir == PortDir.Monitor)
    val txns: Seq[Transaction] =
      drives.zipWithIndex.map { case (p, i) => Transaction.Enqueue(p.name, BigInt(i + 1)) } ++
        monitors.map(p => Transaction.Dequeue(p.name))
    val selections = txns.zipWithIndex.map { case (_, i) => i -> i }.toMap
    val fields = txns.zipWithIndex.collect { case (Transaction.Enqueue(p, v), i) => s"${p}_bits_$i" -> v }.toMap
    ZaoziArtifact(SolvedSequence(selections, fields), txns)

  def backend: StimulusBackend[ZaoziArtifact] = new StimulusBackend[ZaoziArtifact]:
    def kind: String = "chiselsim"

    /** Render the transaction list as a ChiselSim-style driver body. */
    def render(solved: ZaoziArtifact): String =
      val header = s"// ChiselSim driver for ${iface.dutName} (${solved.transactions.size} transactions)"
      val body   = solved.transactions.map {
        case Transaction.Enqueue(port, value) =>
          s"""|  dut.$port.bits.poke($value)
              |  dut.$port.valid.poke(true)
              |  while (!dut.$port.ready.peek()) { dut.clock.step() }
              |  dut.clock.step()
              |  dut.$port.valid.poke(false)""".stripMargin
        case Transaction.Dequeue(port)        =>
          s"""|  dut.$port.ready.poke(true)
              |  while (!dut.$port.valid.peek()) { dut.clock.step() }
              |  val _ = dut.$port.bits.peek()
              |  dut.clock.step()
              |  dut.$port.ready.poke(false)""".stripMargin
      }.mkString("\n")
      s"$header\n$body"
