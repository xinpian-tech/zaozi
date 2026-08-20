// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

/** The construct-generic certify/inject engine.
  *
  * It consumes any [[Invariant]] — regardless of which construct emitted it — and lowers it to a btor2 predicate over
  * that invariant's signals, then either certifies it (BMC-as-bad: the negation must be unreachable) or injects it as a
  * `constraint`. Signal names are resolved to btor2 nodes by a `nodeOf` map: the btor2 `state` symbol names for
  * circt-generated btor2, or a structural locator (e.g. `Harvester.locateBtor2`) for name-stripped third-party btor2.
  * This replaces per-invariant hand-coding with one shared path over the whole recognizer library.
  */
object InvariantEngine:

  /** Lower an invariant to a btor2 predicate over its signals' nodes. */
  def predicate(inv: Invariant, nodeOf: String => Long): Btor2Pred = inv match
    case Invariant.Range(sig, lo, hi)    =>
      val n = nodeOf(sig)
      Btor2Pred.And(Btor2Pred.uge(n, lo), Btor2Pred.ult(n, hi + 1)) // lo ≤ n ≤ hi
    case Invariant.MemberOf(sig, values) =>
      require(values.nonEmpty, s"MemberOf($sig, ∅) is unsatisfiable")
      val n = nodeOf(sig)
      values.toSeq.sorted.map(v => Btor2Pred.eq(n, v)).reduce(Btor2Pred.Or.apply)
    case Invariant.Implies(a, c)         =>
      Btor2Pred.Or(Btor2Pred.Not(predicate(a, nodeOf)), predicate(c, nodeOf))

  /** Certify an invariant BMC-as-bad: its negation must be unreachable within `kmax`. */
  def certify(design: Btor2Design, inv: Invariant, nodeOf: String => Long, kmax: Int): Btor2Result =
    Btor2.checkPred(design, Btor2Pred.Not(predicate(inv, nodeOf)), kmax)

  /** Inject invariants as btor2 `constraint`s holding over every reachable state. */
  def inject(design: Btor2Design, invariants: Seq[Invariant], nodeOf: String => Long): Btor2Design =
    invariants.foldLeft(design)((d, inv) => d.withConstraint(predicate(inv, nodeOf)))

  /** Resolve signal names via the btor2 `state <sort> <name>` symbol names — present in circt-generated btor2, absent
    * in Yosys-flattened third-party btor2 (there use a structural locator instead).
    */
  def byStateName(design: Btor2Design): String => Long =
    name => design.stateNamed(name).getOrElse(throw new IllegalArgumentException(s"no btor2 state named '$name'"))
