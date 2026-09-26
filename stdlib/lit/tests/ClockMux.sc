// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class me.jiuyang.stdlib.clock.ClockMux %s --
// RUN: rm -rf %t.dir && mkdir -p %t.dir
// RUN: %{test} config %t.dir/mux.json --inputs 3 --stages 2 --clockDuringReset false
// RUN: cd %t.dir && %{test} design %t.dir/mux.json
// RUN: firld --base-circuit=ClockMux_308fd8b9 %t.dir/*.mlirbc -o %t.dir/mux.mlir
// RUN: firtool %t.dir/mux.mlir --strip-debug-info --hw-pass-plugin=lower-seq-to-sv --lowering-options=disallowLocalVariables | FileCheck %s --check-prefix=RTL
// RUN: rm -rf %t.dir

// RTL: source_0_enabled:
// RTL: cover property
// RTL: source_0_reserved:
// RTL: source_0_release_pending:
// RTL: source_0_invalid_selection:
// RTL: source_1_enabled:
// RTL: source_2_enabled:
// RTL: mux_test_bypass:
// RTL-LABEL: module ClockMux_308fd8b9(
// RTL: wire unfiltered = selected_layerCapture & off_0 & off_1;
// RTL: filter <= unfiltered;
// RTL: filter_0 <= filter;
// RTL: stages <= filter & filter_0 & unfiltered;
// RTL: stages_0 <= stages;
// RTL: off <= ~(unfiltered | stages_0 | filter | filter_0 | stages);
// RTL: wire unfiltered_0 = selected_layerCapture_0 & off & off_1;
// RTL: wire unfiltered_1 = selected_layerCapture_1 & off & off_0;
