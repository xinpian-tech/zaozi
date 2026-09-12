// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class "me.jiuyang.stdlib.iomux.IOMux" --
// RUN: rm -rf %t.dir && mkdir -p %t.dir/32 %t.dir/64 %t.dir/empty
// RUN: cd %t.dir/32 && %{test} config config.json --pinCount 9 --hsSlots 3 --dataWidth 32 --addressWidth 16 --routes '{"pin":0,"slot":0}' --routes '{"pin":0,"slot":1}' --routes '{"pin":1,"slot":0}' --routes '{"pin":8,"slot":0}' --routes '{"pin":8,"slot":2}'
// RUN: cd %t.dir/32 && %{test} design config.json
// RUN: cd %t.dir/32 && firtool IOMux_*.mlirbc --disable-all-randomization --strip-debug-info | FileCheck %s --check-prefixes=HS-COVER,COMMON,W32
// RUN: cd %t.dir/64 && %{test} config config.json --pinCount 17 --hsSlots 3 --dataWidth 64 --addressWidth 16 --routes '{"pin":0,"slot":0}' --routes '{"pin":0,"slot":1}' --routes '{"pin":1,"slot":0}' --routes '{"pin":16,"slot":0}' --routes '{"pin":16,"slot":2}'
// RUN: cd %t.dir/64 && %{test} design config.json
// RUN: cd %t.dir/64 && firtool IOMux_*.mlirbc --disable-all-randomization --strip-debug-info | FileCheck %s --check-prefixes=HS-COVER,COMMON,W64
// RUN: cd %t.dir/empty && %{test} config config.json --pinCount 1 --hsSlots 1 --dataWidth 32 --addressWidth 9
// RUN: cd %t.dir/empty && %{test} design config.json
// RUN: cd %t.dir/empty && firtool IOMux_*.mlirbc --disable-all-randomization --strip-debug-info | FileCheck %s --check-prefix=EMPTY
// DEFINE: %{ls} = --pinCount 3 --hsSlots 4 --addressWidth 12 --lsPools '{"pins":[0,1],"channels":[{"channel":0,"receive":true},{"channel":7}],"reset":7}' --lsPools '{"pins":[2],"channels":[{"channel":255,"receive":true}]}'
// RUN: mkdir -p %t.dir/ls32 %t.dir/ls64
// RUN: cd %t.dir/ls32 && %{test} config config.json %{ls} --dataWidth 32 && %{test} design config.json
// RUN: cd %t.dir/ls32 && firtool IOMux_*.mlirbc --disable-all-randomization --strip-debug-info | FileCheck %s --check-prefixes=LS-COVER,LS,LS32
// RUN: cd %t.dir/ls64 && %{test} config config.json %{ls} --dataWidth 64 && %{test} design config.json
// RUN: cd %t.dir/ls64 && firtool IOMux_*.mlirbc --disable-all-randomization --strip-debug-info | FileCheck %s --check-prefixes=LS-COVER,LS,LS64
// RUN: mkdir -p %t.dir/wide
// RUN: cd %t.dir/wide && %{test} config config.json --pinCount 1 --hsSlots 16 --dataWidth 32 --addressWidth 9 --routes '{"pin":0,"slot":15}' && %{test} design config.json
// RUN: cd %t.dir/wide && firtool IOMux_*.mlirbc --disable-all-randomization --strip-debug-info | FileCheck %s --check-prefix=WIDE
// RUN: rm -rf %t.dir

// HS-COVER: iomux_hs_pin_0_slot_1:
// HS-COVER: cover property
// HS-COVER: iomux_unaligned_access:
// HS-COVER: cover property

// COMMON-LABEL: module IOMux_{{[0-9a-f]+}}(
// W32: wire [[UNALIGNED:.*]] = |(req_bits_address[1:0]);
// W32-NEXT: wire [[WINDOW:.*]] = req_bits_address < 16'h4000;
// W32: [[UNALIGNED]] & [[WINDOW]] ? 14'h20 : req_bits_address[15:2];
// W32: req_bits_mask_layerCapture = req_bits_read ? 4'hF : req_bits_mask;
// W64: wire [[UNALIGNED:.*]] = |(req_bits_address[2:0]);
// W64-NEXT: wire [[WINDOW:.*]] = req_bits_address < 16'h4000;
// W64: [[UNALIGNED]] & [[WINDOW]] ? 13'h10 : req_bits_address[15:3];
// W64: req_bits_mask_layerCapture = req_bits_read ? 8'hFF : req_bits_mask;
// COMMON: reg [1:0] [[PIN0:_GEN_[0-9]+(_layerCapture)?]];
// COMMON: wire [[RESET:_GEN[_0-9]*]] = ~resetN;
// COMMON: reg [1:0] [[PIN1:_GEN_[0-9]+(_layerCapture)?]];
// COMMON: rsp_bits_read_0 = [[RESPONSE:_GEN_[0-9]+(_layerCapture)?]][{{[0-9]+}}];
// W32: req_bits_index_layerCapture == 14'h40;
// W32: req_bits_index_layerCapture == 14'h41;
// W64: req_bits_index_layerCapture == 13'h20;
// W64: req_bits_index_layerCapture == 13'h21;
// COMMON: wire rsp_bits_error_0 =
// W32: | [[RESPONSE]][{{[0-9]+:[0-9]+}}] < 14'h1000);
// W64: | [[RESPONSE]][{{[0-9]+:[0-9]+}}] < 13'h800);
// COMMON-DAG: wire [[LAST2:_GEN_[0-9]+(_layerCapture)?]] = [[LASTPIN:_GEN_[0-9]+(_layerCapture)?]] == 2'h2;
// COMMON-DAG: wire [[LAST0:_GEN_[0-9]+(_layerCapture)?]] = [[LASTPIN]] == 2'h0;
// COMMON-DAG: wire [[P1S0:_GEN_[0-9]+(_layerCapture)?]] = [[PIN1]] == 2'h0;
// COMMON-DAG: wire [[P0S0:_GEN_[0-9]+(_layerCapture)?]] = [[PIN0]] == 2'h0;
// COMMON-DAG: wire [[P0S1:_GEN_[0-9]+(_layerCapture)?]] = [[PIN0]] == 2'h1;
// COMMON: always @(posedge clock or posedge [[RESET]]) begin
// COMMON-NEXT: if ([[RESET]]) begin
// COMMON-NEXT: [[PIN0]] <= 2'h0;
// COMMON-NEXT: [[PIN1]] <= 2'h0;
// COMMON: [[LASTPIN]] <= 2'h0;
// COMMON: [[PIN0]] <= req_bits_data[1:0];
// COMMON: [[PIN1]] <= req_bits_data[5:4];
// COMMON: [[LASTPIN]] <= req_bits_data[1:0];
// W32: ? 32'h30009
// W32: ? 32'h494F4D58
// W64: ? 64'h30011
// W64: ? 64'h494F4D5800000000 : 64'h0,
// COMMON: assign padInputEnable =
// COMMON-NEXT: {[[LAST2]] ? inputEnable[4] : [[LAST0]] & inputEnable[3],
// W32-NEXT: 6'h0,
// W64-NEXT: 14'h0,
// COMMON-NEXT: [[P1S0]] & inputEnable[2],
// COMMON-NEXT: [[P0S1]] ? inputEnable[1] : [[P0S0]] & inputEnable[0]};
// COMMON-NEXT: assign padOutputValue =
// COMMON-NEXT: {[[LAST2]] ? outputValue[4] : [[LAST0]] & outputValue[3],
// W32-NEXT: 6'h0,
// W64-NEXT: 14'h0,
// COMMON-NEXT: [[P1S0]] & outputValue[2],
// COMMON-NEXT: [[P0S1]] ? outputValue[1] : [[P0S0]] & outputValue[0]};
// COMMON-NEXT: assign padOutputEnable =
// COMMON-NEXT: {[[LAST2]] ? outputEnable[4] : [[LAST0]] & outputEnable[3],
// W32-NEXT: 6'h0,
// W64-NEXT: 14'h0,
// COMMON-NEXT: [[P1S0]] & outputEnable[2],
// COMMON-NEXT: [[P0S1]] ? outputEnable[1] : [[P0S0]] & outputEnable[0]};
// W32-NEXT: assign inputValue = {{.*}}padInputValue[8]{{.*}}padInputValue[1:0], padInputValue[0]};
// W64-NEXT: assign inputValue = {{.*}}padInputValue[16]{{.*}}padInputValue[1:0], padInputValue[0]};

// EMPTY-LABEL: module IOMux_{{[0-9a-f]+}}(
// EMPTY: assign padInputEnable = 1'h0;
// EMPTY-NEXT: assign padOutputValue = 1'h0;
// EMPTY-NEXT: assign padOutputEnable = 1'h0;

// LS-COVER: iomux_ls_pool_0_transmit:
// LS-COVER: cover property
// LS-COVER: iomux_ls_channel_0_independent_receive:
// LS-COVER: cover property
// LS-COVER: iomux_ls_pool_1_transmit:
// LS-COVER: cover property
// LS-COVER: iomux_ls_channel_255_independent_receive:
// LS-COVER: cover property

// LS-LABEL: module IOMux_{{[0-9a-f]+}}(
// LS32: req_bits_index_layerCapture == 10'h300;
// LS64: req_bits_index_layerCapture == 9'h180;
// LS32: req_bits_index_layerCapture == 10'h340;
// LS64: req_bits_index_layerCapture == 9'h1A0;
// LS32: req_bits_index_layerCapture == 10'h37F;
// LS64: req_bits_index_layerCapture == 9'h1BF;
// LS: wire [[P0C0:_GEN_[0-9]+(_layerCapture)?]] = [[TX0:_GEN_[0-9]+(_layerCapture)?]] == 8'h0;
// LS-NEXT: wire [[P0C7:_GEN_[0-9]+(_layerCapture)?]] = [[TX0]] == 8'h7;
// LS-NEXT: wire [[HS0:_GEN_[0-9]+(_layerCapture)?]] = {{.*}} == 2'h0;
// LS-NEXT: wire [[P1C0:_GEN_[0-9]+(_layerCapture)?]] = [[TX1:_GEN_[0-9]+(_layerCapture)?]] == 8'h0;
// LS-NEXT: wire [[P1C7:_GEN_[0-9]+(_layerCapture)?]] = [[TX1]] == 8'h7;
// LS-NEXT: wire [[HS1:_GEN_[0-9]+(_layerCapture)?]] = {{.*}} == 2'h0;
// LS-NEXT: wire [[P2C255:_GEN_[0-9]+(_layerCapture)?]] = &[[TX2:_GEN_[0-9]+(_layerCapture)?]];
// LS-NEXT: wire [[HS2:_GEN_[0-9]+(_layerCapture)?]] = {{.*}} == 2'h0;
// LS: wire [[RX0P0:.*]] = [[RX0:_GEN_[0-9]+]] == 8'h0;
// LS-NEXT: wire [[RX0P1:.*]] = [[RX0]] == 8'h1;
// LS-NEXT: wire [[RX255P2:.*]] = [[RX255:_GEN_[0-9]+]] == 8'h2;
// LS: [[TX0]] <= 8'h7;
// LS-NEXT: [[TX1]] <= 8'h7;
// LS-NEXT: [[TX2]] <= 8'h0;
// LS-NEXT: [[RX0]] <= 8'h0;
// LS-NEXT: [[RX255]] <= 8'h0;
// LS: [[TX1]] <= req_bits_data[15:8];
// LS32: [[RX255]] <= req_bits_data[31:24];
// LS64: [[RX255]] <= req_bits_data[63:56];
// LS32: ? 32'h20100
// LS32: ? 32'h20
// LS64: ? 64'h20100
// LS64: ? 64'h2000040003
// LS: assign padInputEnable =
// LS-NEXT: {[[HS2]] & [[P2C255]] & lsInputEnable[255],
// LS-NEXT: [[HS1]]
// LS-NEXT: & ([[P1C7]]
// LS-NEXT: ? lsInputEnable[7]
// LS-NEXT: : [[P1C0]] & lsInputEnable[0]),
// LS-NEXT: [[HS0]]
// LS-NEXT: & ([[P0C7]]
// LS-NEXT: ? lsInputEnable[7]
// LS-NEXT: : [[P0C0]] & lsInputEnable[0])};
// LS: assign lsInputValue =
// LS-NEXT: {[[RX255P2]] & padInputValue[2],
// LS-NEXT: 254'h0,
// LS-NEXT: [[RX0P1]]
// LS-NEXT: ? padInputValue[1]
// LS-NEXT: : [[RX0P0]] & padInputValue[0]};

// WIDE: iomux_hs_pin_0_slot_15:
// WIDE: cover property
// WIDE-LABEL: module IOMux_{{[0-9a-f]+}}(
// WIDE: <= req_bits_data[3:0];
// WIDE: assign padOutputEnable = {{.*}} & outputEnable;
