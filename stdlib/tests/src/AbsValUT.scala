// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.stdlib

import me.jiuyang.utlib.*

import utest.*

object AbsValUT extends TestSuite:
  private val parameter       = AbsValParameter(8)
  private val outputDirectory = os.Path(sys.props("zaozi.utlib.outDir"), os.pwd) / AbsVal.moduleName(parameter)
  private val generator       = UTGenerator(AbsVal, parameter, cycles = 3, outputDirectory = outputDirectory)

  private def testOutputDirectory(name: String): os.Path = generator.outputDirectory / name

  private def assertCoversInputClasses(stimulus: SolvedStimulus[AbsValIO]): Unit =
    assert(stimulus.io.A.at(0) > 0)
    assert(stimulus.io.A.at(1) == 0)
    assert(stimulus.io.A.at(2) < 0)

  private def readableSv(value: String): String = value.replace("\t", "  ")

  val tests: Tests = Tests:
    test("module-owned constraints produce positive, zero, and negative inputs"):
      val stimulus = generator.solve()
      assertCoversInputClasses(stimulus)

    test("the default harness links the DUT and its verification-layer assertion"):
      val request      = generator.simulationRequest(
        generator.solve(),
        testOutputDirectory("elaboration")
      )
      val generatedSv  = readableSv(os.read(request.workDir / "generated.sv"))
      val controllerSv = os.read(request.workDir / "ZaoziSimulationController.sv")
      val layerSv      = readableSv(os.read(request.workDir / "layers-AbsVal_width8-Verification.sv"))

      val expectedModules =
        """|module AbsVal_width8_Verification();  // -:32:7
           |  always_comb  // -:44:9
           |    absval_matches_abs:
           |      assert(AbsVal_width8.absVal_layerCapture == (AbsVal_width8.sign_layerCapture
           |                                                     ? 8'h0 - AbsVal_width8.A
           |                                                     : AbsVal_width8.A));  // -:3:37, :5:12, :13:12, :35:14, :40:14, :42:14, :44:9
           |endmodule
           |
           |module AbsVal_width8(  // -:3:5
           |  input  [7:0] A,  // -:3:37
           |  output [7:0] ABSVAL  // -:3:62
           |);
           |
           |  wire [7:0] _neg_SUM;  // -:8:26
           |  wire       sign_layerCapture = A[7];  // -:5:12
           |  wire [7:0] absVal_layerCapture = sign_layerCapture ? _neg_SUM : A;  // -:5:12, :8:26, :13:12
           |  Incrementer_width8_radix4 neg (  // -:8:26
           |    .A   (~A),  // -:10:12
           |    .SUM (_neg_SUM)
           |  );  // -:8:26
           |  assign ABSVAL = absVal_layerCapture;  // -:3:5, :13:12
           |endmodule
           |
           |module Incrementer_width8_radix4(  // -:50:5
           |  input  [7:0] A,  // -:50:49
           |  output [7:0] SUM  // -:50:74
           |);
           |
           |  wire propagates = A[0] & A[1] & A[2] & A[3];  // -:52:12, :55:12, :58:12, :61:12, :76:12, :78:12, :80:13
           |  wire _GEN_18 = A[1] & A[0];  // -:52:12, :55:12, :87:13
           |  wire _GEN_20 = A[4] & propagates;  // -:64:12, :76:12, :78:12, :80:13, :91:13
           |  wire _GEN_21 = A[5] & _GEN_20;  // -:67:12, :91:13, :93:13
           |  assign SUM =
           |    {A[7] ^ A[6] & _GEN_21,
           |     A[6] ^ _GEN_21,
           |     A[5] ^ _GEN_20,
           |     A[4] ^ propagates,
           |     A[3] ^ A[2] & _GEN_18,
           |     A[2] ^ _GEN_18,
           |     A[1] ^ A[0],
           |     ~(A[0])};  // -:50:5, :52:12, :55:12, :58:12, :61:12, :64:12, :67:12, :70:12, :73:12, :76:12, :78:12, :80:13, :87:13, :89:13, :91:13, :93:13, :95:13, :97:13, :99:13, :101:13, :103:13, :105:13, :107:13, :109:13, :111:13, :133:13
           |endmodule""".stripMargin

      val expectedHarness =
        """|module UT_AbsVal_width8_51738d12();  // -:152:5
           |  wire _controller_clock;  // -:154:64
           |  wire _controller_reset;  // -:154:64
           |  reg  _GEN_0;  // -:159:17
           |  reg  _GEN_1;  // -:161:17
           |  reg  _GEN_2;  // -:163:17
           |  reg  _GEN_3;  // -:165:17
           |  always @(posedge _controller_clock) begin  // -:154:64
           |    if (_controller_reset) begin  // -:154:64
           |      _GEN_0 <= 1'h1;  // -:152:5, :159:17
           |      _GEN_1 <= 1'h0;  // -:152:5, :161:17
           |      _GEN_2 <= 1'h0;  // -:152:5, :163:17
           |      _GEN_3 <= 1'h0;  // -:152:5, :165:17
           |    end
           |    else begin  // -:154:64
           |      _GEN_0 <= 1'h0;  // -:152:5, :159:17
           |      _GEN_1 <= _GEN_0;  // -:159:17, :161:17
           |      _GEN_2 <= _GEN_1;  // -:161:17, :163:17
           |      _GEN_3 <= _GEN_2;  // -:163:17, :165:17
           |    end
           |  end // always @(posedge)
           |  `ifdef ENABLE_INITIAL_REG_  // -:152:5
           |    `ifdef FIRRTL_BEFORE_INITIAL  // -:152:5
           |      `FIRRTL_BEFORE_INITIAL  // -:152:5
           |    `endif // FIRRTL_BEFORE_INITIAL
           |    initial begin  // -:152:5
           |      automatic logic [31:0] _RANDOM[0:0];  // -:152:5
           |      `ifdef INIT_RANDOM_PROLOG_  // -:152:5
           |        `INIT_RANDOM_PROLOG_  // -:152:5
           |      `endif // INIT_RANDOM_PROLOG_
           |      `ifdef RANDOMIZE_REG_INIT  // -:152:5
           |        _RANDOM[/*Zero width*/ 1'b0] = `RANDOM;  // -:152:5
           |        _GEN_0 = _RANDOM[/*Zero width*/ 1'b0][0];  // -:152:5, :159:17
           |        _GEN_1 = _RANDOM[/*Zero width*/ 1'b0][1];  // -:152:5, :159:17, :161:17
           |        _GEN_2 = _RANDOM[/*Zero width*/ 1'b0][2];  // -:152:5, :159:17, :163:17
           |        _GEN_3 = _RANDOM[/*Zero width*/ 1'b0][3];  // -:152:5, :159:17, :165:17
           |      `endif // RANDOMIZE_REG_INIT
           |    end // initial
           |    `ifdef FIRRTL_AFTER_INITIAL  // -:152:5
           |      `FIRRTL_AFTER_INITIAL  // -:152:5
           |    `endif // FIRRTL_AFTER_INITIAL
           |  `endif // ENABLE_INITIAL_REG_
           |  ZaoziSimulationController #(
           |    .timeoutCycles(19),
           |    .trace(0),
           |    .traceFile("trace.vcd")
           |  ) controller (  // -:154:64
           |    .clock (_controller_clock),
           |    .reset (_controller_reset),
           |    .done  (_GEN_3)  // -:165:17
           |  );  // -:154:64
           |  AbsVal_width8 instance_0 (  // -:156:39
           |    .A      (_GEN_2 ? 8'hFF : _GEN_1 ? 8'h0 : {7'h0, _GEN_0}),  // -:159:17, :161:17, :163:17, :172:17, :173:7, :174:7, :176:9, :179:7, :181:9, :184:7, :185:21, :186:9
           |    .ABSVAL (/* unused */)
           |  );  // -:156:39
           |endmodule""".stripMargin

      val expectedController =
        """|
           |// Generated by CIRCT firtool-1.154.0
           |// SPDX-License-Identifier: Apache-2.0
           |// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
           |
           |module ZaoziSimulationController #(
           |  parameter integer timeoutCycles = 100,
           |  parameter         trace         = 1'b0,
           |  parameter string  traceFile     = "trace.vcd"
           |) (
           |  output logic clock,
           |  output logic reset,
           |  input  wire  done
           |);
           |  initial clock = 1'b0;
           |  always #5 clock = ~clock;
           |
           |  initial begin
           |    reset = 1'b1;
           |    repeat (4) @(posedge clock);
           |    reset = 1'b0;
           |  end
           |
           |  generate
           |    if (trace) begin : generate_trace
           |      initial begin
           |        $dumpfile(traceFile);
           |        $dumpvars(0);
           |      end
           |    end
           |  endgenerate
           |
           |  always @(posedge clock) begin
           |    if (!reset && done) begin
           |      $display("HARNESS-DONE");
           |      $finish;
           |    end
           |  end
           |
           |  initial begin
           |    repeat (timeoutCycles) @(posedge clock);
           |    $display("HARNESS-TIMEOUT after %0d cycles", timeoutCycles);
           |    $fatal;
           |  end
           |endmodule
           |
           |""".stripMargin

      val expectedLayer =
        """|// Generated by CIRCT firtool-1.154.0
           |`ifndef layers_AbsVal_width8_Verification  // -:3:5
           |  `define layers_AbsVal_width8_Verification
           |  bind AbsVal_width8 AbsVal_width8_Verification verification ();
           |`endif // not def layers_AbsVal_width8_Verification""".stripMargin

      assert(generatedSv.contains(expectedModules))
      assert(generatedSv.contains(expectedHarness))
      assert(controllerSv == expectedController)
      assert(layerSv.contains(expectedLayer))
      assert(!generatedSv.contains("anyseq"))

    test("AbsVal passes its verification-layer assertion"):
      val result = generator.run(testOutputDirectory("run"))
      assert(result.exitCode == 0)
      assert(result.log.contains("HARNESS-DONE"))
      assert(!result.log.contains("HARNESS-TIMEOUT"))

    test("a traced run writes a VCD containing the DUT ports"):
      val result = generator.run(testOutputDirectory("trace"), trace = true)
      val vcd    = os.read(result.tracePath.get)
      assert(vcd.contains("ABSVAL"))
      assert(vcd.contains(" A ") || vcd.contains(" A["))

    test("a frozen stimulus replays without solving"):
      val dir      = testOutputDirectory("replay")
      val path     = dir / "stimulus.json"
      val frozen   = generator.freeze(path)
      val reloaded = generator.loadStimulus(path)
      assert(reloaded == frozen)
      assertCoversInputClasses(reloaded)
      val result   = generator.runStimulus(reloaded, dir / "run")
      assert(result.exitCode == 0)
