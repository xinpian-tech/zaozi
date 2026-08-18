// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech

// DEFINE: %{ut} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class "me.jiuyang.stdlib.AbsValUTRun" %s --

// The whole UT flow runs from this lit test. The Scala entry derives the DPI contract, lowers
// the DUT into ONE lib harness (a flat SV module whose ports are the contract), and emits two
// C++ frontends that call the same Verilated model. Verilator builds the model with each
// frontend; the frontend owns the loop, poking the drive port and peeking the probe ports.

// width 8
// RUN: rm -rf %t.dir && mkdir -p %t.dir
// RUN: %{ut} %t.dir --width 8

// The DPI contract is derived from the DUT's IO (Drive) and Probe (Probe), not hand-written.
// RUN: FileCheck %s -check-prefix=DPI8 --input-file=%t.dir/AbsValDPI.json

// Frontend #1: a driver fed a fixed vector. The frontend owns the loop; the DUT computes |A|.
// RUN: cd %t.dir && verilator --cc --exe --build --timing --assert -Mdir drive_obj --top-module Lib_AbsValUT_width8 -Wno-fatal generated.sv layers-AbsValUT_width8-Verification.sv layers-AbsVal_width8-Verification.sv layers-Incrementer_width8_radix4-Verification.sv layers-Lib_AbsValUT_width8-Verification.sv ref_AbsValUT_width8.sv ref_AbsVal_width8.sv frontend_drive.cpp -o fe
// RUN: %t.dir/drive_obj/fe 2>&1 | FileCheck %s -check-prefix=DRIVE8

// Frontend #2: the SAME lib model, driven by the solver's stimulus — replay is just another
// frontend. A[1] is pinned to 0 by the constraints, so that cycle is exact.
// RUN: cd %t.dir && verilator --cc --exe --build --timing --assert -Mdir replay_obj --top-module Lib_AbsValUT_width8 -Wno-fatal generated.sv layers-AbsValUT_width8-Verification.sv layers-AbsVal_width8-Verification.sv layers-Incrementer_width8_radix4-Verification.sv layers-Lib_AbsValUT_width8-Verification.sv ref_AbsValUT_width8.sv ref_AbsVal_width8.sv frontend_replay.cpp -o fe
// RUN: %t.dir/replay_obj/fe 2>&1 | FileCheck %s -check-prefix=REPLAY8
// RUN: rm -rf %t.dir

// DPI8:      "dut": "AbsValUT_width8"
// DPI8:      "name": "A"
// DPI8-NEXT: "role": "Drive"
// DPI8:      "name": "ABSVAL"
// DPI8-NEXT: "role": "Probe"

// The frontend drives A and the DUT computes |A| on the same eval: a genuine poke/peek loop.
// DRIVE8: DRIVE cyc=1 A=5 ABSVAL=5
// DRIVE8: DRIVE cyc=2 A=-3 ABSVAL=3
// DRIVE8: DRIVE cyc=3 A=7 ABSVAL=7

// REPLAY8:      REPLAY cyc=1 A={{[0-9]+}} ABSVAL={{[0-9]+}}
// REPLAY8-NEXT: REPLAY cyc=2 A=0 ABSVAL=0
// REPLAY8-NEXT: REPLAY cyc=3 A=-{{[0-9]+}} ABSVAL={{[0-9]+}}
