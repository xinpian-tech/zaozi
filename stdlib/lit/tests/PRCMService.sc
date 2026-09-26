// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

// DEFINE: %{test} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class me.jiuyang.stdlib.prcm.PRCM %s --
// RUN: rm -rf %t.dir && mkdir -p %t.dir/shared %t.dir/multiple

// RUN: %{test} config %t.dir/shared/prcm.json --indexWidth 4 --dataWidth 32 --coldResetStages 2 --domains '{"name":"a","modes":[{"name":"off","code":"0","target":"Off","services":[]},{"name":"reset","code":"1","target":"Reset","services":[]},{"name":"run","code":"2","target":"Run","services":["x","alias"]}],"resetMode":"off","resetStages":2}' --domains '{"name":"b","modes":[{"name":"off","code":"0","target":"Off","services":[]},{"name":"reset","code":"1","target":"Reset","services":[]},{"name":"run","code":"2","target":"Run","services":["x"]}],"resetMode":"off","resetStages":2}' --domains '{"name":"p","modes":[{"name":"off","code":"0","target":"Off","services":[]},{"name":"reset","code":"1","target":"Reset","services":[]},{"name":"run","code":"2","target":"Run","services":[]}],"resetMode":"off","resetStages":2}' --services '{"name":"x","provider":"p"}' --services '{"name":"alias","provider":"p"}'
// RUN: cd %t.dir/shared && %{test} design %t.dir/shared/prcm.json
// RUN: %{test} bindings %t.dir/shared/prcm.json | FileCheck %s --check-prefix=SHARED-BINDINGS
// RUN: firld --base-circuit=PRCM_a0cbec73 %t.dir/shared/*.mlirbc -o %t.dir/shared/prcm.mlir
// RUN: %{test} prove %t.dir/shared/prcm.json %t.dir/shared/prcm.mlir %t.dir/shared/proof 30000
// RUN: firtool %t.dir/shared/prcm.mlir --strip-debug-info --hw-pass-plugin=lower-seq-to-sv | FileCheck %s --check-prefix=SHARED
// RUN: %{test} config %t.dir/multiple/prcm.json --indexWidth 4 --dataWidth 32 --coldResetStages 2 --domains '{"name":"a","modes":[{"name":"off","code":"0","target":"Off","services":[]},{"name":"reset","code":"1","target":"Reset","services":[]},{"name":"run","code":"2","target":"Run","services":["x","y"]}],"resetMode":"off","resetStages":2}' --domains '{"name":"p","modes":[{"name":"off","code":"0","target":"Off","services":[]},{"name":"reset","code":"1","target":"Reset","services":[]},{"name":"run","code":"2","target":"Run","services":[]}],"resetMode":"off","resetStages":2}' --domains '{"name":"q","modes":[{"name":"off","code":"0","target":"Off","services":[]},{"name":"reset","code":"1","target":"Reset","services":[]},{"name":"run","code":"2","target":"Run","services":[]}],"resetMode":"off","resetStages":2}' --services '{"name":"x","provider":"p"}' --services '{"name":"y","provider":"q"}'
// RUN: cd %t.dir/multiple && %{test} design %t.dir/multiple/prcm.json
// RUN: %{test} bindings %t.dir/multiple/prcm.json | FileCheck %s --check-prefix=MULTIPLE-BINDINGS
// RUN: firld --base-circuit=PRCM_9f899b78 %t.dir/multiple/*.mlirbc -o %t.dir/multiple/prcm.mlir
// RUN: %{test} prove %t.dir/multiple/prcm.json %t.dir/multiple/prcm.mlir %t.dir/multiple/proof 30000
// RUN: firtool %t.dir/multiple/prcm.mlir --strip-debug-info --hw-pass-plugin=lower-seq-to-sv | FileCheck %s --check-prefix=MULTIPLE
// RUN: rm -rf %t.dir

// SHARED-BINDINGS: {"domains":[{"name":"a","port":0},{"name":"b","port":1},{"name":"p","port":2}],"services":[{"consumer":"a","provider":"p","names":["alias","x"]},{"consumer":"b","provider":"p","names":["x"]}]}
// MULTIPLE-BINDINGS: {"domains":[{"name":"a","port":0},{"name":"p","port":1},{"name":"q","port":2}],"services":[{"consumer":"a","provider":"p","names":["x"]},{"consumer":"a","provider":"q","names":["y"]}]}

// SHARED: pending_request_is_held:
// SHARED: service_is_held_until_released:
// SHARED: return_waits_for_grant_low:
// SHARED: pending_need_withdrawn:
// SHARED: consumer_draining:
// SHARED: held_service_failed:
// SHARED: service_returned:
// SHARED: domain_0_service_loss_recorded:
// SHARED: domain_0_waiting_for_service:
// SHARED: domain_1_service_loss_recorded:
// SHARED: domain_1_waiting_for_service:
// SHARED: domain_2_providing_service:
// SHARED-LABEL: module PRCM_a0cbec73(
// SHARED: PRCMServiceHandshake service_0_2 (
// SHARED: PRCMServiceHandshake service_1_2 (
// SHARED-NOT: PRCMServiceHandshake service_

// MULTIPLE: domain_0_service_loss_recorded:
// MULTIPLE: domain_0_waiting_for_service:
// MULTIPLE: domain_1_providing_service:
// MULTIPLE: domain_2_providing_service:
// MULTIPLE-LABEL: module PRCM_9f899b78(
// MULTIPLE: PRCMServiceHandshake service_0_1 (
// MULTIPLE: PRCMServiceHandshake service_0_2 (
// MULTIPLE-NOT: PRCMServiceHandshake service_
