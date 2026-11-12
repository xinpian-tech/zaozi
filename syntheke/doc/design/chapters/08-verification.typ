#import "../lib.typ": *
#import "../figures/probes.typ": probes

= Observation for verification <ch-verification>

Verification reads signals inside the design. Wiring them out by hand breaks encapsulation and breaks again with every topology
change; naming them by hierarchical path strings is fragile. Syntheke splits observation into three parts: which
references a generator publishes, what kind of probe each reference is, and who reads them. Signals leave as FIRRTL
probes, read-only references, and Syntheke plans their ports and wires through the hierarchy. Probes do not travel
through binds and decide no parameter. Each probe port has a FIRRTL layer, so release builds can remove the
verification logic.

== Publishing <sec-publishing>

A generator decides from its full parameter which *public probe ports* it has: outputs of type `Probe`, each with a
name, the referenced data type and a layer. `GeneratorDefinition.probes(fullParam)` reports them. Their names are
unique, legal, and differ from the module's node names.

A *probe contract* is a kind of probe: a `ProbeContract[P]` object, compared by identity. `P` is the *probe
parameter*: what a reference means.

A *probe* is a generator module's statement about one of its public ports: `probe(selector)` declares a
`ProbeNode[P]` named after its `val`. The selector belongs to a contract and runs once the full parameter is known:
from the full parameter and the public ports it picks a port and gives `P`, or says that this instance has no such
probe. Selecting a port that is not public is an error.

== The catalog <sec-catalog>

The selected probes form the design's *probe catalog*, each entry a `ResolvedProbe[P]`: the probe, its parameter and
its public port. A design *publishes* the probes it declares and those it forwards from its instances with
`probe.boundary`. An enclosing design reads the catalog of an instance, `instance.probes.query(contract)`, and gets
every probe of that contract. The instance is frozen by then, so the catalog is complete regardless of declaration
order.

Consumers ask for probes by contract, never by module path or port name. The probe parameter says what a reference
is; the generator library pairs the contract with a hardware data type (@sec-zaozi). When the topology changes, the
query's answer changes and the consumer's code does not.

== Observers <sec-observers>

A generator that reads probes is an *observer*. It is an ordinary generator module of the enclosing design that takes
the probes it queried as user parameters and records them in its full parameter. From the full parameter, its
definition reports its *observations*, `GeneratorDefinition.observations(fullParam)`: one input per probe it reads,
of the referenced data type, named after the probe's port at the root (@sec-port-names).
Every observed probe must be published by a generator of the design or by an instance, and an input's name must not
be the name of one of the observer's nodes or public probe ports. Because observations are part of the full
parameter, two observers reading different probes are different modules.

== Routing <sec-probe-routing>

Probes reuse the planning of @ch-hierarchy along two kinds of path (@fig-probes):

- *Publication.* Every port of the published catalog is passed up from its module to the root, one probe port per
  level, by `ref.define`. The root port is what an enclosing design reads.
- *Observation.* For each observation, the reference is passed up from the source to the lowest common ancestor of
  source and observer; there it is read with `ref.resolve`; the data goes down as inputs to the observer.

Both paths run only through wrappers and change no generator's ports. A design has no collector of its own: every
selected probe reaches the root, and reading is the observer's declaration.

#fig([A public probe port of `g` in `m`, under the root. Dashed purple: the reference, passed up a level at a time with `ref.define` to the root, where it is published. Below: an observation; the reference is read at the common ancestor and goes on as data to the observer's input, named after the probe's port at the root.])[
  #probes
] <fig-probes>

== Layers <sec-layers>

Each probe port names a layer path. A module declares the
prefix tree of the layers of the probes routed through it, and the layers of every generator and instantiated design
below it. Ports along a probe's path are colored with its layer; when a build disables a layer, FIRRTL's compiler
removes the logic in it.

Syntheke does not insert monitors automatically. A monitor is assembled from the three parts above: the
generator's public probe ports are what can be seen, an observer in the enclosing design is the checker, and layers
make it removable. An edge whose
generators publish no probes cannot be observed.
