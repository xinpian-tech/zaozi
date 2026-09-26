// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class me.jiuyang.stdlib.prcm.PRCM %s --
// RUN: rm -rf %t.dir && mkdir -p %t.dir
// RUN: %{test} config %t.dir/prcm.json --indexWidth 4 --dataWidth 32 --coldResetStages 2 --domains '{"name":"core","modes":[{"name":"off","code":"0","target":"Off"},{"name":"reset","code":"258","target":"Reset"},{"name":"run","code":"769","target":"Run"}],"resetMode":"off","resetStages":2}'
// RUN: cd %t.dir && %{test} design %t.dir/prcm.json
// RUN: %{test} header %t.dir/prcm.json | FileCheck %s --check-prefix=HEADER
// RUN: %{test} bindings %t.dir/prcm.json | FileCheck %s --check-prefix=BINDINGS
// RUN: firld --base-circuit=PRCM_48a4fefc %t.dir/*.mlirbc -o %t.dir/prcm.mlir
// RUN: firtool %t.dir/prcm.mlir --strip-debug-info --hw-pass-plugin=lower-seq-to-sv | FileCheck %s --check-prefix=RTL
// RUN: rm -rf %t.dir

// HEADER: #define PRCM_APERTURE 0xcULL
// HEADER: #define PRCM_DOMAIN_core_PORT 0x0ULL
// HEADER: #define PRCM_DOMAIN_core_REQUEST_OFFSET 0x0ULL
// HEADER: #define PRCM_DOMAIN_core_STATUS_OFFSET 0x4ULL
// HEADER: #define PRCM_DOMAIN_core_EVENT_OFFSET 0x8ULL
// HEADER: #define PRCM_DOMAIN_core_REQUEST_MASK 0x3ffULL
// HEADER: #define PRCM_DOMAIN_core_REQUEST_RESET 0x0ULL
// HEADER: #define PRCM_DOMAIN_core_STATUS_DONE_BIT 0xaULL
// HEADER: #define PRCM_DOMAIN_core_STATUS_INVALID_BIT 0xbULL
// HEADER: #define PRCM_DOMAIN_core_STATUS_FAULT_BIT 0xcULL
// HEADER: #define PRCM_DOMAIN_core_EVENT_POWER_LOST_BIT 0x0ULL
// HEADER: #define PRCM_DOMAIN_core_MODE_off 0x0ULL
// HEADER: #define PRCM_DOMAIN_core_MODE_reset 0x102ULL
// HEADER: #define PRCM_DOMAIN_core_MODE_run 0x301ULL

// BINDINGS: {"domains":[{"name":"core","port":0}],"services":[]}

// RTL: domain_0_power_loss_recorded:
// RTL: domain_0_invalid_mode:
// RTL: domain_0_set_wins_clear:
// RTL: regmap_response_valid_held:
// RTL: regmap_response_data_stable:
// RTL: management_reset:
// RTL-LABEL: module PRCM_48a4fefc(
// RTL: SynchronizedReset_stages2_activeLow cold (
// RTL: .reset{{ +}}(coldResetN)
// RTL: .synchronizedReset ([[COLD:[a-zA-Z_0-9]+]])
// RTL: PRCMDomain_resetStages2 {{[a-zA-Z_0-9]+}} (
// RTL: .clock{{ +}}(clock)
// RTL: .coldResetN{{ +}}([[COLD]])
// RTL: .powerRequest{{ +}}(domains_0_powerRequest)
// RTL: .domainClock{{ +}}(domains_0_clock)
// RTL: .domainResetN{{ +}}(domains_0_resetN)
