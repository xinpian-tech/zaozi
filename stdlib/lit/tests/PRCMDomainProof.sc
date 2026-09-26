// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

// DEFINE: %{runner} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS
// DEFINE: %{domain} = %{runner} --main-class me.jiuyang.stdlib.prcm.PRCMDomain %s --
// DEFINE: %{proof} = %{runner} --main-class me.jiuyang.stdlib.prcm.PRCM %s --
// RUN: rm -rf %t.dir && mkdir -p %t.dir
// RUN: %{domain} config %t.dir/domain.json --resetStages 3
// RUN: cd %t.dir && %{domain} design %t.dir/domain.json
// RUN: firld --base-circuit=PRCMDomain_resetStages3 %t.dir/*.mlirbc -o %t.dir/domain.mlir
// RUN: %{proof} prove-domain %t.dir/domain.json %t.dir/domain.mlir %t.dir/proof 30000
// RUN: FileCheck %s --input-file=%t.dir/proof/report.json
// RUN: python3 -c 'import json, pathlib, subprocess, sys; root = pathlib.Path(sys.argv[1]); report = json.loads((root / "report.json").read_text()); assert all(subprocess.check_output(["z3", str(root / check["query"])], text=True).strip().lower() == check["result"].lower() for check in report["checks"])' %t.dir/proof
// RUN: rm -rf %t.dir

// CHECK: "scope": "domain state bindings and normal reset-feedback refinement"
// CHECK: "passed": true
// CHECK: "progressFailure": null
