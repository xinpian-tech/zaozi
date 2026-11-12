#import "../lib.typ": *
#import "../figures/planning.typ": planning

= Connections across the hierarchy <ch-hierarchy>

The two ends of a bind can be anywhere in the hierarchy tree. A connection between them needs a port on every
module boundary it crosses and a wire on every level. Syntheke plans these after negotiation, from the settled edges,
so that wrappers need no hand-written ports.

== Planning <sec-planning>

Let a bind's source be in generator module $A$ and its target in $B$. The connection is made in the wrapper $W$ (@fig-planning): the
lowest common ancestor of $A$ and $B$, or the parent of $A$ if $A = B$.

- From $A$'s parent up to but not including $W$, each module gets an output port, and a wire from its child's port
  to its own.
- From $B$'s parent up to but not including $W$, each module gets an input port, and a wire from its own to its
  child's.
- $W$ joins the two ends. If an end's module is a child of $W$, that end is the child's own port.

Every port on the way has the edge's interface, an output on the source side and an input on the target side; a
whole bundle is connected at once. The plan is a list of `PortPlan`s and `WirePlan`s, each with the source location
of the bind it serves.

#fig([A bind from node `x` of generator `a` to node `y` of generator `b`, with `w` their lowest common ancestor. Grey ports are planned, one per crossed border, named after the path down to the node; generator ports keep their names. The thick wire is where `w` joins the two ends.])[
  #planning
] <fig-planning>

== Design boundaries <sec-boundary-ports>

The root's ports come only from its boundaries and from published probes. A bind to the design's own boundary
module is planned from the inner end up to the root, where it meets a root port named after the boundary; the
boundary module itself is not hardware.

An instance of a frozen design is a leaf. A bind to one of its boundary nodes ends at the instance's root port of
the same name. Nothing inside the instance is planned: its own design planned it, and its circuit is elaborated
separately (@ch-hardware).

== Port names <sec-port-names>

A generated port name must be unique in its module and must tell which connection and path it serves. It is built
from segments:

- the *base*: `node`, the node's name and `out` for a source, `in` for a target; `probe`, the public port's name and
  `out` for a probe; `observation` and the input's name for an observation;
- one `inst` and the instance name for each level between the module and the endpoint, from the top.

Each segment is escaped (`$` as `$$`, `_` as `$u`, `-` as `$m`) and the segments are joined with `_`, so different
segment lists give different names. Generator ports and boundary ports keep their declared names. Instance and node names are
unique within their modules, so names clash only in a broken design; two ports of one module with the same name are
reported.

== Wrappers <sec-wrappers>

A wrapper's hardware is all generated: its planned ports, an instance per child, the wires planned in it, and the
FIRRTL layers it needs (@sec-layers). Its module name is given explicitly, `wrapper(moduleName)`: a wrapper has no
full parameter to derive a name from. No two wrappers of a design share a module
name, nor does a wrapper take a generator module's name. Merging wrappers that turn out structurally equal is left
to FIRRTL's deduplication.

== Module identity <sec-module-identity>

By default, a generator module's name is the generator's name, with characters other than letters and digits
replaced by `_`, then `_` and the first 8 bytes, in hex, of the SHA-256 of the name and the full parameter as JSON
with object keys sorted. One full parameter is one module, defined once in a circuit however often it is placed.
