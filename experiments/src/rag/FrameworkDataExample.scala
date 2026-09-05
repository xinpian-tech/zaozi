// SPDX-License-Identifier: Apache-2.0

// Task: represent a caller-supplied candidate and a pending proof using typed tuples.
// Given: the caller has derived all values and the pending reason from its own task.
// Example solution: a reusable helper, not a ready-made response to a design task.
// A fragment-only response copies the two declaration shapes, not this object/helper.
object FrameworkDataExample:
  def declarations(
    label: String,
    command: Int,
    left: Long,
    right: Long,
    pendingLabel: String,
    pendingReason: String
  ): (Seq[(String, Int, Long, Long)], Seq[(String, String)]) =
    val cases: Seq[(String, Int, Long, Long)] = Seq(
      (label, command, left, right)
    )
    val proofObligations: Seq[(String, String)] = Seq(
      (pendingLabel, pendingReason)
    )
    (cases, proofObligations)

  // Use Seq() for either declaration when its list is empty.
  // In a literal-only response, replace supplied names with task-derived literals;
  // write a Long as a hexadecimal literal ending in L. No values are prescribed here.
  // A pending reason is metadata, not a discharged proof or a solver outcome.
