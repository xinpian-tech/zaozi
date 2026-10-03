// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
module IOMuxPadModel (
  inout wire PAD,
  input wire A, IE, OE, PU, PD, CS, SL, PDRV0, PDRV1,
  output wire Y
);
  bufif1 (PAD, A, OE);
  assign (pull1, pull0) PAD = !OE && PU && !PD ? 1'b1 : 1'bz;
  assign (pull1, pull0) PAD = !OE && !PU && PD ? 1'b0 : 1'bz;
  assign Y = IE & PAD;
endmodule

module IOMuxPad24Model (
  inout wire PAD,
  input wire A, IE, OE, PU, PD, CS, SL,
  output wire Y
);
  IOMuxPadModel model (
    .PAD(PAD), .A(A), .IE(IE), .OE(OE), .PU(PU), .PD(PD),
    .CS(CS), .SL(SL), .PDRV0(1'b0), .PDRV1(1'b0), .Y(Y)
  );
endmodule
