module alu_deadcode_formal(
  input         clock,
  input         reset,
  input  [31:0] a,
  input  [31:0] b,
  input  [3:0]  op,
  input         start
);
  wire [31:0] result;
  wire [3:0]  flags;
  wire        done;

  alu_top dut (
    .clk(clock),
    .rst_n(~reset),
    .a(a),
    .b(b),
    .op(op),
    .start(start),
    .result(result),
    .flags(flags),
    .done(done)
  );

  // Match the reset convention used by the generated Zaozi/JasperGold harness.
  rst_low: assume property (~reset);

  // RTL line 336 executes only through the default arm of fp_counter's case.
  line_336_fp_counter_default: cover property (
    dut.fp_active &&
    dut.fp_counter != 3'd1 &&
    dut.fp_counter != 3'd2 &&
    dut.fp_counter != 3'd3 &&
    dut.fp_counter != 3'd4
  );

  // Exact path condition for RTL line 401, expressed without relying on the
  // procedural scratch register f2i_shift_right.
  line_401_f2i_shift_ge_32: cover property (
    dut.fp2int_active && dut.fp2int_counter == 1'b1 &&
    !dut.f2i_is_nan && !dut.f2i_is_zero &&
    !(dut.f2i_is_inf || dut.f2i_exp >= 8'd158) &&
    !(dut.f2i_exp < 8'd127) &&
    (8'd158 - dut.f2i_exp >= 8'd32)
  );
endmodule
