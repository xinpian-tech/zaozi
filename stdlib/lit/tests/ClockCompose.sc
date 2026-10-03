// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class me.jiuyang.stdlib.clock.ClockTree %s --
// DEFINE: %{nand} = --cells '{"name":"NAND2","inputs":["A","B"],"output":"Y","function":[{"A":0,"Y":1},{"B":0,"Y":1},{"A":1,"B":1,"Y":0}]}'
// DEFINE: %{icg} = --cells '{"name":"ICG","sequential":{"type":"GatePositive","clock":"CK","enable":"E","output":"Q"}}'
// DEFINE: %{tree} = --inputs a --targets '{"name":"out","links":[{"source":"a","path":{"divider":{"width":2,"initial":"2","clockDuringReset":false,"automatic":true}}}]}'
// RUN: rm -rf %t.dir && mkdir -p %t.dir/composed %t.dir/shuffled %t.dir/changed
// RUN: %{test} config %t.dir/composed/config.json %{tree} %{nand} %{icg}
// RUN: FileCheck %s --check-prefix=CONFIG --input-file=%t.dir/composed/config.json
// RUN: %{test} config %t.dir/shuffled/config.json %{tree} %{icg} %{nand}
// RUN: diff %t.dir/composed/config.json %t.dir/shuffled/config.json
// RUN: cd %t.dir/composed && %{test} design %t.dir/composed/config.json
// RUN: firld --base-circuit=$(basename %t.dir/composed/ClockTree_????????.mlirbc .mlirbc) %t.dir/composed/*.mlirbc -o %t.dir/composed/tree.mlir
// RUN: firtool %t.dir/composed/tree.mlir --strip-debug-info --split-verilog -o %t.dir/composed/rtl
// RUN: FileCheck %s --check-prefix=CELLS --input-file=$(grep -l "NAND2 gate4" %t.dir/composed/rtl/*.sv)
// RUN: python3 -c 'import json, sys; p = json.load(open(sys.argv[1])); g = p["composed"][1]["gates"][0]["pins"]; g.update({k: {"a": "b", "b": "a"}.get(v, v) for k, v in g.items()}); json.dump(p, open(sys.argv[2], "w"))' %t.dir/composed/config.json %t.dir/changed/config.json
// RUN: cd %t.dir/changed && not %{test} design %t.dir/changed/config.json 2>&1 | FileCheck %s --check-prefix=CHANGED
// RUN: not %{test} config %t.dir/changed/missing.json %{tree} %{icg} --cells '{"name":"INV","inputs":["A"],"output":"Y","function":[{"Y":"!A"}]}' 2>&1 | FileCheck %s --check-prefix=MISSING
// RUN: rm -rf %t.dir

// CONFIG: "cells":[{"name":"ICG"
// CONFIG-SAME: {"name":"NAND2"
// CONFIG-SAME: "composed":[{"role":"Mux","depth":3,"gates":[
// CONFIG-SAME: {"role":"Xor","depth":3,"gates":[{"cell":"NAND2","pins":{"A":"1","B":"a"}},{"cell":"NAND2","pins":{"A":"1","B":"b"}},{"cell":"NAND2","pins":{"A":"a","B":"g1"}},{"cell":"NAND2","pins":{"A":"b","B":"g0"}},{"cell":"NAND2","pins":{"A":"g2","B":"g3"}}]}]

// CELLS:      NAND2 gate0 (
// CELLS-NEXT:   .A (1'h1),
// CELLS-NEXT:   .B (a),
// CELLS-NEXT:   .Y (_gate0_Y)
// CELLS:      NAND2 gate4 (
// CELLS-NEXT:   .A (_gate2_Y),
// CELLS-NEXT:   .B (_gate3_Y),
// CELLS-NEXT:   .Y (outClock)

// CHANGED: composed clock role Xor: it does not compute the role function; the cells changed since config, rerun config

// MISSING: clock role Mux cannot be built from the declared cells: the declared cells cannot express its function
