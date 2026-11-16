= Interconnect <ch-interconnect>

An interconnect has two topologies. The *logical* topology is which ports are connected and which inputs reach which
outputs; it decides the parameters. The *physical* topology is how the interconnect implements those paths: its
internal structure, arbitration and resources. Syntheke keeps them apart. An interconnect, including clock and reset
distribution, is a generator module that the source instantiates explicitly. Its logical topology is its nodes, the
binds to them, and the internal dependencies between them, all of which negotiation reads. Its physical topology is
its generator's user parameter: the logical topology does not depend on it, and the module's full parameter function
checks that the negotiated parameters fit it. Either can change without rewriting the other.

== Interconnect nodes <sec-interconnect-nodes>

A generator module declares any number of named inward and outward nodes; how many and which follow from its user
parameter. Port counts are declared, never inferred from connections: a port that is not used is not declared, and when a
module has one port per peer, the same list builds its ports and the binds to them.

The user parameter also maps each named node to a place in the physical topology.

== Propagation through interconnect <sec-interconnect-flow>

An interconnect's parameter rules aggregate parameters by the rules of their protocol. Each outward node derives its `Down` from the
inward nodes that can reach it; each inward node derives its `Up` from the outward nodes it can reach. Reachability
can differ per node; each rule states it by the nodes it reads. Protocol libraries provide the merges, and
an interconnect only says which inputs reach which outputs.

Binds may form physical loops through modules whose ports are independent, as long as no module ties them into a
cycle of internal dependencies. After every edge is settled, each interconnect's full parameter function reads its
nodes' edges and its user parameter, and fails if the negotiated parameters exceed its ports, interfaces, topology
or capacity.

== Same domain inside a module <sec-same-domain>

Which ports of a module share a domain is stated by memberships: two nodes name the same domain. Typically, a node names
the domain of the clock input that drives it.

Memberships describe the hardware as the generator builds it, and Syntheke checks bind crossings against them
(@sec-crossing). It cannot prove that a generator's RTL has no crossing it did not declare; that belongs to RTL
review. A module that must reject a relation checks it in its full parameter function.

== Physical clocks and resets <sec-carriers>

Physical clocks and resets are ordinary protocols whose wires are planned like any other. Their protocol carries
the clock and reset kinds (@sec-crossing), so a clock wire's two ends name the same domain and the wire is checked
against the declarations; the wire does not decide the domain. Its `negotiate` settles only the interface and
physical properties; everything else about a clock or a reset is its domain's definition.

Several outward nodes may carry the same domain. A clock produced by a module is a domain declared at the root,
derived from the module's input clock; the module's outputs carry it and its users name it.

== Bridges <sec-bridges>

A bridge is a generator module with an inward node and an outward node and dependencies between them: the outward
rule maps the inward side's `Down` to the outward side's, the inward rule maps the outward side's `Up` back. The
conversion hardware is built by the bridge's generator. Each side names its own domains. Because its ports follow
from its own edges and wires are planned around it, a bridge can also be the delivery boundary of a subsystem.
