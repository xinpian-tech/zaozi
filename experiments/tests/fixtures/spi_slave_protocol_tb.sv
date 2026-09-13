`timescale 1ns/1ps
module spi_slave_protocol_tb;
  bit [3:0] done = 0;
  for (genvar mode=0;mode<4;mode++) begin: modes
    localparam CPOL=mode/2;
    localparam CPHA=mode%2;
    logic clk=0, rst=1, sclk=CPOL, mosi=0;
    logic [7:0] ss_n='1;
    wire miso;
    spi_slave_bfm #(.CPOL(CPOL),.CPHA(CPHA)) bfm(.*);
    always #1 clk=~clk;
    task automatic transfer(input bit [7:0] data);
      bit [7:0] observed=0;
      for (int bitno=7;bitno>=0;bitno--) begin
        mosi=data[bitno]; #2;
        sclk=~CPOL; #2;
        if (!CPHA) observed[bitno]=miso;
        sclk=CPOL; #2;
        if (CPHA) observed[bitno]=miso;
      end
      if (observed !== 8'hA3 || bfm.received !== data)
        $fatal(1,"SPI mode %0d full-word mismatch: response=%h received=%h",mode,observed,bfm.received);
    endtask
    initial begin
      #4; rst=0; #2;
      if (miso !== 1'bz) $fatal(1,"unselected slave drives MISO");
      ss_n=8'hFE; #2;
      transfer(8'h96); transfer(8'h3C);
      ss_n='1; #2;
      if (miso !== 1'bz) $fatal(1,"deselected slave drives MISO");
      ss_n=8'h7F; #2;
      transfer(8'hE1);
      if (bfm.transfer_count != 3) $fatal(1,"SPI transfer count differs");
      // Abort a partial frame with reset; next complete word must restart.
      sclk=~CPOL; #2; rst=1; #2; sclk=CPOL; #2; rst=0; #2;
      transfer(8'h42);
      done[mode]=1;
    end
  end
  initial begin
    wait (&done); $display("SPI_SLAVE_PROTOCOL_PASS four modes, 8 chip selects, full words, reset"); $finish;
  end
  initial begin #10000; $fatal(1,"SPI regression timeout"); end
endmodule
