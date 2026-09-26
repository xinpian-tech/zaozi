// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class me.jiuyang.stdlib.prcm.PRCM %s --
// RUN: rm -rf %t.dir && mkdir -p %t.dir
// RUN: %{test} config %t.dir/prcm.json --indexWidth 4 --dataWidth 32 --coldResetStages 2 --domains '{"name":"a","modes":[{"name":"off","code":"0","target":"Off"},{"name":"reset","code":"1","target":"Reset"},{"name":"run","code":"2","target":"Run","services":["x"]}],"resetMode":"off","resetStages":2}' --domains '{"name":"p","modes":[{"name":"off","code":"0","target":"Off"},{"name":"reset","code":"1","target":"Reset"},{"name":"run","code":"2","target":"Run"}],"resetMode":"off","resetStages":2}' --services '{"name":"x","provider":"p"}' --chip '{"modes":[{"name":"local","code":"0","domains":{"a":"Local","p":"Local"}},{"name":"sleep","code":"1","domains":{"a":{"$type":"Fixed","mode":"off"},"p":{"$type":"Fixed","mode":"off"}}},{"name":"active","code":"258","domains":{"a":{"$type":"Fixed","mode":"run"},"p":"Local"}},{"name":"held","code":"769","domains":{"a":{"$type":"Fixed","mode":"reset"},"p":{"$type":"Fixed","mode":"off"}}}],"resetMode":"local"}'
// RUN: cd %t.dir && %{test} design %t.dir/prcm.json
// RUN: %{test} header %t.dir/prcm.json | FileCheck %s --check-prefix=HEADER
// RUN: %{test} bindings %t.dir/prcm.json | FileCheck %s --check-prefix=WITNESS
// RUN: firld --base-circuit=PRCM_dc45977f %t.dir/*.mlirbc -o %t.dir/prcm.mlir
// RUN: %{test} prove %t.dir/prcm.json %t.dir/prcm.mlir %t.dir/proof 30000
// RUN: firtool %t.dir/prcm.mlir --strip-debug-info --hw-pass-plugin=lower-seq-to-sv | FileCheck %s --check-prefix=RTL
// RUN: mkdir -p %t.dir/local
// RUN: python3 -c 'import json, pathlib, sys; p = json.loads(pathlib.Path(sys.argv[1]).read_text()); [m.update(domains={name: "Local" for name in m["domains"]}) for m in p["chip"]["modes"]]; pathlib.Path(sys.argv[2]).write_text(json.dumps(p))' %t.dir/prcm.json %t.dir/local/prcm.json
// RUN: cd %t.dir/local && %{test} design %t.dir/local/prcm.json
// RUN: firld --base-circuit=$(basename %t.dir/local/PRCM_*.mlirbc .mlirbc) %t.dir/local/*.mlirbc -o %t.dir/local/prcm.mlir
// RUN: %{test} prove %t.dir/local/prcm.json %t.dir/local/prcm.mlir %t.dir/local/proof 30000
// RUN: rm -rf %t.dir

// HEADER: #define PRCM_CHIP_REQUEST_OFFSET 0x0ULL
// HEADER: #define PRCM_CHIP_STATUS_OFFSET 0x4ULL
// HEADER: #define PRCM_CHIP_REQUEST_MASK 0x3ffULL
// HEADER: #define PRCM_CHIP_REQUEST_RESET 0x0ULL
// HEADER: #define PRCM_CHIP_STATUS_DONE_BIT 0xaULL
// HEADER: #define PRCM_CHIP_STATUS_INVALID_BIT 0xbULL
// HEADER: #define PRCM_CHIP_MODE_active 0x102ULL
// HEADER: #define PRCM_CHIP_MODE_held 0x301ULL
// HEADER: #define PRCM_DOMAIN_a_REQUEST_OFFSET 0x8ULL
// HEADER: #define PRCM_DOMAIN_a_STATUS_BLOCKED_BY_CHIP_BIT 0x5ULL
// HEADER: #define PRCM_DOMAIN_a_STATUS_WAIT_SERVICE_BIT 0x6ULL
// HEADER: #define PRCM_DOMAIN_p_REQUEST_OFFSET 0x14ULL
// HEADER: #define PRCM_DOMAIN_p_STATUS_IN_USE_BIT 0x6ULL

// WITNESS: {"mode":"active","witness":{"a":"Run","p":"Run"}}
// WITNESS-SAME: {"mode":"held","witness":{"a":"Reset","p":"Off"}}

// RTL: domain_0_chip_blocks_local_completion:
// RTL: assert property
// RTL: domain_0_blocked_by_chip:
// RTL: cover property
// RTL: domain_1_chip_blocks_local_completion:
// RTL: assert property
// RTL: domain_1_blocked_by_chip:
// RTL: cover property
// RTL: chip_invalid_mode:
// RTL: cover property
// RTL: chip_policy_complete:
// RTL: cover property
// RTL: regmap_chip_request_0_write:
// RTL: regmap_chip_request_1_write:
// RTL: regmap_chip_status_read:

// RTL-LABEL: module PRCM_dc45977f(
// RTL: wire {{ *}}blockedByChip_layerCapture = [[BASE:[a-zA-Z_0-9]+]] != [[LOCAL:[a-zA-Z_0-9]+]];
// RTL: wire {{ *}}done_layerCapture =
// RTL: ) & [[BASE]] == [[LOCAL]];
// RTL: wire {{ *}}blockedByChip_layerCapture_0 = [[BASEP:[a-zA-Z_0-9]+]] != [[LOCALP:[a-zA-Z_0-9]+]];
// RTL: wire {{ *}}done_layerCapture_0 =
// RTL: ) & [[BASEP]] == [[LOCALP]];
