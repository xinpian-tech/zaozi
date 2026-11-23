#import "../lib.typ": *
#import "../figures/structures.typ": structures, structures-legend
#import "../figures/lifecycle.typ": lifecycle

= Concept model <ch-model>

To negotiate as a separate stage, the design must be explicit data, fixed before negotiation starts. This chapter
defines what that data is.

== Three structures <sec-structures>

A Syntheke design is described by three structures (@fig-structures):

- The *hierarchy tree*: modules and which module instantiates which. It records ownership, namespaces and physical
  module boundaries, and becomes the module hierarchy of the generated hardware down to the generator modules; what
  is inside a generator module is up to its generator. Interconnects with an RTL implementation are modules in this
  tree like any other.
- The *connection structure*: generator modules declare named inward and outward *nodes*, each a protocol port of
  the module; a *bind* joins an outward node to an inward node; and the parameter rules of a module's nodes read
  other nodes of the same module, which are its *internal dependencies*.
- The *domain structure*: the clock, reset and power domains, how they derive from each other, and which domains
  each node is in. It records meaning, not wires.

A connection may cross any number of levels: a node deep in one subtree can be bound to a node in another. The
connection structure says which two nodes are joined; the hierarchy tree gives the path between them, along which
ports are planned (@ch-hierarchy).

#fig([The three structures of one design. Boxes are the hierarchy tree; circles are nodes, filled with the clock
  domain they are in; green arrows are binds, one crossing the border of `p`; dotted arrows are the internal
  dependencies of `x`. The domains are declared at the root, and `slow` names `clk` in its definition.])[
  #structures
  #structures-legend
] <fig-structures>

== Modules <sec-modules>

Every module is built from a *user parameter*: values its builder chooses before anything else is known.
Everything the module declares may depend on it. There are two kinds:

- A *wrapper module*, `wrapper(moduleName) { ... }`, only organizes the hierarchy. It instantiates children and
  binds their nodes. Its hardware is instances, ports and wires, all of which Syntheke plans and emits.
- A *generator module*, `generator[FP] { ... }`, has no children and stands for one generator, which produces all of
  its hardware. It declares nodes, the domains each node is in, each node's parameter rule, probes, and the function
  that computes its full parameter `FP`.

The two bodies run in different scopes, `WrapperScope` and `GeneratorScope[FP]`, so a generator body cannot
instantiate children or bind.

== Nodes and binds <sec-nodes>

`inward(p)(domains*)` and `outward(p)(domains*)` declare a node of protocol `p` (@ch-protocol). A bind,
`target <-- source`, joins an outward node to an inward node of the same protocol object. A bind points the
way its source's offer, `Down`, travels; the target's request, `Up`, travels back. Every outward node is the source of
exactly one bind and every inward node the target of exactly one, so nodes, ports and connections correspond one to
one. A bind is declared in a wrapper that is an ancestor of both ends: a module connects only nodes in its
own subtree, and connections leaving it are written further out.

A node is declared as a draft and sealed exactly once with its parameter rule:

- `node.fixed(value)`: a constant.
- `node.derive(sources)(f)`: a function of opposite nodes of the same module, given as a node or as sequences and
  tuples of nodes. An outward node computes its `Down` from inward nodes' `Down`; an inward node computes its `Up`
  from outward nodes' `Up`. The wrong direction is a compile error.

The sources of `derive` are the module's internal dependencies; they are not declared separately, so what a
function can read is exactly what it depends on. A rule may read nodes of any protocol. Ports
that affect each other's parameters are modeled as named nodes whose rules read each other. Binds and internal
dependencies together form the *parameter dependency graph*, which must be acyclic (@ch-negotiation). The graph,
not the source, decides when a rule runs, so rules must have no side effects.

== Domains <sec-domains>

A *domain kind* (`DomainKind`) defines a kind of domain: clock, reset, power, security, and so on. Syntheke knows no
kind by name; design libraries define them. A kind has a `Definition` type, `map`, which rewrites every domain a
definition names, and `describe`, the properties that make two domains interchangeable.

A *domain* is declared at the design root, `kind.declare(definition)`. Its definition is data: the domain's
properties, and the other domains, of the same or another kind, it derives from or depends on. A definition can only
name domains that already exist, so references never form a cycle; a domain that names no domain of its own kind is
a *root* of that kind, the others *derive* from it. A kind checks its own rules, in its own code, when a definition is
constructed; Syntheke does not interpret definitions. A domain is never computed: it is exactly its definition,
and whatever a generator needs from a domain, the kind derives from that definition.

A node names the domains it is in when it is declared, at most one per kind; `node.domain(kind)` reads one back.
Apart from the copies it holds for its instances (@ch-frozen), every domain of a design is declared at its root, and
modules only reference them. The root therefore shows every clock, reset and supply and how they relate, and each
module's call site shows which domains it is in. Domains and memberships
create no ports or wires: the only connection primitive is the bind.

== Designs <sec-designs>

`Design(moduleName) { ... }` is a design: its body is the root wrapper's body. A design is the unit of negotiation;
a design that instantiates it sees only its boundaries and public probes (@ch-frozen). What the body returns is
what the design hands out.

== Building <sec-build>

Each declaration is named after the `val` it is assigned to; `named(name) { ... }` names one where there is no
`val`, as in a loop. Names match `[A-Za-z_][A-Za-z0-9_]*`. Identities derive from names: a `ModuleId` is the path
of instance names from the root, a `ModuleNodeId` a module and a node name, a `BindId` a source and a target node, a
`DomainId` a module and a domain name. Every declaration records its source location, used only in messages.

A reusable module is a function that calls `generator[FP]` or `wrapper` and returns a class holding its ports; the
instance is named after the `val` at the call site. A generator definition is a `given GeneratorDefinition[FP]`,
resolved from the module's `FP` type.

== Lifecycle <sec-lifecycle>

Every value in a design becomes known at one point in time, and every piece of user code is written at one point
and runs at one point. Syntheke keeps three times apart (@fig-lifecycle): *build* decides what exists, *negotiation* decides what the
parameters are, *elaboration* decides what the hardware is. Code at one time never reads a value of a later time.
That is what lets each stage run, and be checked, on its own.

+ *Before the build.* User parameters, protocol objects, domain kinds and generator definitions are ordinary Scala
  values, written by the designer and by libraries.

+ *Build.* The first time a design is used, its body runs once, and every wrapper and generator body inside it runs
  as it is reached. Bodies declare what exists: modules, domains with their definitions, nodes with their domains,
  binds, boundaries (@ch-frozen) and probes (@ch-verification). A body also *writes* rules it cannot run yet, because their inputs do not exist: the
  value of `fixed` is known, but a `derive` function waits for the parameters of the nodes it reads, `parameters`
  waits for the module's edges, and a probe selector waits for the full parameter. Since what exists depends only on
  values already known, the structure is complete before any parameter is computed. When the body returns, the
  `DesignSpec` is fixed and the scopes of its bodies are closed.

+ *Frozen designs, inside the build.* When a body first touches another design, by `instantiate`, `ports`,
  `edgeOf` or `probes` (@ch-frozen), that design is built and negotiated completely, right there, and its result
  kept. The body
  then reads the frozen result as ordinary values: it can choose user parameters from a frozen edge, or query
  probes to observe. Domains fed to an instance are checked at `instantiate`. The inner design is finished before the
  outer one goes on; a design that instantiates itself while it is being built is an error.

+ *Negotiation.* Right after its build, the design is negotiated (@ch-negotiation), and the written rules run in the
  order the dependency graph decides: `derive` functions while `Down` and `Up` propagate, `negotiate` and `interface`
  per bind, `parameters` on the module's `EdgeView`, then the generator definition's `probes` and `observations` and
  the probe selectors on the full parameter (@ch-verification). A rule reads only what it is given and what was known when it was written. The result is the frozen
  `ResolvedDesign`.

+ *Elaboration.* `Elaborator.elaborate` runs the generators (@ch-hardware). Only now does a generator's own code run,
  and it receives nothing but its full parameter. That is why the full parameter must determine a generator's whole
  module, and why the same parameter, written as JSON, elaborates the IP alone (@sec-generator-contract).

+ *Linking and lowering.* The build system links each design's circuits with `firld` and lowers them with
  `firtool`, from the metadata elaboration wrote.

#fig([Code against time. A hollow mark is where code is written, a filled mark where it runs. `D` is a frozen
  design, built (grey) and negotiated (blue) inside this build before the body reads it. Diamonds are what each
  stage leaves.])[
  #lifecycle
] <fig-lifecycle>

