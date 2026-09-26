// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class me.jiuyang.stdlib.prcm.PRCM %s --
// RUN: rm -rf %t.dir && mkdir -p %t.dir
// RUN: %{test} config %t.dir/prcm.json --indexWidth 3 --dataWidth 128 --coldResetStages 3 --domains '{"name":"core","modes":[{"name":"off","code":"3","target":"Off"},{"name":"run","code":"1099511627781","target":"Run"}],"resetMode":"off","resetStages":2}'
// RUN: %{test} prove-model %t.dir/prcm.json %t.dir/proof 10000
// RUN: FileCheck %s --input-file=%t.dir/proof/report.json
// RUN: python3 -c 'import json, pathlib, subprocess, sys; root = pathlib.Path(sys.argv[1]); report = json.loads((root / "report.json").read_text()); assert all(subprocess.check_output(["z3", str(root / check["query"])], text=True).strip().lower() == check["result"].lower() for check in report["checks"])' %t.dir/proof
// RUN: cd %t.dir && %{test} design %t.dir/prcm.json
// RUN: firld --base-circuit=$(basename %t.dir/PRCM_*.mlirbc .mlirbc) %t.dir/*.mlirbc -o %t.dir/prcm.mlir
// RUN: %{test} prove %t.dir/prcm.json %t.dir/prcm.mlir %t.dir/assembly-proof 30000
// RUN: FileCheck %s --check-prefix=ASSEMBLY --input-file=%t.dir/assembly-proof/report.json
// RUN: python3 -c 'import json, pathlib, subprocess, sys; root = pathlib.Path(sys.argv[1]); report = json.loads((root / "report.json").read_text()); assert all(subprocess.check_output(["z3", str(root / check["query"])], text=True).strip().lower() == check["result"].lower() for check in report["checks"])' %t.dir/assembly-proof
// RUN: mkdir -p %t.dir/off
// RUN: python3 -c 'import json, pathlib, sys; p = json.loads(pathlib.Path(sys.argv[1]).read_text()); p["domains"][0]["modes"] = p["domains"][0]["modes"][:1]; p["dataWidth"] = 8; p["coldResetStages"] = 2; pathlib.Path(sys.argv[2]).write_text(json.dumps(p))' %t.dir/prcm.json %t.dir/off/prcm.json
// RUN: cd %t.dir/off && %{test} design %t.dir/off/prcm.json
// RUN: firld --base-circuit=$(basename %t.dir/off/PRCM_*.mlirbc .mlirbc) %t.dir/off/*.mlirbc -o %t.dir/off/prcm.mlir
// RUN: %{test} prove %t.dir/off/prcm.json %t.dir/off/prcm.mlir %t.dir/off/proof 30000
// RUN: FileCheck %s --check-prefix=ASSEMBLY --input-file=%t.dir/off/proof/report.json
// RUN: rm -rf %t.dir

// CHECK: "scope": "configuration, action models, and combinational control circuits"
// CHECK: "passed": true
// CHECK: "progressFailures": []

// ASSEMBLY: "scope": "PRCM models, assembly state bindings, and receiver progress"
// ASSEMBLY: "passed": true
// ASSEMBLY: "progressFailures": []
// ASSEMBLY: "recoveryFailures": []
// ASSEMBLY: "releaseFailures": []
