// SPDX-License-Identifier: Apache-2.0

// Task: serialize a caller-supplied generation expression and pending proof metadata.
// Given: a label, a Scala predicate/sequence/property, and an independently supplied proof reason.
// Example solution: the expression is later typechecked in a run-generated UT.
// This example provides no port names, candidate values, predicates or proof conclusions.
object FrameworkDataExample:
  def response(label: String, expression: String, proofLabel: String, reason: String): ujson.Value =
    ujson.Obj(
      "intents" -> ujson.Arr(ujson.Obj("label" -> label, "expression" -> expression)),
      "proofObligations" -> ujson.Arr(ujson.Obj("label" -> proofLabel, "reason" -> reason))
    )
