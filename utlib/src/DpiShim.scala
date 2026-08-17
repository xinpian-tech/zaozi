// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 xinpian-tech
package me.jiuyang.utlib

/** Generate the DPI shim for a [[DPISpec]]: the software-backend binding of the contract.
  *
  * The shim is a `sim.func.dpi` declaration plus a clocked `sim.func.dpi.call`, which firtool
  * lowers to a SystemVerilog `import "DPI-C"` and a per-cycle call. The DUT's Probe points are
  * the DPI *inputs* (the simulator hands the observed values to the external frontend) and its
  * Drive ports are the DPI *outputs* (the frontend returns the values to feed the DUT). An
  * external Rust/Python frontend implements this one C-ABI function; the sim-dialect emission
  * here is only the software backend's binding of the shared contract.
  */
object DpiShim:
  def funcName(spec:   DPISpec): String = s"${spec.dut}_tick"
  def moduleName(spec: DPISpec): String = s"${spec.dut}_dpi"

  /** The shim as HW-dialect MLIR text (with the `sim.func.dpi` declaration), ready for
    * `firtool` to lower to SystemVerilog.
    */
  def mlirString(spec: DPISpec): String =
    val probes = spec.probe // DPI inputs: observed and handed out to the frontend
    val drives = spec.drive // DPI outputs: returned by the frontend to feed the DUT
    val fn     = funcName(spec)
    val mod    = moduleName(spec)

    val funcParams =
      probes.map(p => s"in %${p.name} : i${p.width}") ++ drives.map(p => s"out ${p.name} : i${p.width}")
    val modPorts   =
      Seq("in %clk : !seq.clock") ++
        probes.map(p => s"in %${p.name} : i${p.width}") ++
        drives.map(p => s"out ${p.name} : i${p.width}")

    val callArgs  = probes.map(p => s"%${p.name}").mkString(", ")
    val inTypes   = probes.map(p => s"i${p.width}").mkString(", ")
    val outTypes  = drives.map(p => s"i${p.width}").mkString(", ")
    val results   = if drives.isEmpty then "" else if drives.size == 1 then "%r = " else s"%r:${drives.size} = "
    val call      = s"    ${results}sim.func.dpi.call @$fn($callArgs) clock %clk : ($inTypes) -> ($outTypes)"
    val outputRef = drives.indices
      .map(i => if drives.size == 1 then "%r" else s"%r#$i")
      .mkString(", ")

    s"""|module {
        |  sim.func.dpi @$fn(${funcParams.mkString(", ")})
        |  hw.module @$mod(${modPorts.mkString(", ")}) {
        |$call
        |    hw.output $outputRef : $outTypes
        |  }
        |}
        |""".stripMargin
