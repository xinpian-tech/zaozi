// Tool-semantics fixture only, not a benchmark intent or experimental UT.
module tristate_cover_probe(
  input clock, reset, enable, write,
  input [3:0] data,
  output [3:0] bus,
  output reg [3:0] registered_bus
);
  reg [3:0] stored;
  always @(posedge clock)
    if (write) stored <= data;
  assign bus = enable ? stored : 4'bzzzz;
  always @(posedge clock or posedge reset)
    if (reset) registered_bus <= 4'b0;
    else registered_bus <= bus;

  floating_plain: cover property (@(posedge clock) !enable && (&bus));
  floating_case: cover property (@(posedge clock) !enable && (bus === 4'hf));
  floating_reduction: cover property (@(posedge clock) !enable && ((&bus) === 1'b1));
  floating_equality: cover property (@(posedge clock) !enable && ((bus == 4'hf) === 1'b1));
  floating_bit: cover property (@(posedge clock) !enable && (bus[0] === 1'b1));
  floating_detected: cover property (@(posedge clock) !enable && (bus === 4'bzzzz));
  driven_case: cover property (@(posedge clock) enable && (bus === 4'hf));
  registered_case: cover property (@(posedge clock) !enable ##1 (registered_bus === 4'hf));
  registered_detected: cover property (@(posedge clock) !enable ##1 (registered_bus === 4'bzzzz));
endmodule
