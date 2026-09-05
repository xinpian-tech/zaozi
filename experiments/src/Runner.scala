// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>

/** The fixed entry the harness runs: invoke the generated experiment, write its JSON report to
  * `<outDir>/report.json` (the file `ut_harness.py` reads), and echo it on a marked stdout line for humans.
  */
@main def utRun(outDirPath: String): Unit =
  val outDir = os.Path(outDirPath, os.pwd)
  val report = Generated.run(outDir)
  os.write.over(outDir / "report.json", ujson.write(report))
  println("UTCLI-RESULT " + ujson.write(report))
