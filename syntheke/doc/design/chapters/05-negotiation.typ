#import "../lib.typ": *
#import "../figures/propagation.typ": propagation

= Negotiation <ch-negotiation>

`Negotiator.negotiate(design)` turns a design's `DesignSpec` into its `ResolvedDesign`. Its structure and domains are checked
first; then parameters propagate over the dependency graph, `Down` forwards and `Up` backwards; every bind settles; and
every generator module computes its full parameter. This chapter follows that order.

== Steps <sec-steps>

+ *Design boundaries.* Everything the body hands out is a boundary of the design (@ch-frozen).
+ *Structure.* No two distinct generator definitions share a name; no two wrappers share a module name; both ends
  of a bind are nodes of the design, of one protocol object, under the wrapper that declares the bind; every outward
  node is the source of exactly one bind and every inward node the target of exactly one.
+ *Domains.* Every domain a definition names and every domain a node is in belongs to the design, or is a copy it
  holds for an instance (@ch-frozen). Then every bind's crossings are checked against its protocol
  (@sec-crossing).
+ *Order.* The dependency graph is sorted (@sec-order).
+ *Propagation.* `Down` in that order, `Up` in reverse (@sec-propagation).
+ *Settling.* `negotiate` for every bind, in declaration order, and its interface, which must contain no probe.
+ *Full parameters.* Each generator module's `parameters` function, on its `EdgeView`; then its public probes are
  selected and its observations read (@ch-verification).
+ *Frozen contracts.* Every instance keeps the edges it was frozen with (@ch-frozen).
+ *Planning.* Ports, wires and layers through the hierarchy (@ch-hierarchy).

Domains come first because they depend only on their definitions. Parameter rules may read domains and carry
their properties in `Down` and `Up`; `negotiate` sees only those. Parameters never decide domains: a domain
property that depends on a negotiated result comes from an enclosing design reading a frozen design's result as a
user parameter (@ch-frozen).

== Order <sec-order>

The `Down` graph has an edge from each bind's source to its target, and from each inward node to each outward node
that reads it; an inward node that reads outward nodes adds an edge from itself to them, since its `Up` is computed
from theirs on the way back. The `Up` graph is the reverse. The order is Kahn's algorithm, always taking the ready
node that comes first in the hierarchy's preorder and then in declaration order, so the order, and with it the
first error, is the same on every run. A cycle is reported with every node on it and its source location.

== Propagation <sec-propagation>

Propagation is two passes over that order (@fig-propagation). In order, each outward node evaluates its rule on the `Down` of the inward nodes it reads, all of which have already
arrived, and its `Down` passes along its bind to the target. In reverse order, each inward node evaluates its rule on
the `Up` of the outward nodes it reads, and its `Up` passes back to the source. Fixed nodes give their constant.
The two passes do not read each other's results. A `Violation` stops negotiation with the node, the direction, the
inputs it read in order, and its source location.

#fig([The graph of @fig-structures in both passes; numbers are the order. `Down` (top) is computed in order: `d5`, the `Down` of `x.out0`, from what arrived at `x.in0` and `x.in1`. `Up` (bottom) is computed in reverse: `u3` and `u4` from what `c.in` sent back. Dotted: internal dependencies.])[
  #propagation
] <fig-propagation>

== Records <sec-records>

#table(
  columns: (auto, 1fr),
  table.header([Record], [Contents]),
  [`ResolvedEdge`], [Per bind, in declaration order: the bind, its protocol, `Down`, `Up`, `Edge` and interface.
    `edgeAs(protocol)` reads the edge typed.],
  [`ResolvedGeneratorModule`], [Per generator module, in preorder: its `EdgeView` (each node's edge, in node
    order), full parameter and public probe declaration. A node is in exactly one bind, so a port handle finds its
    edge: `view.edgeOf(port)` is typed by the port's protocol, and there is no lookup by name.],
  [`ResolvedDesign`], [The `DesignSpec`, the edges, the generator modules, the port, wire and layer plans, the
    published probes, the observations, and the frozen designs it instantiates. It holds nothing of their insides.],
)

== Errors <sec-errors>

User errors are `NegotiationException`s. A message states the problem, then the identities, parameter values and
source locations involved; there are no error codes. A check does not depend on the other checks of its step, so
all of them run and their failures are reported together, as `<step> failed:` with one line each; the next step
does not run, since it assumes what the step checked. A computation (a parameter rule, `negotiate`, a full parameter
function, a probe selector) depends on earlier values and stops at the first failure; it reports a conflict as a
`Violation` value, whose message should name both sides of the conflict. Exceptions thrown by user code pass through
unchanged.

What can be decided where it is written fails at once, while building, at that source location: a name that is not
legal or is taken; a node naming two domains of one kind, or none of a kind its protocol carries; a draft sealed
twice or never; a generator module without `parameters`, or calling it twice; a rule reading another module's node;
a domain, boundary or forwarded probe declared away from the root; a design instantiating itself; `node.domain` of a
kind the node is not in; a boundary that is not an instance's; a feed that is not allowed (@ch-frozen); a
declaration into a module whose body has returned.
