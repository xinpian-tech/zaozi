#import "../lib.typ": *
#import "../figures/units.typ": units

= The hardware boundary <ch-hardware>

This chapter is the contract between Syntheke and the generators it calls, and how a negotiated design becomes
circuits.

== The generator contract <sec-generator-contract>

A generator takes a full parameter and produces a module:

+ Its ports, public probe ports, observation inputs, layers and body are all determined by the full parameter. For
  nodes declared from the user parameter, the full parameter function writes their names and interfaces into `FP`.
+ The same generator and full parameter give the same module (@sec-module-identity).
+ It is called with the full parameter alone, so `FP` written as JSON elaborates the IP standalone. Observers are
  the exception: the probe handles in their full parameter are written to JSON but cannot be read back.

A generator has no implicit clock or reset. Clock and reset inputs are inward nodes of a carrier protocol, with a
port and a bind like any other node (@sec-carriers), and the clock and reset domains are given to the generator as
parameters. When a generator's hardware derives a domain, it builds
it from that domain's definition.

On Syntheke's side, a generator is a `GeneratorDefinition[FP]`: its name, the writer of `FP`, and two functions of
the full parameter that report its public probe ports and its observations. Two distinct definitions in one design
must not share a name. A generator whose hardware is a FIRRTL module extends `CirctGenerator[FP]`, which adds its
module name (@sec-module-identity), its layers, and `instantiate`, which places an instance and returns every module
it elaborated for it, each as a circuit of its own. Each hardware language plugs in as one such subclass.

== Ports <sec-ports>

A generator module's hardware ports are the generator's own, and Syntheke expects exactly these:

- one per node, named after it: an output of the edge's interface for a bind source, an input for a target;
- one per public probe port, an output of its `Probe` type;
- one per observation, an input of the referenced data type.

These names are distinct within the module. When an instance is placed, its ports are checked against this
expectation field by field: names, order, direction (`Flipped`), type constructors, vector lengths, widths and
signedness. A missing port, an extra port, or a mismatch fails with an `ElaborationException` naming the module, the
port, the source location and the first differing path.

== zaozi generators <sec-zaozi>

`zaozi(generator)` serves a zaozi `Generator` as a `CirctGenerator`:

#table(
  columns: (auto, 1fr),
  table.header([Part], [From the zaozi generator]),
  [Name], [The generator object's class name without a trailing `$`, then without a trailing `Gen`.],
  [Public probe ports], [Its probe declaration for the full parameter; every field is an output reference.],
  [Observations], [Those of its interface, if the interface is a `ProbeIO`.],
  [Layers], [Its layer declaration for the full parameter.],
  [Instantiation], [The generator renamed to `moduleName(fullParam)`, instantiated inside `Elaborate.collect`, which
    returns the instance and every module elaborated for it.],
)

A zaozi probe contract is `ProbeBindingFor[P, T]()`: probes of it publish zaozi data `T` with parameter `P`.
`contract.from(generator)(select)` is its selector for one generator; `select` picks, from the full parameter and the
generator's probe declaration, the parameter and the field to publish. An observer gets a handle with
`resolved.observe`, with the contract as a given, and keeps it in its full parameter; its interface,
`ProbeIO(parameter, probes*)`, has one input per probe, named by the probe's port; in the architecture,
`handle.bind(io).read()` is the probe's value. A handle serializes as its public port, so it is part of the
observer's module name.

== Elaboration <sec-elaboration>

`Elaborator.elaborate(roots)` elaborates the given designs and every design they instantiate, each once even when
several designs share it, dependencies first. Each design is one *elaboration unit* (@fig-units): a circuit named after its root
wrapper, in a directory of its own. A root's directory is its name; a design instantiated by others goes under the
longest common prefix of their directories.

A unit's circuit contains:

- an extmodule for each instantiated design, with its root's planned ports;
- a module per wrapper, with its planned ports, its children and its planned wires: `Connect` as a connect,
  `DefineReference` as `ref.define`, `ReadReference` as `ref.resolve` followed by a connect;
- for every generator child, the instance its generator placed, with its ports checked;
- an extmodule declaring every module an instance references but the circuit does not define;
- the layers.

No two units may define a module of the same name, so a shared design is defined in exactly
one unit and referenced from the others. A generator may also instantiate a module written in Verilog: it is
an extmodule whose declaration is its definition, which linking leaves as it is, and its Verilog is given to the
simulator or synthesis tool along with the generated files.

The unit's circuit and every circuit its generators elaborated are written unlinked, each as mlirbc, together with
metadata: `circuit`, the circuit the files link into; `files`, in link order, the unit's own circuit first; and `dependencies`, the directories of the designs it
instantiates. A build links each unit with `firld --base-circuit <circuit> --no-mangle`, which resolves every
extmodule that stands for a module defined in the files and leaves the instantiated designs external, and lowers it
with `firtool` into one Verilog file per module. A probe crossing into an instantiated design is a FIRRTL reference
to its port; firtool exports it as a `ref_*.sv` header that the enclosing unit includes.

#fig([Elaboration units. `A` and `B` both instantiate `S`, so `S` is defined once, in its own directory under the
  common prefix of theirs. Each directory holds the unit's own circuit (dark), one circuit per generator module, and
  `design.json`. A build links one unit's files with `firld`, `S` staying external, and lowers them with `firtool`.])[
  #units
] <fig-units>

== When code runs <sec-when>

The lifecycle of @sec-lifecycle, for every piece of user and library code:

#table(
  columns: (2.4fr, 1fr, 1.3fr, 1.5fr),
  table.header([Code], [Written], [Runs], [Reads]),
  [`Design` body, `wrapper`, `generator`], [build], [build], [user parameters, frozen designs],
  [`kind.declare(definition)`], [build, at the root], [—], [domains declared before it],
  [`inward`, `outward`, `<--`, `.boundary`], [build], [—], [domains, ports],
  [`fixed(value)`], [build], [—], [value known at build],
  [`derive(sources)(f)`], [build], [negotiation], [the sources' parameters],
  [`parameters(f)`], [build], [negotiation], [the module's `EdgeView`],
  [`probe(selector)`], [build], [negotiation], [full parameter, probe declaration],
  [`instantiate(feeds)`], [build], [build; negotiates the design], [the design's ports],
  [`ports`, `edgeOf`, `probes`, `query`], [build], [build], [a frozen result],
  [`Protocol.negotiate`, `interface`], [protocol], [negotiation], [`Down` and `Up`; `Edge`],
  [`Accept` tests], [protocol], [negotiation], [the two domains],
  [`DomainKind.map`, `describe`], [domain kind], [build, negotiation, export], [definitions],
  [`GeneratorDefinition.probes`, `observations`], [generator], [negotiation], [full parameter],
  [`CirctGenerator.moduleName`, `layers`, `instantiate`], [generator], [elaboration], [full parameter],
  [`firld`, `firtool`], [—], [build system], [mlirbc and metadata],
)
