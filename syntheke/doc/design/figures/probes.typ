#import "common.typ": *

#let probes = cetz.canvas(length: 1cm, {
  import cetz.draw: *
  mod((0, 0), (14.4, 4.4), [root])
  mod((0.4, 1.2), (6.2, 3.8), [`m`])
  mod((0.8, 1.7), (2.8, 3.2), [`g`], bg: c-fill)
  mod((9.0, 0.3), (13.8, 1.9), [`o` (observer)], bg: c-fill)
  let ref = (paint: c-dv, dash: "dashed", thickness: 1pt)
  let port(p) = circle(p, radius: 0.12, fill: rgb("#efe6fb"), stroke: 0.8pt + c-dv)
  port((2.8, 2.45))
  content((2.6, 2.45), anchor: "east", code("trace"))
  port((6.2, 2.45))
  content((6.3, 2.85), anchor: "west", code("inst_g_probe_trace_out"))
  port((14.4, 2.45))
  content((14.3, 2.85), anchor: "east", code("inst_m_inst_g_probe_trace_out"))
  // publication: the reference goes up to the root
  line((2.95, 2.45), (6.05, 2.45), stroke: ref, mark: (end: ">", fill: c-dv))
  line((6.35, 2.45), (14.25, 2.45), stroke: ref, mark: (end: ">", fill: c-dv))
  // observation: read at the common ancestor, then data down to the observer
  circle((8.0, 1.1), radius: 0.1, fill: c-dv, stroke: none)
  line((6.35, 2.35), (7.9, 1.2), stroke: ref, mark: (end: ">", fill: c-dv))
  arrow((8.1, 1.1), (8.85, 1.1))
  pin((9.0, 1.1), label: "inst_m_inst_g_probe_trace_out", inside: "east")
  content((8.0, 0.75), code("ref.resolve"))
  content((4.4, 2.15), text(fill: c-dv, code("ref.define")))
})
