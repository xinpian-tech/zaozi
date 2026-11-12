= Tooling <ch-tooling>

Every intermediate and final result of negotiation is data, so it can be exported and read by other tools. The
design source remains the single source of truth; architecture documents are generated from it.

== Export <sec-export>

`Export` writes a `ResolvedDesign` as JSON. Module identities are arrays of instance names; node, bind and domain
identities are objects of their parts; source locations are a file and a line.

#table(
  columns: (auto, 1fr),
  table.header([Export], [Contents]),
  [`topology`], [The module tree (wrappers with module name and children, boundary modules with the module they
    stand for, generator modules with their nodes: direction, domains, and the nodes each rule reads), the binds, and
    the domains with the domains they name.],
  [`domains`], [Every domain with its `describe` properties, every node's membership in each kind, and, per bind
    and kind, the domains of its two ends.],
  [`edges`], [Per bind: `Down`, `Up`, `Edge` and interface.],
  [`plan`], [The probe catalog (probe, parameter, port, reference type), the published ports, each observer's
    observations, the port and wire plans, and each module's layers.],
  [`params`], [Per generator module: its generator's name and full parameter. Except for observers, each entry is a
    valid input for elaborating that IP standalone (@sec-generator-contract).],
)

== Order <sec-order-export>

The same design yields byte-identical exports on every run. Modules are in the preorder of the hierarchy, children
in declaration order. Binds, edges and domains are in declaration order, a node's reads in their declared order.
Memberships are sorted by node and kind; crossings follow bind order. Bundle fields keep their protocol order. A
wrapper's ports are emitted sorted by name, its children in declaration order, its wires in plan order. Layers and
map keys are sorted by name. Source locations are the absolute paths sourcecode captured at compile time; they
serve diagnostics, and identities never depend on them.
