// Synthetic initialization regression, not a RAG source or DUT replacement.
module initial_state_fixture #(parameter WIDTH=8)(
    input wire clk, rst, enable,
    input wire [1:0] address,
    input wire [WIDTH-1:0] payload,
    output reg [WIDTH-1:0] data = WIDTH'(165),
    output reg [WIDTH-1:0] counter = WIDTH'(5),
    output reg flag = 1'b1
);
reg [WIDTH-1:0] memory [0:3];
integer i;
initial for (i=0; i<4; i=i+1) memory[i] = WIDTH'(i+9);
always @(posedge clk) begin
    counter <= counter+1;
    if (enable) begin
        memory[address] <= payload;
        data <= memory[address];
        flag <= 1;
    end
    if (rst) flag <= 0;
end
endmodule
