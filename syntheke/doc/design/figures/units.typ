#import "common.typ": *

#let units = cetz.canvas(length: 1cm, {
  import cetz.draw: *
  // two designs instantiate one shared design
  let design(p, name) = {
    rect((p.at(0) - 0.6, p.at(1) - 0.3), (p.at(0) + 0.6, p.at(1) + 0.3), radius: 2pt, stroke: 0.7pt + c-hier)
    content(p, code(name))
  }
  design((0.8, 3.2), "A"); design((2.8, 3.2), "B"); design((1.8, 1.4), "S")
  arrow((0.95, 2.85), (1.6, 1.75), paint: c-hier, thickness: 0.7pt)
  arrow((2.65, 2.85), (2.0, 1.75), paint: c-hier, thickness: 0.7pt)

  // one directory per design; S, shared, lies under the common prefix of its users
  let file(p, label, fill) = {
    rect((p.at(0), p.at(1) - 0.17), (p.at(0) + 2.0, p.at(1) + 0.17), fill: fill, stroke: 0.4pt)
    content((p.at(0) + 0.08, p.at(1)), anchor: "west", text(size: 6pt, font: "DejaVu Sans Mono", label))
  }
  let dir(x, name, gens) = {
    content((x, 4.0), anchor: "west", code(name + "/"))
    file((x, 3.5), name + ".mlirbc", rgb("#e3d5b8"))
    for (i, g) in gens.enumerate() { file((x, 3.1 - i * 0.4), g + ".mlirbc", c-fill) }
    file((x, 3.1 - gens.len() * 0.4), "design.json", rgb("#fff4d6"))
  }
  dir(4.4, "A", ("G_<hash>",))
  dir(6.8, "B", ("H_<hash>",))
  dir(9.2, "S", ("K_<hash>", "L_<hash>"))

  // a build links A's files, S stays external, and lowers the result
  rect((4.4, 0.6), (6.4, 1.6), radius: 2pt, stroke: 0.7pt)
  content((5.4, 1.1), code("firld"))
  arrow((5.4, 2.0), (5.4, 1.65), paint: luma(60), thickness: 0.7pt)
  rect((7.0, 0.6), (9.0, 1.6), radius: 2pt, stroke: 0.7pt)
  content((8.0, 1.1), code("firtool"))
  arrow((6.45, 1.1), (6.95, 1.1), paint: luma(60), thickness: 0.7pt)
  for i in range(3) { rect((9.6 + i * 0.12, 0.7 - i * 0.12), (10.6 + i * 0.12, 1.5 - i * 0.12), fill: white, stroke: 0.5pt) }
  content((10.3, 1.0), code(".sv"))
  arrow((9.05, 1.1), (9.55, 1.1), paint: luma(60), thickness: 0.7pt)
})
