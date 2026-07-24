# Syntheke

> **Draft the graph. Negotiate the terms. Enact the hardware.**

Syntheke (from Greek *συνθήκη* — "treaty, agreement"; Chinese name **合契**) is a
treaty-driven topology and parameter negotiation framework for SoC generators.
It describes *what* hardware modules to instantiate, *with which parameters*,
and *how they connect* — while delegating all hardware construction to
[zaozi](https://github.com/xinpian-tech/zaozi) and, through it, to the
CIRCT/MLIR toolchain.

## Why

A system-on-chip is a hierarchy of independently authored blocks joined by
shared protocols. The RTL interface of each block — address widths, ID spaces,
data widths, supported transfer types — is not a local constant: it is a
function of the *entire topology*. A memory controller's port shape depends on
every client that can reach it; a client's visibility depends on every device
downstream. Threading these values by hand through constructor arguments does
not scale, and hiding them in a global configuration object makes designs
impossible to reason about or reuse.

Syntheke treats this as a negotiation problem. Blocks declare *terms* — the
parameters they offer downstream and the requirements they raise upstream — on
the edges of an explicit graph. A negotiator resolves connection cardinalities,
propagates parameters in both directions, and settles every edge into a binding
agreement. Only then is hardware generated, from fully-resolved, serializable
parameters.

Its second founding principle is a strict separation between negotiation and
hardware construction. Negotiation is pure computation over plain data: it can
be run, tested, dumped, and inspected without ever touching a hardware
elaborator. Hardware logic lives exclusively in zaozi generators, which consume
one serializable parameter object and know nothing about the graph.

## The Triptych Pipeline

```
   Phase 1: Build                Phase 2: Negotiate             Phase 3: Elaborate
┌──────────────────────┐     ┌──────────────────────────┐   ┌──────────────────────┐
│ a Scala DSL drafts   │ ──▶ │ resolve cardinalities    │──▶│ instantiate zaozi    │
│ the design spec:     │     │ propagate parameters     │   │ generators, punch    │
│ module tree, nodes,  │     │ down and up the graph    │   │ hierarchy ports,     │
│ connections, probes  │     │ settle every edge        │   │ wire everything,     │
│                      │     │ plan ports and wires     │   │ emit FIRRTL via MLIR │
└──────────────────────┘     └──────────────────────────┘   └──────────────────────┘
        draft the terms   →   conclude the agreement   →   enact the agreement
```

- **Build** — ordinary Scala code assembles a *design specification*: a module
  hierarchy, protocol nodes, connections between them, and verification probes.
  Nothing is elaborated; the spec is plain data plus deferred callbacks.
- **Negotiate** — a pure function turns the spec into a *resolved design*:
  connection multiplicities are solved, parameters flow downstream and
  upstream, per-edge parameters are settled, and every hierarchy-crossing port
  and wire is planned. Errors are values, reported in bulk with source
  locations.
- **Elaborate** — the resolved design is enacted: zaozi generators are
  instantiated with their final serializable parameters, structural wrapper
  modules are emitted directly through CIRCT, and the planned wiring is drawn.

## Core concepts

- **Protocol** — a negotiation contract: the type of parameters flowing
  downward (source → sink), the type flowing upward (sink → source), and a
  function that settles one edge from the pair.
- **Node** — a point where a module participates in a protocol. Node roles
  (source, sink, adapter, nexus, …) determine how many edges a node accepts and
  how parameters transform across it.
- **Two graphs** — the *module hierarchy* (ownership and namespacing) and the
  *negotiation graph* (nodes and edges) are distinct; edges may cross hierarchy
  levels, and Syntheke plans the ports punched through every boundary on the
  path.
- **Wrapper vs. generator modules** — a design module either composes children
  (no hardware of its own) or owns exactly one zaozi generator (all hardware
  inside). The two are separated at the type level; there is no mixed form.
- **Two-layer parameters** — a generator's parameter is the fold of a
  *user parameter* (written in the Build phase) with a *protocol parameter*
  (computed by the negotiator). The folded result is the only object that
  crosses the boundary into zaozi, and the only thing that must serialize.
- **Verification probes** — design-verification taps are first-class protocol
  nodes: probe sources flow strictly upward to an ancestor sink, ports are
  punched automatically along the way, and probe hardware is confined to
  CIRCT layers so it can be dropped from a release build.

## Documentation

The design-document series lives in `doc/design/` — written in Chinese, typeset
with Typst, all diagrams drawn with fletcher/cetz. The build is managed by
[Typix](https://github.com/loqusion/typix): fonts (Noto CJK, JetBrains Mono) and
Typst packages are pinned in the flake, so the PDF is fully reproducible.

```shell
nix build ./doc#design     # → ./result is syntheke-design.pdf
nix build ./doc#rational   # archived early notes (Chinese)
nix build ./doc#naming     # archived naming study (Chinese)
```

## Repository layout

| path | contents |
|---|---|
| `doc/design/` | the design-document series (Typst, Chinese) |
| `doc/*.typ` | archived pre-design notes (Chinese) |
| `doc/flake.nix` | Typix-managed documentation build |
| `diplomacy/` | vendored read-only reference sources studied during the design phase; not part of Syntheke |

## Status

Syntheke is in its **design phase**: this branch delivers the complete design
documentation, starting from motivation, before any implementation. The design
document is the contract for the implementation that follows.
