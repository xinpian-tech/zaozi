// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

// DEFINE: %{runner} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS
// DEFINE: %{domain} = %{runner} --main-class me.jiuyang.stdlib.prcm.PRCMDomain %s --
// DEFINE: %{proof} = %{runner} --main-class me.jiuyang.stdlib.prcm.PRCM %s --
// RUN: rm -rf %t.dir && mkdir -p %t.dir && cd %t.dir
// RUN: %{domain} config %t.dir/domain.json --resetStages 3
// RUN: %{proof} prove-domain %t.dir/domain.json %t.dir/proof
// RUN: FileCheck %s --input-file=%t.dir/proof/report.json
// RUN: ls %t.dir/proof/*.smt2 | wc -l | FileCheck %s --check-prefix=COUNT
// RUN: for query in %t.dir/proof/*.smt2; do z3 $query | FileCheck $query --check-prefix=EXPECT || exit 1; done
// RUN: rm -rf %t.dir

// CHECK: "scope": "domain state bindings and normal reset-feedback refinement"
// CHECK: "progressFailure": null

// COUNT: 6
