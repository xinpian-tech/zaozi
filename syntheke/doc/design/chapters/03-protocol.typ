#import "../lib.typ": *
#import "../figures/edge.typ": edge-figure
#import "../figures/fullparam.typ": fullparam

= Protocols <ch-protocol>

A protocol says what a connection negotiates: which parameters flow along a bind, how the two ends' parameters
settle into one, what hardware interface results, and which domains the connection may cross.

== Down, Up and Edge <sec-down-up-edge>

Each end of a bind holds half of the facts. The source offers a *downward parameter* `Down`, computed
by its rule and carried along the bind to the target. The target asks with an *upward parameter* `Up`, computed by
its rule and carried back to the source. After both passes, every bind has exactly one `Down` and one `Up`, and the
protocol settles them into the bind's *edge parameter* (@fig-edge):

```scala
def negotiate(down: Down, up: Up): Either[Violation, Edge]
```

A bind with its settled parameters is an *edge*. The first values come from nodes whose rules read nothing; modules in between
transform them.

#fig([One edge. `Down` goes along the bind, `Up` back; `negotiate` settles them into the `Edge`, whose interface is a bundle seen from the source: the source port is an output, the target port an input, and a flipped field flows back.])[
  #edge-figure
] <fig-edge>

== Protocol objects <sec-protocol-object>

A protocol is an object implementing `Protocol`, and its identity is the object. It defines the types `Down`, `Up`
and `Edge`, `negotiate`, `interface`, writers for the three parameter types, and its domain rules (@sec-crossing).
Because the types belong to the protocol value, `p.negotiate` takes `p.Down` and `p.Up` and returns `p.Edge`, and a
bind can only join two nodes of the same protocol: a connection across protocols without a converter does not
type-check.

A protocol converter is an explicit generator module with an inward node of protocol A and an outward node of
protocol B: its outward rule maps `A.Down` to `B.Down`, its inward rule maps `B.Up` to `A.Up`, and its generator
builds the conversion hardware. Parameter conversion and hardware conversion live in one module.

== Crossing domains <sec-crossing>

The protocol decides whether the two ends of a bind must share a domain or may cross between domains; Syntheke
knows nothing about clocks or power. For each domain kind either end of a bind is in:

- If the protocol *carries* the kind (`carries`), both ends are in the same domain of it.
- Otherwise the protocol's `accepts` has an `Accept(kind)((a, b) => ...)` deciding whether a bind may go from
  domain `a` to domain `b`.
- A kind the protocol neither carries nor accepts, or in which only one end has a domain, is an error.

There is no list of synchronous protocols or asynchronous exemptions: every kind on a bind is covered by its
protocol, or the design is wrong.

== Interfaces <sec-interface>

Every edge has a hardware interface, `interface(edge)`, a `ProtocolInterface.Bundle`. The interface is used twice:
to type the ports Syntheke plans through wrappers (@ch-hierarchy), and to check the generators' actual ports
(@ch-hardware).

`ProtocolInterface` follows zaozi's value types: `Bundle`, `Vec`, `Bits`, `UInt`, `SInt`, `Analog`, `Bool`, `Clock`,
`Reset`, `Flipped` for a field flowing the other way, and `Probe` for a reference. Each case is a distinct type,
even when two cases are the same wires in hardware. Field names are unique and legal names; widths
and lengths are non-negative; `Flipped` neither nests nor makes a `Vec` element; a `Probe` references data without
`Flipped` or `Probe`. The bundle is seen from the source: the source's port is an output, the target's an input,
and `Flipped` fields go the other way. Field order is part of the type. A bind's interface contains no `Probe`; probes are for observation
(@ch-verification).

== Full parameters <sec-full-parameter>

A generator's parameter merges two layers:

- The *user parameter*, chosen when the module is built. It is known before negotiation and lives in the module body's closure.
- The *resolved parameter*: what negotiation decides for the module. It is the module's `EdgeView`, every node's
  settled edge.

`parameters(view => ...)` merges them into the module's full parameter `FP` (@fig-full-parameter), or fails with a `Violation` when the
module cannot carry what was negotiated or cannot work in the domains it is in. `view.edgeOf(port)` reads a node's `Edge`, typed by its protocol. The function reads only its
own module's edges, and the domains it needs from its closure; it cannot see other modules or feed anything back.
A full parameter should hold properties and stable names rather than instance paths, so that the same IP placed
twice gets the same full parameter and is the same module (@sec-module-identity). An observer's full parameter also
names the probes it reads, by their paths (@sec-observers).

#fig([The full parameter. `parameters` merges the user parameter with the module's `EdgeView`; the result is the only
  thing that crosses the thick line into elaboration, where the generator builds the module from it alone.])[
  #fullparam
] <fig-full-parameter>
