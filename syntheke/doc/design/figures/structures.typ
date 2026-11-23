#import "../lib.typ": *

#let structures = cetz.canvas(length: 1cm, {
  import cetz.draw: *
  let mod(a, b, label, fill: none) = {
    rect(a, b, stroke: 0.8pt + c-hier, fill: fill, radius: 2pt)
    content((a.at(0) + 0.12, b.at(1) - 0.12), anchor: "north-west", small(label))
  }
  // a node on a module border; its name is written inside the module
  let pin(p, fill, label, inside: "west") = {
    circle(p, radius: 0.13, fill: fill, stroke: 0.6pt)
    let dx = if inside == "west" { -0.22 } else { 0.22 }
    let anc = if inside == "west" { "east" } else { "west" }
    content((p.at(0) + dx, p.at(1)), anchor: anc, code(label))
  }
  let bind(a, b) = line(a, b, stroke: 1.1pt + c-edge, mark: (end: ">", fill: c-edge))

  // hierarchy
  mod((0, 0), (15, 7.4), [*root* (wrapper)])
  mod((0.5, 0.4), (4.8, 4.8), [*p* (wrapper)])
  mod((0.9, 2.8), (4.0, 4.1), [*a* (generator)], fill: c-fill)
  mod((0.9, 0.8), (4.0, 2.1), [*b* (generator)], fill: c-fill)
  mod((6.4, 0.8), (9.8, 4.2), [*x* (generator)], fill: c-fill)
  mod((11.8, 1.6), (14.5, 3.4), [*c* (generator)], fill: c-fill)

  // domains, declared at the root: slow derives from clk
  rect((9.2, 5.55), (14.1, 6.75), stroke: 0.5pt + c-dim, radius: 2pt)
  rect((9.5, 5.85), (10.6, 6.45), fill: c-clk-a, stroke: 0.6pt)
  content((10.05, 6.15), code("clk"))
  rect((12.6, 5.85), (13.7, 6.45), fill: c-clk-b, stroke: 0.6pt)
  content((13.15, 6.15), code("slow"))
  line((12.55, 6.15), (10.65, 6.15), stroke: 0.7pt + c-dim, mark: (end: ">"))

  // nodes, filled with the clock domain they are in
  pin((4.0, 3.45), c-clk-a, "out")
  pin((4.0, 1.45), c-clk-a, "out")
  pin((6.4, 3.45), c-clk-a, "in0", inside: "east")
  pin((6.4, 1.45), c-clk-a, "in1", inside: "east")
  pin((9.8, 2.45), c-clk-b, "out0")
  pin((11.8, 2.45), c-clk-b, "in", inside: "east")

  // internal dependencies of x: out0's rule reads in0 and in1
  let dep = (paint: c-dim, dash: "dotted", thickness: 0.8pt)
  line((7.15, 3.35), (8.9, 2.55), stroke: dep, mark: (end: ">"))
  line((7.15, 1.55), (8.9, 2.35), stroke: dep, mark: (end: ">"))

  // binds; the upper two cross the border of p and are declared in root
  bind((4.15, 3.45), (6.25, 3.45))
  bind((4.15, 1.45), (6.25, 1.45))
  bind((9.95, 2.45), (11.65, 2.45))
})

#let structures-legend = small[
  #box(width: 0.9em, height: 0.6em, fill: c-clk-a, stroke: 0.4pt) in `clk` #h(0.8em)
  #box(width: 0.9em, height: 0.6em, fill: c-clk-b, stroke: 0.4pt) in `slow` #h(0.8em)
  #text(fill: c-edge)[→ bind] #h(0.8em) #text(fill: c-dim)[⇢ internal dependency] #h(0.8em)
  #text(fill: c-hier)[□ module]
]
