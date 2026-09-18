// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>

/** The fixed entry the harness runs: invoke the generated experiment, write its JSON report to
  * `<outDir>/report.json` (the file `ut_harness.py` reads), and echo it on a marked stdout line for humans.
  */
@main def utRun(outDirPath: String): Unit =
  val outDir = os.Path(outDirPath, os.pwd)
  val experiment = Class.forName("Generated$").getField("MODULE$").get(null)
    .asInstanceOf[me.jiuyang.utlib.UTExperiment]
  os.makeDir.all(outDir)
  try
    val report = experiment.run(outDir)
    os.write.over(outDir / "report.json", ujson.write(report))
    println("UTCLI-RESULT " + ujson.write(report))
  catch
    case error: me.jiuyang.utlib.LtlArgumentException =>
      val diagnostic = ujson.Obj("schema" -> "ltl-argument-v1", "code" -> error.code,
        "file" -> error.file, "line" -> error.line, "col" -> 1, "message" -> error.getMessage)
      os.write.over(outDir / "ltl-error.json", ujson.write(diagnostic))
      System.err.println(error.getMessage)
      sys.exit(2)
