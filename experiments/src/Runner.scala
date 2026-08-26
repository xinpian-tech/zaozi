// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>

/** The fixed entry the harness runs: invoke the generated experiment and print its JSON report on a marked line —
  * the only stdout line `ut_harness.py` parses.
  */
@main def utRun(outDirPath: String): Unit =
  val report = Generated.run(os.Path(outDirPath, os.pwd))
  println("UTCLI-RESULT " + ujson.write(report))
