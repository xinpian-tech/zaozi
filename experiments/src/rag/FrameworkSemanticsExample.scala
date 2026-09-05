// SPDX-License-Identifier: Apache-2.0

// Task: combine value, relation, state and temporal predicates into one typed intent.
// Given: symbolic predicates, a declared history window and a clocked sequence.
// Example solution: predicates come from the caller; no design condition is supplied.
import me.jiuyang.utlib.{Sem, Txn}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.{ClockEvent, Sequence}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

object FrameworkSemanticsExample:
  def compose[D <: Int](
    drivenPredicate: Referable[Bool],
    window: Txn.TxnWindow[D],
    relationPredicate: Txn.TxnWindow[D] => Referable[Bool],
    observedPredicate: Referable[Bool],
    scenario: Sequence
  )(using ClockEvent): Sem.Intent[
    Sem.Kinds.Value | Sem.Kinds.Relation | Sem.Kinds.State | Sem.Kinds.Temporal
  ] =
    Sem.value(drivenPredicate) &&
      Sem.relation(window)(relationPredicate) &&
      Sem.state(observedPredicate) &&
      Sem.temporal(scenario)

  // A UT author can pass the returned intent to Generate(intent, label) in the
  // module body, using that body's elaboration context. A fixed-runner fragment
  // must not add this helper or change the runner's existing UT.
