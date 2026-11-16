#import "../lib.typ": *
#import "../figures/frozen.typ": frozen

= Frozen designs <ch-frozen>

A subsystem is often shared by several enclosing designs. If each of them negotiated it again, its interface
would drift with its surroundings. So a design is negotiated on its own, once, and what it offers outside
is frozen: an enclosing design can only accept it.

== Boundaries <sec-boundaries>

To negotiate alone, a design states what it assumes about the outside. At the root,
`port.boundary(externalParams)(domains*)` makes a generator's port a *boundary*: for an inward port,
`externalParams` is the `Down` the outside offers and the result an `InwardBoundary`; for an outward port, it is the
`Up` the outside asks for and the result an `OutwardBoundary`. `domains` are the outside end's domains. Syntheke
puts a *boundary module* under the root whose constant nodes stand for the outside and binds them to the ports, so
the design negotiates like any other, and each boundary becomes a port of the root.

A design's contract is what negotiation settled at its boundaries: at every boundary, the `Down`, `Up`, `Edge` and
interface; the properties (`describe`) of every domain the boundary is in; and which boundaries share a domain. A
design hands out only its boundaries, alone or in vectors and case classes; ports, probes and domains leave a design
only through boundaries and public probes.

== Instances <sec-instances>

`design.instantiate` places a frozen design as a child. The enclosing design does not see its modules: the
instance is a boundary module whose constant nodes carry the frozen edges (@fig-frozen). A frozen outward boundary
becomes a node offering its frozen `Down`; a frozen inward boundary becomes a node asking for its frozen `Up`. The enclosing design binds these nodes
(`node <-- instance.ports.out`, `instance.ports.in <-- node`, or one instance to another) and negotiates. Afterwards,
every boundary's `Down`, `Up`, `Edge` and interface must be exactly as frozen; any change is an error.

A frozen design is read without instantiating it: `design.ports` are its boundaries, `design.edgeOf(boundary)` the
frozen edge, `design.probes` its public probes. An enclosing design may choose its own user parameters from these:
reading the previous round's result as this round's input closes no loop. A design publishes what outside tools need
through its boundaries and public probes, which together are its ABI. `boundary.boundary` forwards an instance's boundary as
a boundary of the enclosing design, with the frozen edge as its outside; `probe.boundary` makes an instance's probe
public in the enclosing design.

== Domains at an instance <sec-instance-domains>

A frozen boundary is in domains of the frozen design. The enclosing design holds one domain for each:

- A domain it *feeds*: `instantiate(feeds)` gives, for each boundary, domains of the enclosing design that take
  the place of the frozen ones of their kinds.
- Otherwise a *copy*: a domain with the frozen one's kind and name under the instance's path, whose definition is
  rewritten with `kind.map` to name the domains standing for the ones it named. Every frozen domain a boundary
  reaches is copied; instantiating a design twice makes two sets of copies.

`boundary.domain(kind)` reads the domain standing for a boundary's frozen domain. Feeds are checked when the design
is instantiated: a fed domain `describe`s like the frozen one it replaces, no frozen domain is fed two domains, and
no domain is fed for two frozen domains, which the frozen design keeps apart. Copies keep their properties and their
separation by construction.

#fig([`D` is negotiated alone: a constant node (diamond) stands for its outside, and its boundary settles to `e*`. In
  `E`, the instance `d` is only that boundary: a constant node carrying `e*`. `E` feeds its `clk` in place of `D`'s
  `clk` (dashed), and `D`'s `div` is copied under the instance as `d.div`, now derived from `E`'s `clk`. After `E`'s
  negotiation, the edge at the boundary must equal `e*`.])[
  #frozen
] <fig-frozen>
