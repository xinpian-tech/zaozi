#import "common.typ": *

// One small SoC, drawn once per kind of parameter. Blue is what initiators bring, red what targets bring, green what
// the connection settles to.
#let skeleton(name) = {
  import cetz.draw: *
  let unit(p, label) = {
    rect((p.at(0) - 0.55, p.at(1) - 0.3), (p.at(0) + 0.55, p.at(1) + 0.3), radius: 2pt, stroke: 0.7pt + c-hier,
      fill: c-fill)
    content(p, code(label))
  }
  unit((0.6, 2.0), "i0")
  unit((0.6, 0.6), "i1")
  rect((4.4, 0.3), (5.6, 2.3), radius: 2pt, stroke: 0.7pt + c-hier, fill: c-fill)
  content((5.0, 2.05), code("xbar"))
  unit((9.4, 2.0), "t0")
  unit((9.4, 0.6), "t1")
  content((-0.4, 1.3), anchor: "east", small(name))
  rect((-1.6, -0.1), (12.1, 3.0), stroke: none)
}
#let link(a, b, w) = {
  import cetz.draw: *
  line(a, b, stroke: (paint: c-edge, thickness: w))
}
#let num(p, n, paint) = {
  import cetz.draw: *
  content(p, text(size: 6.5pt, fill: paint, font: "DejaVu Sans Mono", n))
}

// Widths: each link settles to what both of its ends can do.
#let widths = cetz.canvas(length: 1cm, {
  import cetz.draw: *
  skeleton[width]
  link((1.15, 2.0), (4.4, 1.7), 1.6pt)
  link((1.15, 0.6), (4.4, 0.9), 1.6pt)
  link((5.6, 1.7), (8.85, 2.0), 3.2pt)
  link((5.6, 0.9), (8.85, 0.6), 0.7pt)
  num((1.55, 2.25), "64", c-down)
  num((1.55, 0.35), "64", c-down)
  num((6.05, 2.05), "512", c-down)
  num((8.4, 2.3), "256", c-up)
  num((8.45, 0.3), "32", c-up)
  num((7.2, 2.15), "256", c-edge)
  num((7.2, 0.5), "32", c-edge)
})

// Address sets gather from the targets back to every initiator; a new target changes bars two hops away.
#let addresses = cetz.canvas(length: 1cm, {
  import cetz.draw: *
  skeleton[address]
  let bar(p, spans) = {
    rect((p.at(0) - 0.9, p.at(1) - 0.13), (p.at(0) + 0.9, p.at(1) + 0.13), stroke: 0.5pt, fill: white)
    for (a, b, color, dashed) in spans {
      rect((p.at(0) - 0.9 + 1.8 * a, p.at(1) - 0.13), (p.at(0) - 0.9 + 1.8 * b, p.at(1) + 0.13), fill: color,
        stroke: if dashed { (paint: c-up, thickness: 1pt, dash: "densely-dotted") } else { none })
    }
  }
  let t0 = (0.0, 0.45, rgb("#f4b8b0"), false)
  let t1 = (0.6, 0.7, rgb("#c0392b"), false)
  let t2 = (0.8, 0.9, rgb("#f7d58b"), true)
  bar((9.4, 2.6), (t0,))
  bar((9.4, 1.2), (t1,))
  // a new target t2, and where it shows up
  rect((10.85, 0.0), (11.95, 0.6), radius: 2pt, stroke: (paint: c-hier, dash: "dashed", thickness: 0.7pt))
  content((11.4, 0.3), code("t2"))
  bar((11.4, 0.9), (t2,))
  bar((5.0, 1.3), (t0, t1, t2))
  bar((0.6, 2.6), (t0, t1, t2))
  bar((0.6, 1.2), (t0, t1, t2))
  arrow((8.85, 2.0), (5.65, 1.75), paint: c-up, thickness: 0.8pt)
  arrow((8.85, 0.6), (5.65, 0.85), paint: c-up, thickness: 0.8pt)
  arrow((10.85, 0.35), (5.65, 0.6), paint: c-up, thickness: 0.8pt, dash: "dashed")
  arrow((4.35, 1.75), (1.2, 2.0), paint: c-up, thickness: 0.8pt)
  arrow((4.35, 0.85), (1.2, 0.6), paint: c-up, thickness: 0.8pt)
})

// Identities: the crossbar extends local IDs with the initiator's number; whether that fits depends on the target.
#let identities = cetz.canvas(length: 1cm, {
  import cetz.draw: *
  skeleton[identity]
  let cells(p, n, prefix: false, paint: c-down) = {
    for i in range(n) {
      let x = p.at(0) + i * 0.22
      rect((x, p.at(1) - 0.1), (x + 0.2, p.at(1) + 0.1),
        fill: if prefix and i == 0 { rgb("#d8f0de") } else { white }, stroke: 0.6pt + paint)
    }
  }
  link((1.15, 2.0), (4.4, 1.7), 0.8pt)
  link((1.15, 0.6), (4.4, 0.9), 0.8pt)
  link((5.6, 1.7), (8.85, 2.0), 0.8pt)
  link((5.6, 0.9), (8.85, 0.6), 0.8pt)
  cells((1.5, 2.25), 2)
  cells((1.5, 0.35), 3)
  cells((6.2, 2.15), 4, prefix: true, paint: c-edge)
  cells((6.2, 0.5), 4, prefix: true, paint: c-edge)
  cells((8.2, 2.55), 4, paint: c-up)
  cells((8.4, 1.15), 3, paint: c-up)
  line((7.3, 0.25), (7.6, 0.75), stroke: 1.2pt + c-up)
  line((7.3, 0.75), (7.6, 0.25), stroke: 1.2pt + c-up)
})

#let motivation = stack(dir: ttb, spacing: 4mm, widths, addresses, identities)
