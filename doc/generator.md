# Generator

## Overview

Zaozi has a significantly different API for circuit generation compared to Chisel. Instead of instantiating `Module` classes, Zaozi uses `Generator` as a module generator. Each `Generator` is a singleton object that consumes a serializable parameter to produce a module. Here, the term 'module' refers to a FIRRTL module, which has a unique identifier and does not take parameters.

For example, we can define a generator as follows:

```scala
@generator
object Passthrough extends Generator[PassthroughParameter, PassthroughLayers, PassthroughIO, PassthroughProbe]:
  def architecture(parameter: PassthroughParameter) =
    val io = summon[Interface[PassthroughIO]]
    io.o := io.i
```

In this code, we use the macro annotation `@generator` to automatically fulfill most members of the generator, including some fields and a `main` method that allows users to invoke it directly from the command line. The generator also extends the trait `Generator`, which accepts four type parameters: the generator's parameter type and the interface types of the corresponding module. The actual RTL design is implemented in the `architecture` method. This method accepts an explicit parameter (`parameter`) and several contextual parameters that users can access using Scala 3's `summon` method, such as `io` in the example above.

The overarching principle is that a generator is essentially a series of *pure functions*. They consume a parameter and produce the final module; therefore, users *SHOULD NOT* use any input outside the provided parameter.

## Parameter

In Zaozi, a parameter type is a class (typically a case class) that extends `Parameter` and can be serialized and deserialized using given `upickle.Writer` and `upickle.Reader` instances. Each Generator has a `parseParameter` method that can parse command-line arguments into the parameter type. This method requires a given `mainargs.TokensReader` instance. Additionally, the method can be overridden by the user to support cases where the parameter is difficult to construct from the command line.

## Interface

In Zaozi, three types represent the interface of a FIRRTL module: `HWInterface`, `DVInterface`, and `LayerInterface`. All of these consume a parameter of the same type as the Generator's parameter type to produce their structure. Notably, users *SHOULD NOT* pass the same interface type to different generators unless the generators have exactly identical interfaces (e.g., two generator implementations of the same module, a.k.a. `InstanceChoice`) although interface types are designed as type parameters of `Generator`. The same rule holds for parameter types.

### LayerInterface

The concept of `Layers` originates from [Chisel](https://www.chisel-lang.org/docs/explanations/layers). Unlike Chisel, however, Zaozi considers layers as a 'local interface' for a module, expressed by producing a tree of strings:

```scala
class SomeLayers(parameter: LayerSpecParameter) extends LayerInterface(parameter):
  def layers = Seq(
    Layer(
      "A0",
      Seq(
        Layer(
          "A0B0",
          Seq(
            Layer(
              "A0B0C0"
            ),
            Layer(
              "A0B0C1"
            )
          )
        ),
        Layer("A0B1"),
        Layer("A0B2")
      )
    ),
    Layer("A1")
  )
```

### HWInterface

An `HWInterface` represents the hardware interface of a module, traditionally referred to as `IO`. This trait has two abstract implementations: `HWBundle` and `HWRecord`, which are a `Bundle` and a `Record`, respectively. We can define it as follows:

```scala
class GCDIO(parameter: GCDParameter) extends HWBundle(parameter):
  val clock:  BundleField[Clock]                 = Flipped(Clock())
  val reset:  BundleField[Reset]                 = Flipped(if (parameter.useAsyncReset) AsyncReset() else Reset())
  val input:  BundleField[DecoupledIO[GCDInput]] = Flipped(Decoupled(new GCDInput(parameter)))
  val output: BundleField[ValidIO[GCDOutput]]    = Aligned(Valid(GCDOutput(parameter)))
```

### DVInterface

`DVInterface` represents references to hardware used by verification. It is similar to `HWInterface` but also accepts a `LayerInterface` type parameter to bind a probe port to a specific layer. For example:

```scala
class LayerSpecProbe(parameter: LayerSpecParameter) extends DVBundle[LayerSpecParameter, LayerSpecLayers](parameter):
  val a0     = ProbeRead(UInt(parameter.width), layers("A0"))
  val a0b0   = ProbeRead(UInt(parameter.width), layers("A0")("A0B0"))
  val a0b0c0 = ProbeRead(UInt(parameter.width), layers("A0")("A0B0")("A0B0C0"))
  val a0b1   = ProbeRead(UInt(parameter.width), layers("A0")("A0B1"))
```

## RTL Architecture

As mentioned earlier, we implement the design in the `architecture` method:

```scala
def architecture(parameter: LayerSpecParameter) =
  val io    = summon[Interface[LayerSpecIO]]
  val probe = summon[Interface[LayerSpecProbe]]
  layer("A0"):
    probe.a0 <== io.a0
    layer("A0B0"):
      probe.a0b0 <== io.a0b0
      layer("A0B0C0"):
        probe.a0b0c0 <== io.a0b0c0
    layer("A0B1"):
      probe.a0b1 <== io.a0b1
```

Note that `io` and `probe` here are of the `Referable` type `Interface`, not the original `HWInterface` and `DVInterface` types.

## Naming

The `moduleName` is also a method of the generator rather than a plain text field:

```scala
override def moduleName(parameter: PARAM): String =
  s"${this.getClass.getSimpleName.stripSuffix("$")}_${parameter.hashCode.toHexString}"
```

## Instantiation

We invoke the `instantiate` method to create a module instance. Each generator maintains a cache for modules produced using the same parameter:

```scala
val sub1 = Subtractor.instantiate(SubtractorParameter(parameter.width))
sub1.io.a := x
sub1.io.b := y
```

## Elaboration

Zaozi supports invoking any generator directly from the command line without additional code. By default, the `main` method has two subcommands: `config` and `design`.

- The `config` subcommand parses command-line arguments into a parameter and serializes it to a JSON file.
- The `design` subcommand accepts a JSON file to elaborate MLIR Bytecode.

Users can specify the output directory by setting the environment variable `ZAOZI_OUTDIR`, as shown below:

```shell
./gcd.jar config config.json --width 32 --use-async-reset false
ZAOZI_OUTDIR=result ./gcd.jar design config.json
```

Additionally, users can provide a `GeneratorApi` with `mainImpl` to support custom elaboration flows, or simply override the `main` method in a specific generator.

## Python testbench stimulus

A simulation directory contains the inputs, the user's driver and the outputs:

```text
syncqueue-verilator/
  run.py
  dpi_impl.py
  SyncQueueTestBench.json
  SyncQueueTestBench.mlirbc
  SyncQueueTestBench.sv
  wave.vcd
  .build/                     # All intermediate RTL, C API and compiler files
```

The harness writes `run.py`; the DUT supplies only the DPI implementation.
Edit `dpi_impl.py`, then run from any working directory:

```shell
python3 out/syncqueue-verilator/run.py
```

The entry point consumes the existing testbench inputs. The framework obtains
its toolchain from the repository's pinned nixpkgs and handles compilation,
Python loading, live console output and VCD capture. It does not overwrite `dpi_impl.py` or
re-elaborate the input design on this path. All intermediate files stay under
`.build/`; Python bytecode caches are disabled for the user driver. Generation,
compilation, Python prints and errors go directly to the terminal; no log files
are written.

For the SyncQueue interface, write `step()` returning the `out`, `inout` and
`return` fields named in `SyncQueueTestBench.json`:

```python
def step():
    return {
        "resetN": 1,
        "pushRequestN": 1,
        "popRequestN": 1,
        "diagnosticN": 1,
        "dataIn": 0,
        "done": 1,
        "status": 0,
    }
```

The generated testbench calls this function on falling clock edges. `in` and
`inout` parameters, when present, are passed as keyword arguments. The example
has no observation inputs, so Python supplies stimulus; DUT outputs are visible
in the VCD, without an additional result checker.

With Zaozi's Nix package on `PATH`, run these commands from a project that
has a `build.mill` and a `<module>/tb/src` testbench module:

```shell
nix shell path:/path/to/zaozi#tbh
tbh list
tbh run SyncQueue -o wave.vcd
```

The default `zaozi-assembly` package includes `bin/tbh`; the smaller `tbh`
package is also available separately. The packaged command supplies Python,
Verilator, CIRCT and the other harness tools. The project still supplies its
own Mill modules, Scala testbench source, `parameter.json` and `dpi_impl.py`.
In this repository's Nix development shell, `tbh` is also on `PATH`.
To invoke the source checkout directly, use `./tblib/tbh/tbh`.
`tbh list` uses ripgrep to list `*TestBench.scala` below the current directory.
`tbh run SyncQueue` verifies the Scala source, `parameter.json` and
`dpi_impl.py`, elaborates the design, builds the CFFI bridge and links the
Verilator executable, then checks the Python DPI implementation before
simulating and writing `out/syncqueue-verilator/coverage.txt`.
Use `tbh run SyncQueue -o coverage.txt` for a report in the current directory,
or `tbh run SyncQueue -o wave.vcd` for a waveform there. Verilator's raw
coverage data and compiler files stay under `.build/`.
The report is the unmodified plain-text output of
`verilator_coverage --report summary,hier`. Native annotated source files are
written to `.build/coverage/` with `--annotate-all --annotate-points --annotate-min 1`.
These contain individual coverage points and hit counts; `-` marks uncovered
points and `+` marks covered points. Coverage requires at least one hit.
The harness compiles the top-level layer bind files emitted by firtool so
verification layers participate in simulation. Named `Cover` properties appear
as `type=user` points in the annotated sources. Only properties
elaborated for the selected parameters are counted. Verilator 5.050 has an
[inline SVA expression-coverage bug](https://github.com/verilator/verilator/issues/7898);
the harness disables expression coverage on that version while retaining user
`Cover` coverage and the other coverage types.

The tool locates the Scala generator and `parameter.json`, links `dpi_impl.py`
to its source on the first run, then preserves the output directory's driver on
later runs. Drivers can resolve `__file__` to load their own adjacent resources;
the harness does not prescribe or inspect a stimulus file format.
The current SyncQueue driver supplies stimulus but contains no DUT-output
assertions; successful simulation alone does not verify FIFO correctness.

For another already generated testbench, use the reusable Python runner:

```shell
python3 tblib/tbh/run.py out/my-simulation
```

The simulation directory must contain one `.mlirbc`, the matching `.json`,
and one `dpi_impl.py`, `.cc`, `.cpp` or `.cxx`. The harness derives the top module
and links the directory's `run.py` to its own entry point.

A single DPI function maps to `step()` by default. With multiple functions,
Python function names default to the DPI symbols; `--bind symbol=callback`
overrides a mapping. A C++ driver can instead define the exported C functions
declared by the generated `.build/dpi/dpi_api.h` (include the framework's
`native/capi.h` for C++ linkage) and be passed as the positional source file.

Python exceptions and invalid output values produce a nonzero exit code.
Simulation and wall-clock timeouts default to 1000 ns and 30 seconds; override
them with `--max-time-ns` and `--wall-timeout`. Errors are printed to the terminal;
the waveform recorded before a runtime failure is retained.

The CLI and framework live together in `tblib/tbh`: `tbh` is the command entry,
`run.py` launches generated simulation directories, and `zaozi_tb/` contains
the framework and native sources. The interface JSON generates
only C API declarations in `dpi_api.h`. For Python implementations, CFFI emits
its bridge into `.build/`; fixed framework code loads the Python driver and
checks returned values. C++ implementations export the same C API directly.
Simulator scheduling and waveform capture use fixed framework sources. The
initial implementation supports CIRCT's scalar `bit`,
signed `byte`, `shortint`, `int` and `longint` interfaces. Unsupported vectors,
arrays and four-state values are rejected rather than guessing their ABI;
signed byte outputs must be in -128..127.

For Python development, `pyrightconfig.json` checks Python sources under `tblib`
and uses `.venv` for CFFI dependencies. Create it from the pinned Nix environment:

```shell
nix shell .#testbench-python -c python3 - <<'PY'
import site
import subprocess
import sys
from pathlib import Path

subprocess.run([sys.executable, "-m", "venv", "--without-pip", ".venv"], check=True)
packages = next(Path(".venv/lib").glob("python*/site-packages"))
(packages / "nix-dependencies.pth").write_text("\n".join(site.getsitepackages()) + "\n")
PY
```

The `.pth` file also makes the Nix dependency paths visible to static import
resolution. Recreate this environment after updating the pinned Python packages.
Run basedpyright from the repository root; also check `tblib/tbh/tbh` explicitly
because the command entry has no `.py` extension. The declarations in
`tblib/tbh/typings` describe the CFFI dynamic module.
