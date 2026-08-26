// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.utlib

/** Bridges a [[FormalUT]] witness to the Model B testbench: a [[Trace]] (per-cycle values of the DUT's inputs, as
  * circt-bmc prints them) becomes the `stimulus.txt` the generated tick callback replays — one drive value per line
  * per cycle, in the representation the callback's `%ld` read expects: two's-complement decimal for a signed port, the
  * raw value for an unsigned one.
  */
object Stimulus:

  /** Render `trace` as `stimulus.txt` content for the ABI's single drive port. */
  def fromTrace(trace: Trace, spec: AbiSpec): String =
    val drive = spec.drive match
      case Seq(one) => one
      case other    => throw IllegalArgumentException(s"${spec.dut}: Model B needs exactly one drive port, got $other")
    val values = lookup(trace, drive.name)
    values.map(render(_, drive)).map(_.toString + "\n").mkString

  /** Write [[fromTrace]] to `path`, creating parent directories. */
  def save(trace: Trace, spec: AbiSpec, path: os.Path): os.Path =
    os.makeDir.all(path / os.up)
    os.write.over(path, fromTrace(trace, spec))
    path

  /** The drive signal's per-cycle values: exact name first, else the `<name>__d_next` a delayed drive is traced as
    * (see `FormalUT.delayInputs` — the register's next value IS the stimulus at the same row), else a unique
    * case-insensitive match (circt-bmc prints the lowered port name, which can differ in case from the ABI's).
    */
  private def lookup(trace: Trace, name: String): Vector[BigInt] =
    val caseInsensitive = trace.values.collect { case (k, v) if k.equalsIgnoreCase(name) => v }.toSeq
    trace.values
      .get(name)
      .orElse(trace.values.get(s"${name}__d_next"))
      .orElse(Option.when(caseInsensitive.sizeIs == 1)(caseInsensitive.head))
      .getOrElse(
        throw IllegalArgumentException(
          s"trace has no signal for drive port '$name' (signals: ${trace.values.keys.mkString(", ")})"
        )
      )

  /** A trace value (unsigned, as parsed from hex) in the callback's decimal representation. */
  private def render(value: BigInt, port: AbiPort): BigInt =
    if port.signed && value.testBit(port.width - 1) then value - (BigInt(1) << port.width) else value
