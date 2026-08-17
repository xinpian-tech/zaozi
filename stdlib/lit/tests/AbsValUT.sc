// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech

// DEFINE: %{ut} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class "me.jiuyang.stdlib.AbsValUT" %s --

// The UT flow runs entirely from this lit test: the Scala entry solves the constraints and
// emits the generated SV testbench (harness + minimal clock/reset driver), then Verilator
// runs it and the run must reach the sim-dialect-generated HARNESS-DONE marker.

// width 8
// RUN: rm -rf %t.dir && mkdir -p %t.dir
// RUN: %{ut} %t.dir --width 8
// The DPI contract is derived from the DUT's IO (Drive) and Probe (Probe), not hand-written.
// RUN: FileCheck %s -check-prefix=DPI8 --input-file=%t.dir/AbsValDPI.json
// The DPI shim is generated from the contract: firtool lowers sim.func.dpi to a DPI-C import.
// RUN: firtool %t.dir/AbsValDPIShim.mlir --format=mlir | FileCheck %s -check-prefix=SHIM8
// RUN: cd %t.dir && verilator --binary --timing --assert --top-module ut_top -Wno-fatal generated.sv ut_top.sv -o simtb
// The sim-dialect print lowers to $fwrite on stderr, so fold it into stdout for FileCheck.
// RUN: %t.dir/obj_dir/simtb 2>&1 | FileCheck %s -check-prefix=RUN8
// RUN: rm -rf %t.dir

// RUN8: HARNESS-DONE
// RUN8-NOT: HARNESS-TIMEOUT

// DPI8:      "dut": "AbsVal_width8"
// DPI8:      "name": "A"
// DPI8-NEXT: "role": "Drive"
// DPI8:      "name": "absval"
// DPI8-NEXT: "role": "Probe"

// SHIM8: import "DPI-C"
// SHIM8-SAME: AbsVal_width8_tick
// SHIM8: AbsVal_width8_tick(
