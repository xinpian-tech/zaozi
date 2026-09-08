// SPDX-License-Identifier: Apache-2.0

// Task: serialize a caller-supplied complete UT source and pending proof metadata.
// Given: one UT module name, all Gen labels, complete Scala source, and a supplied proof reason.
// Example solution: the source is compiled verbatim alongside the framework binding and runner.
// This example provides no port names, candidate values, predicates or proof conclusions.
object FrameworkDataExample:
  def stop(reason: String): ujson.Value =
    ujson.Obj("stop" -> ujson.Obj("reason" -> reason), "proofObligations" -> ujson.Arr())

  def response(module: String, generationLabels: Seq[String], source: String,
               proofLabel: String, reason: String): ujson.Value =
    ujson.Obj(
      "ut" -> ujson.Obj("module" -> module,
        "generationLabels" -> ujson.Arr.from(generationLabels), "source" -> source),
      "proofObligations" -> ujson.Arr(ujson.Obj("label" -> proofLabel, "reason" -> reason))
    )
