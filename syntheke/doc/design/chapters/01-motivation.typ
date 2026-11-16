#import "../lib.typ": *
#import "../figures/motivation.typ": motivation

= Motivation <ch-motivation>

An SoC integrates processor cores, caches, DMA engines, memory controllers and peripherals into a hierarchical
system over several protocols. Each of these is a parameterized IP, and the interfaces between them are
parameterized too. IPs that are each correct on their own still fail together when the parameters on the two sides of an interface
disagree.

The cause is that an IP's parameters are not decided by the IP alone. They are decided by the whole SoC: by what
connects to what, and by which clock, reset and power domain each part sits in. Generating and checking these
parameters is therefore a system-level task, and it has to happen before any hardware is generated.

Data widths, address sets and transaction identities are such parameters (@fig-parameters). They follow different rules: widths
intersect what initiators and targets can do, address sets gather from targets back to initiators, and identities
are constrained by the protocol and by what each interconnect can implement. All of them are properties of the
connection graph, and a change at one end can change interfaces several hops away. The two ends of a connection
each hold half of the facts: the initiator its needs and abilities, the target its supply and limits.

#fig([Three parameters on one connection graph; blue is what initiators bring, red what targets bring, green what a
  link settles to. Width: each link is as wide as both ends allow. Address: the targets' address sets gather back to
  every initiator, and adding `t2` changes the crossbar and both initiators. Identity: the crossbar extends each local
  ID with the initiator's number; `t1` accepts fewer bits, so the crossbar must remap instead.])[
  #motivation
] <fig-parameters>

== An explicit negotiation stage <sec-explicit-stage>

Since interface parameters are functions of the connection graph, the user's parameters and the domains, no single
generator can compute them. Syntheke makes their negotiation a stage between describing the design and generating
its hardware: the design is first recorded as data, then negotiated, and only then does each generator receive its
parameters (@sec-lifecycle). Syntheke chooses the generators, computes the parameters it gives them, and plans the
ports and wires between modules; the hardware comes from generators, here zaozi generators#footnote[zaozi is a hardware construction
library in Scala 3 that emits FIRRTL through CIRCT.]. As a stage of
its own, negotiation can run and be tested without hardware, its results can be exported, and each generator's
parameters can be serialized.

== Requirements <sec-requirements>

#table(
  columns: (auto, 1fr),
  table.header([Requirement], [What it asks for]),
  [Fast iteration], [The design source is the single source of truth; architecture data is exported from it
    (@ch-tooling); mismatches, missing connections and wrong counts are found during negotiation.],
  [Negotiation], [Interface parameters come from the declarations at each end; domains and their crossings are
    checked before any parameter is computed; inconsistencies are reported before any hardware exists
    (@ch-protocol, @ch-negotiation).],
  [Decoupled topology], [The logical topology, which ports connect and which inputs reach which outputs, is separate
    from the physical topology that implements it inside the interconnect. Either can change without the
    other being rewritten; the interconnect joining them is an explicit module, never generated implicitly (@ch-interconnect).],
  [Arbitrary hierarchy], [Connected modules are rarely parent and child. A connection may cross any number of module
    boundaries; the ports on the way are planned by the framework (@ch-hierarchy).],
  [Observation for verification], [Verification logic reads internal signals. Declaring a probe is all the wiring
    needed; paths follow the topology; release builds drop verification logic through FIRRTL layers (@ch-verification).],
  [Standalone IP], [Every IP can be elaborated, tested and reproduced on its own from one fixed parameter file.
    Observers, which read probes of their enclosing design, are the one exception (@ch-hardware).],
  [Frozen subsystems], [A subsystem shared by several enclosing designs is negotiated once and its boundary
    frozen; an enclosing design can only accept that boundary; the subsystem is elaborated once and shared
    (@ch-frozen).],
)
