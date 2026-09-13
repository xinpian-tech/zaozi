// Negative regression only: b deliberately has no initial/reset assignment.
module clock_probe(input clock, c2, reset, x, output reg [7:0] a,b);
  always @(posedge clock or posedge reset)
    if (reset) a <= 0; else a <= a + (x ? 2 : 1);
  always @(posedge c2)
    if (!reset) b <= b + 1;
endmodule
