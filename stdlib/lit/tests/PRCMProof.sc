// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class me.jiuyang.stdlib.prcm.PRCM %s --
// RUN: rm -rf %t.dir && mkdir -p %t.dir
// RUN: %{test} config %t.dir/prcm.json --indexWidth 3 --dataWidth 128 --coldResetStages 3 --domains '{"name":"core","modes":[{"name":"off","code":"3","target":"Off"},{"name":"run","code":"1099511627781","target":"Run"}],"resetMode":"off","resetStages":2}'
// RUN: %{test} prove-model %t.dir/prcm.json %t.dir/proof
// RUN: FileCheck %s --input-file=%t.dir/proof/report.json
// RUN: %{test} prove %t.dir/prcm.json %t.dir/assembly-proof
// RUN: FileCheck %s --check-prefix=ASSEMBLY --input-file=%t.dir/assembly-proof/report.json
// RUN: mkdir -p %t.dir/off
// RUN: python3 -c 'import json, pathlib, sys; p = json.loads(pathlib.Path(sys.argv[1]).read_text()); p["domains"][0]["modes"] = p["domains"][0]["modes"][:1]; p["dataWidth"] = 8; p["coldResetStages"] = 2; pathlib.Path(sys.argv[2]).write_text(json.dumps(p))' %t.dir/prcm.json %t.dir/off/prcm.json
// RUN: %{test} prove %t.dir/off/prcm.json %t.dir/off/proof
// RUN: FileCheck %s --check-prefix=ASSEMBLY --input-file=%t.dir/off/proof/report.json
// RUN: for query in $(find %t.dir -name '*.smt2'); do z3 $query | FileCheck $query --check-prefix=EXPECT || exit 1; done
// RUN: rm -rf %t.dir

// CHECK: "scope": "action models and combinational control circuits"
// CHECK: "progressFailures": []

// ASSEMBLY: "scope": "PRCM models, assembly state bindings, and receiver progress"
// ASSEMBLY: "progressFailures": []
// ASSEMBLY: "recoveryFailures": []
// ASSEMBLY: "releaseFailures": []
