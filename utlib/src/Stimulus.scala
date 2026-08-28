// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.utlib

/** One beat of abstract stimulus: each ABI drive port's value for one cycle. */
final case class Beat(values: Map[String, BigInt])

/** The model→stimulus interface: a solved witness expressed purely in ABI terms — per-cycle beats over the spec's
  * drive ports, no solver naming, no backend encoding. [[fromTrace]] is the only crossing from solver land (a trace
  * already rebound to ABI names by [[TraceBinding]]); a [[StimulusCodec]] is the only crossing to a concrete replay
  * backend. Everything about "what the stimulus IS" lives here; everything about "how a backend spells it" lives in a
  * codec.
  */
final case class AbstractStimulus(spec: AbiSpec, beats: Vector[Beat]):
  def cycles: Int = beats.length

  /** One drive port's value stream. */
  def drive(name: String): Vector[BigInt] =
    require(spec.drive.exists(_.name == name), s"'$name' is not a drive port of ${spec.dut}")
    beats.map(_.values(name))

object AbstractStimulus:
  /** Cross the interface: extract the ABI drive columns from an ABI-named trace. The trace must already be rebound
    * ([[TraceBinding.rebind]]) — lookup here is exact, and a miss names what the trace does hold.
    */
  def fromTrace(trace: Trace, spec: AbiSpec): AbstractStimulus =
    val columns = spec.drive.map { port =>
      val values = trace.values.getOrElse(
        port.name,
        throw IllegalArgumentException(
          s"trace has no signal for drive port '${port.name}' (signals: ${trace.values.keys.mkString(", ")})"
        )
      )
      port.name -> values
    }
    val beats   = Vector.tabulate(trace.cycles)(t => Beat(columns.map((n, vs) => n -> vs(t)).toMap))
    AbstractStimulus(spec, beats)

/** A concrete stimulus encoding some replay backend understands. */
trait StimulusCodec:
  def write(stimulus: AbstractStimulus, path: os.Path): os.Path

/** The Model B testbench's encoding: exactly one drive port, one value per line per cycle, in the representation the
  * tick callback's `%ld` read expects — two's-complement decimal for a signed port, the raw value for an unsigned
  * one.
  */
object ModelBStimulus extends StimulusCodec:
  def render(stimulus: AbstractStimulus): String =
    val drive = stimulus.spec.drive match
      case Seq(one) => one
      case other    =>
        throw IllegalArgumentException(s"${stimulus.spec.dut}: Model B needs exactly one drive port, got $other")
    stimulus.drive(drive.name).map(v => s"${asSigned(v, drive)}\n").mkString

  def write(stimulus: AbstractStimulus, path: os.Path): os.Path =
    os.makeDir.all(path / os.up)
    os.write.over(path, render(stimulus))
    path

  /** A trace value (unsigned, as parsed from hex) in the callback's decimal representation. */
  private def asSigned(value: BigInt, port: AbiPort): BigInt =
    if port.signed && value.testBit(port.width - 1) then value - (BigInt(1) << port.width) else value

/** The default pipeline, composed: ABI-named trace → [[AbstractStimulus]] → Model B `stimulus.txt`. */
object Stimulus:
  def fromTrace(trace: Trace, spec: AbiSpec): String =
    ModelBStimulus.render(AbstractStimulus.fromTrace(trace, spec))

  def save(trace: Trace, spec: AbiSpec, path: os.Path): os.Path =
    ModelBStimulus.write(AbstractStimulus.fromTrace(trace, spec), path)
