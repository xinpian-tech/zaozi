// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>

// DEFINE: %{testbench} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class SyncQueueUTLit %s --
// DEFINE: %{dut} = scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS --main-class me.jiuyang.stdlib.queue.default.SyncQueue --

// RUN: rm -rf %t.dir && mkdir -p %t.dir/dut
// RUN: %{testbench} %S/../../ut/src/sync_queue/parameter.json %t.dir
// RUN: cd %t.dir/dut && %{dut} design %S/../../ut/src/sync_queue/parameter.json
// RUN: FileCheck %s --check-prefix=SV --input-file=%t.dir/testbench.sv
// RUN: python3 %S/../../../utlib/scripts/ut.py --testbench %t.dir/testbench.sv --schema %t.dir/interface.json --driver %S/../../ut/src/sync_queue/driver.py --stimulus %S/../../ut/src/sync_queue/stimulus.json --timeout 300
// RUN: test -s %t.dir/trace.vcd
// RUN: rm -rf %t.dir

// SV: import "DPI-C"
// SV-LABEL: module SyncQueueUT
// SV: SyncQueue_width8_depth3_almostEmptyLevel1_almostFullLevel1_stickyErrorfalse_enableDiagnosticsfalse_asyncResetfalse_resetMemtrue dut (

import me.jiuyang.stdlib.queue.default.{SyncQueueParameter, given}
import me.jiuyang.stdlib.ut.SyncQueueTestBench

object SyncQueueUTLit:
  def main(args: Array[String]): Unit =
    val parameter = upickle.default.read[SyncQueueParameter](os.read(os.Path(args(0), os.pwd)))
    SyncQueueTestBench.lower(parameter).writeTo(os.Path(args(1), os.pwd))
