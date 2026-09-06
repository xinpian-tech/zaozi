// SPDX-License-Identifier: Apache-2.0
import me.jiuyang.utlib.*
import utest.*

// Synthetic API fixtures only. This file is not a RAG source.
object FrameworkExamplesTest extends TestSuite:
  private val spec = AbiSpec(
    "example_bus",
    Seq(AbiPort("payload", AbiRole.Drive, 8, false)),
    AbiSpec.AbiVersion
  )

  val tests: Tests = Tests:
    test("response example preserves caller expression and pending metadata"):
      val response = FrameworkDataExample.response("example", "caller_expression", "pending", "caller reason")
      assert(response("intents")(0)("expression").str == "caller_expression")
      assert(response("proofObligations")(0)("reason").str == "caller reason")

    test("generated outcome becomes ABI stimulus"):
      val trace = Trace(1, Map("payload" -> Vector(BigInt(7)), "unused" -> Vector(BigInt(9))))
      val interpreted = FrameworkPipelineExample.interpret(GenerateOutcome.Generated(trace), spec)
      assert(interpreted == Right(AbstractStimulus(spec, Vector(Beat(Map("payload" -> BigInt(7)))))))

    test("infeasible does not fabricate a stimulus"):
      val interpreted = FrameworkPipelineExample.interpret(GenerateOutcome.Infeasible, spec)
      assert(interpreted.isLeft)

    test("unknown retains the diagnostic and does not count as infeasible"):
      val interpreted = FrameworkPipelineExample.interpret(GenerateOutcome.Unknown("test timeout"), spec)
      assert(interpreted == Left("No conclusive solver result: test timeout"))

    test("missing ABI signal is not silently filled"):
      intercept[IllegalArgumentException] {
        FrameworkPipelineExample.interpret(GenerateOutcome.Generated(Trace(1, Map.empty)), spec)
      }

    test("a synthetic ABI stimulus can be exported through the codec"):
      val trace = Trace(1, Map("payload" -> Vector(BigInt(7))))
      val stimulus = FrameworkPipelineExample.interpret(GenerateOutcome.Generated(trace), spec).toOption.get
      val rendered = UvmSequence("example_seq", "example_item").render(stimulus)
      assert(rendered.contains("class example_seq extends uvm_sequence #(example_item)"))
      assert(rendered.contains("txn.payload = 8'h7;"))
