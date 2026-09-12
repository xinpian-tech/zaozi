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
// RUN: cd %t.dir/ls32 && firtool IOMux_*.mlirbc --disable-all-randomization --strip-debug-info > design.sv && FileCheck %s --check-prefixes=LS-COVER,LS,LS32 < design.sv
// RUN: cd %t.dir/ls64 && %{test} config config.json %{ls} --dataWidth 64 && %{test} design config.json
// RUN: cd %t.dir/ls64 && firtool IOMux_*.mlirbc --disable-all-randomization --strip-debug-info > design.sv && FileCheck %s --check-prefixes=LS-COVER,LS,LS64 < design.sv
// DEFINE: %{wide} = --pinCount 257 --hsSlots 257 --addressWidth 16 --impId 18364758544493064720 --routes '{"pin":256,"slot":256}' --lsPools '{"pins":[0,256],"channels":[{"channel":0,"receive":true},{"channel":256,"receive":true}],"reset":256}'
// RUN: mkdir -p %t.dir/wide32 %t.dir/wide64
// RUN: cd %t.dir/wide32 && %{test} config config.json %{wide} --dataWidth 32 && %{test} design config.json
// RUN: cd %t.dir/wide32 && firtool IOMux_*.mlirbc --disable-all-randomization --strip-debug-info | FileCheck %s --check-prefix=WIDE
// RUN: cd %t.dir/wide32 && %{test} header config.json > registers.h && FileCheck %s --check-prefix=HEADER < registers.h
// RUN: cd %t.dir/wide64 && %{test} config config.json %{wide} --dataWidth 64 && %{test} design config.json
// RUN: cd %t.dir/wide64 && firtool IOMux_*.mlirbc --disable-all-randomization --strip-debug-info | FileCheck %s --check-prefix=WIDE
// RUN: cd %t.dir/wide64 && %{test} header config.json > registers.h
// RUN: cmp %t.dir/wide32/registers.h %t.dir/wide64/registers.h
// DEFINE: %{options} = --pinCount 2 --hsSlots 2 --addressWidth 12 --option '{"gpio":true,"interrupt":true,"padControl":true,"invert":true,"rxOverride":true}' --routes '{"pin":0,"slot":0,"tie":true}' --lsPools '{"pins":[1],"channels":[{"channel":1,"receive":true,"tie":false}]}' --pad '{"classes":[{"control":[{"name":"drive","table":{"width":2,"rows":[{"name":"low","value":"0"},{"name":"high","value":"3"}]}}],"safe":{}}],"pinClass":[0,0]}'
// RUN: mkdir -p %t.dir/options32 %t.dir/options64
// RUN: cd %t.dir/options32 && %{test} config config.json %{options} --dataWidth 32 && %{test} design config.json
// RUN: cd %t.dir/options32 && firtool IOMux_*.mlirbc --disable-all-randomization --strip-debug-info | FileCheck %s --check-prefix=OPTIONS
// RUN: cd %t.dir/options32 && %{test} header config.json > registers.h
// RUN: cd %t.dir/options64 && %{test} config config.json %{options} --dataWidth 64 && %{test} design config.json
// RUN: cd %t.dir/options64 && %{test} header config.json > registers.h
// RUN: cmp %t.dir/options32/registers.h %t.dir/options64/registers.h
// RUN: cd %t.dir/empty && %{test} header config.json | FileCheck %s --check-prefix=IMPID-ZERO
// RUN: cd %t.dir/empty && %{test} config max.json --pinCount 1 --hsSlots 1 --dataWidth 32 --addressWidth 9 --impId 18446744073709551615
// RUN: cd %t.dir/empty && %{test} header max.json | FileCheck %s --check-prefix=IMPID-MAX
// RUN: cd %t.dir/empty && not %{test} config negative.json --pinCount 1 --hsSlots 1 --dataWidth 32 --addressWidth 9 --impId=-1 2>&1 | FileCheck %s --check-prefix=IMPID-INVALID
// RUN: cd %t.dir/empty && not %{test} config overflow.json --pinCount 1 --hsSlots 1 --dataWidth 32 --addressWidth 9 --impId 18446744073709551616 2>&1 | FileCheck %s --check-prefix=IMPID-INVALID
// RUN: mkdir -p %t.dir/native
// RUN: for width in 8 16 128; do %{test} config %t.dir/native/$width.json --pinCount 1 --hsSlots 257 --dataWidth $width --addressWidth 12; done
// RUN: cd %t.dir/native && %{test} design 8.json && firtool IOMux_*.mlirbc --disable-all-randomization --strip-debug-info | FileCheck %s --check-prefix=NATIVE8
// RUN: not %{test} config %t.dir/native/invalid.json --pinCount 1 --hsSlots 1 --dataWidth 24 --addressWidth 9 2>&1 | FileCheck %s --check-prefix=WIDTH-INVALID
// RUN: python3 -c 'import json,sys; json.dump(dict(pinCount=1,hsSlots=2,dataWidth=8,addressWidth=12,option=dict(padControl=True),pad=dict(classes=[dict(control=[dict(name="c"+str(i),table=dict(width=2,rows=[dict(name="low",value="0"),dict(name="high",value="3")])) for i in range(17)])],pinClass=[0])),open(sys.argv[1],"w"))' %t.dir/control17.json
// RUN: %{test} header %t.dir/control17.json | FileCheck %s --check-prefix=CONTROL17
// RUN: for width in 32 64; do %{test} config %t.dir/exact$width.json --pinCount 1 --hsSlots 1 --dataWidth $width --addressWidth 9 --lsPools '{"pins":[0],"channels":[{"channel":239,"receive":true}]}' && %{test} header %t.dir/exact$width.json | FileCheck %s --check-prefix=EXACT; done
// RUN: not %{test} config %t.dir/over.json --pinCount 1 --hsSlots 1 --dataWidth 32 --addressWidth 9 --lsPools '{"pins":[0],"channels":[{"channel":247,"receive":true}]}' 2>&1 | FileCheck %s --check-prefix=CAPACITY
// RUN: rm -rf %t.dir

// HS-COVER: iomux_hs_pin_0_slot_1:
// HS-COVER: cover property
// HS-COVER: iomux_unaligned_access:
// HS-COVER: cover property

// COMMON-LABEL: module IOMux_{{[0-9a-f]+}}(
// COMMON: reg [1:0] [[PIN0:_GEN_[0-9]+(_layerCapture)?]];
// COMMON: wire [[RESET:_GEN[_0-9]*]] = ~resetN;
// COMMON: reg [1:0] [[PIN1:_GEN_[0-9]+(_layerCapture)?]];
// COMMON: rsp_bits_read_0 = [[RESPONSE:_GEN_[0-9]+(_layerCapture)?]][{{[0-9]+}}];
// W32: wordIndex_layerCapture == 14'h40;
// W32: wordIndex_layerCapture == 14'h41;
// W64: wordIndex_layerCapture == 13'h20;
// W64: wordIndex_layerCapture == 13'h21;
// COMMON: assign rsp_bits_error_0 =
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
// COMMON: assign padInputEnable =
// COMMON-NEXT: {[[LAST2]]{{[[:space:]]*}}? inputEnable[4]{{[[:space:]]*}}: [[LAST0]] & inputEnable[3],
// W32-NEXT: 6'h0,
// W64-NEXT: 14'h0,
// COMMON-NEXT: [[P1S0]] & inputEnable[2],
// COMMON-NEXT: [[P0S1]]{{[[:space:]]*}}? inputEnable[1]{{[[:space:]]*}}: [[P0S0]] & inputEnable[0]};
// COMMON-NEXT: assign padOutputValue =
// COMMON-NEXT: {[[LAST2]]{{[[:space:]]*}}? outputValue[4]{{[[:space:]]*}}: [[LAST0]] & outputValue[3],
// W32-NEXT: 6'h0,
// W64-NEXT: 14'h0,
// COMMON-NEXT: [[P1S0]] & outputValue[2],
// COMMON-NEXT: [[P0S1]]{{[[:space:]]*}}? outputValue[1]{{[[:space:]]*}}: [[P0S0]] & outputValue[0]};
// COMMON-NEXT: assign padOutputEnable =
// COMMON-NEXT: {[[LAST2]]{{[[:space:]]*}}? outputEnable[4]{{[[:space:]]*}}: [[LAST0]] & outputEnable[3],
// W32-NEXT: 6'h0,
// W64-NEXT: 14'h0,
// COMMON-NEXT: [[P1S0]] & outputEnable[2],
// COMMON-NEXT: [[P0S1]]{{[[:space:]]*}}? outputEnable[1]{{[[:space:]]*}}: [[P0S0]] & outputEnable[0]};
// W32-NEXT: assign inputValue = {{.*}}padInputValue[8]{{.*}}padInputValue[1:0], padInputValue[0]};
// W64-NEXT: assign inputValue = {{.*}}padInputValue[16]{{.*}}padInputValue[1:0], padInputValue[0]};

// EMPTY-LABEL: module IOMux_{{[0-9a-f]+}}(
// EMPTY: assign padInputEnable = 1'h0;
// EMPTY-NEXT: assign padOutputValue = 1'h0;
// EMPTY-NEXT: assign padOutputEnable = 1'h0;

// LS-COVER: iomux_ls_pool_0_selection:
// LS-COVER: cover property
// LS-COVER: iomux_ls_channel_0_independent_receive:
// LS-COVER: cover property
// LS-COVER: iomux_ls_pool_1_selection:
// LS-COVER: cover property
// LS-COVER: iomux_ls_channel_255_independent_receive:
// LS-COVER: cover property

// LS-LABEL: module IOMux_{{[0-9a-f]+}}(
// LS32: wordIndex_layerCapture == 10'h42;
// LS64: wordIndex_layerCapture == 9'h21;
// LS32: wordIndex_layerCapture == 10'h44;
// LS64: wordIndex_layerCapture == 9'h22;
// LS32: wordIndex_layerCapture == 10'h83;
// LS64: wordIndex_layerCapture == 9'h41;
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

// WIDE: iomux_hs_pin_256_slot_256:
// WIDE: cover property
// WIDE-LABEL: module IOMux_{{[0-9a-f]+}}(
// WIDE: req_bits_address < 16'h718;
// WIDE: req_bits_mask_layerCapture[0];
// WIDE-NEXT: {{.*}}req_bits_mask_layerCapture[1];
// WIDE: <= req_bits_data[7:0];
// WIDE: <= req_bits_data[8];
// WIDE: assign inputValue = padInputValue[256];
// WIDE: assign lsInputValue =
// WIDE: ? padInputValue[256]

// HEADER: #define IOMUX_IMPID_OFFSET 0x0ULL
// HEADER-NEXT: #define IOMUX_IMPID_VALUE 0xfedcba9876543210ULL
// HEADER-NEXT: #define IOMUX_IMPID_WIDTH 0x40ULL
// HEADER: #define IOMUX_HS_SELECT_OFFSET 0x100ULL
// HEADER-NEXT: #define IOMUX_HS_SELECT_LANE_BITS 0x10ULL
// HEADER-NEXT: #define IOMUX_LS_SELECT_OFFSET 0x308ULL
// HEADER-NEXT: #define IOMUX_LS_SELECT_LANE_BITS 0x10ULL
// HEADER-NEXT: #define IOMUX_LS_RX_PIN_OFFSET 0x510ULL
// HEADER-NEXT: #define IOMUX_LS_RX_PIN_LANE_BITS 0x10ULL
// HEADER-NEXT: #define IOMUX_APERTURE 0x718ULL

// OPTIONS: iomux_output_value_source_3:
// OPTIONS: cover property
// OPTIONS: iomux_rise_pending_disabled:
// OPTIONS: cover property
// OPTIONS: iomux_interrupt_delivery:
// OPTIONS: cover property
// OPTIONS: iomux_hs_pin_0_slot_0_override:
// OPTIONS: cover property
// OPTIONS: iomux_ls_channel_1_override:
// OPTIONS: cover property
// OPTIONS: iomux_pad_force:
// OPTIONS: cover property
// OPTIONS: iomux_pad_control_0_register:
// OPTIONS: cover property
// OPTIONS-LABEL: module IOMux_{{[0-9a-f]+}}(
// OPTIONS: input          pad_force,
// OPTIONS: output {{.*}} pad_control_0,
// OPTIONS: output {{.*}} pad_pin_0_control_0,
// OPTIONS-NEXT: pad_pin_1_control_0,
// OPTIONS: output        interrupt,

// IMPID-ZERO: #define IOMUX_IMPID_VALUE 0x0ULL
// IMPID-MAX: #define IOMUX_IMPID_VALUE 0xffffffffffffffffULL
// IMPID-INVALID: impId must fit an unsigned 64-bit word

// NATIVE8-LABEL: module IOMux_
// NATIVE8: input  [7:0]  req_bits_data,
// WIDTH-INVALID: dataWidth must be a power of two and at least 8
// CONTROL17: #define IOMUX_FIELD_pin_5f0_5fcontrol_5f16_OFFSET 0x11aULL
// CONTROL17-NEXT: #define IOMUX_FIELD_pin_5f0_5fcontrol_5f16_BIT 0x0ULL
// CONTROL17-NEXT: #define IOMUX_FIELD_pin_5f0_5fcontrol_5f16_WIDTH 0x1ULL
// EXACT: #define IOMUX_APERTURE 0x200ULL
// CAPACITY: IOMux register window does not fit addressWidth
