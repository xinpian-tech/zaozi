module clock_probe(input clock, c2, reset, x, output reg [7:0] a,b);
  always @(posedge clock or posedge reset)
    if (reset) a <= 0; else a <= a + (x ? 2 : 1);
  always @(posedge c2 or posedge reset)
    if (reset) b <= 0; else b <= b + 1;
  goal: cover property (@(posedge clock) !reset && a == 5 && b >= 5);
endmodule
