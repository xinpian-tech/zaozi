#import "../lib.typ": *

= 验证协议 <ch-verification>

验证环境需要观察设计内部的信号，例如供协同仿真比对的架构状态、供记分板检查的互连事务与各级断言。Syntheke 用一样东西表示这类观察关系：被观察模块上声明的观察点，称为#term[探针源][DV source]。信号以 FIRRTL 的探针（`Probe`，对内部信号的只读引用）形式引出；框架把每个探针叶沿层次树自动上提，直至设计根（@ch-hierarchy）。消费者——协同仿真、记分板、断言环境——不是设计图的节点：图内没有收集端，也没有验证 bind。例化期可以提供一个测试平台后端，在根模块内按层消费全部探针（@sec-dv-testbench）；不提供时探针成为顶层探针端口，由设计外的工具接入。每个探针源还声明一条 FIRRTL 层路径，例如 `verification.cosim`，用于控制对应验证逻辑的生成与移除（@req-verification、@sec-layers）。

验证协议是设计协议之外的第二种协议，代码里对应的协议对象是 `DVProtocol`。设计协议在一条 bind 的两端之间双向传播、逐边求解；验证协议没有协商：探针源在声明处给出参数，接口当场导出、当场检查。两种协议的身份都是各自的协议对象。

== 探针源 <sec-dv-declarations>

探针源由生成器模块声明，提供验证协议的 `Down` 与 FIRRTL 层路径。声明记录标识、协议、`Down`、层路径、由协议导出的接口和源码位置，构建期写入 `DesignSpec`。探针源的稳定标识 `DVSourceId` 由所属模块和端点名组成；名称与模块节点共用同一唯一性约束（@sec-generator-module）。

探针源按信号叶对应生成器的若干纯 `Probe` 端口，端口名为源名加叶路径段（@sec-port-naming）。

#图([探针的层次路由。两个探针源（紫）的探针叶沿层次树逐层上提，在设计根引出为顶层探针端口；跨越的模块边界均产生带层路径标注的端口。])[
  #syn-diagram(
    spacing: (15mm, 7.5mm),
    node((0, 0.4), [源 α], name: <s1>, shape: fletcher.shapes.circle, stroke: c-dv),
    node((1.4, 0.4), [源 β], name: <s2>, shape: fletcher.shapes.circle, stroke: c-dv),
    node(enclose: (<s1>,), stroke: c-hier, inset: 10pt, snap: false, name: <m1>),
    node(enclose: (<s2>,), stroke: c-hier, inset: 10pt, snap: false, name: <m2>),
    node(enclose: (<m1>, <m2>), stroke: c-hier, inset: 22pt, snap: false, name: <mid>),
    node((2.9, -0.75), [顶层探针端口], name: <k>, stroke: c-dv, fill: rgb("#f6f1fd")),
    node(enclose: (<mid>, <k>), stroke: c-hier, inset: 34pt, snap: false),
    node((0, -0.35), text(size: 8pt, fill: c-hier)[核], stroke: none),
    node((1.4, -0.35), text(size: 8pt, fill: c-hier)[核], stroke: none),
    node((0.7, -0.95), text(size: 8pt, fill: c-hier)[簇], stroke: none),
    node((0.7, -1.62), text(size: 8pt, fill: c-hier)[顶层], stroke: none),
    edge(<s1>, <k>, "--|>", stroke: c-dv),
    edge(<s2>, <k>, "--|>", stroke: c-dv),
  )
]

== 验证协议对象 <sec-dv-protocol>

验证协议只有一种参数：探针源声明的 `Down`。协议对象的函数 `interfaceOf(down, layer)` 从 `Down` 与层路径导出该源的接口 `ProtocolBundle`；接口的每个信号叶必须是携带该层路径的 `Probe`，且不得出现 `Flipped`——观测是单向的。这些契约在探针源声明处当场检查，违反契约的协议在用户的声明行报错（@sec-error-semantics）。

`Down`、`LayerPath` 与接口都是不可变、可序列化的数据；`downRW` 提供 `Down` 的规范化编码，供工具导出（@ch-tooling）。

== 路由规则 <sec-dv-routing>

#决策([探针自动上提到根，图内不设收集端])[
  探针源声明即完成接线：框架把每个探针叶从源模块逐层上提，直至设计根。收集端不是图的节点——若是，它就要参与协商、要求解聚合接口，而它的形状本是设计的确定函数。消费发生在图之外：例化期的测试平台后端在根内消费清单（@sec-dv-testbench），或由顶层探针端口交给设计外的工具。
] <dec-dv-top>

探针路由复用设计侧的跨层端口规划（@sec-punch-planning），但按信号叶展开，且没有目标分支：探针在硬件里从不组成聚合。对源接口的每个信号叶生成一条 Output 路径——从源的父模块到设计根（含根），每个模块边界一个纯 `Probe` 类型的 Dangle 端口，逐层 `ref.define` 传递；`Vec` 叶按下标展开、与字段叶同样路由。

== 测试平台消费 <sec-dv-testbench>

#term[探针清单][probe manifest]是 `DesignSpec` 的纯函数：每个探针源在声明处已给出 `Down`、层路径与接口，框架把它整理为按模块先序与声明顺序排列的记录——每叶一条，含根作用域端口名（@sec-port-naming 的 Dangle 名）、`ref.resolve` 后的数据类型与叶路径；`Down` 按协议的规范化编码序列化，清单整体可序列化。

例化期把清单按层分组交给#term[测试平台后端][testbench backend]：对每个层，框架在根模块内打开对应 layerblock，由后端例化一个 harness 模块。契约是每叶一个输入端口，以清单端口名命名、类型为该叶的数据类型，除此之外没有端口；框架逐叶 `ref.resolve` 后按名连入，并按 @dec-binding-check 的方式当场核对端口。harness 不在图里、不参与协商，形状是设计的确定函数：清单从规格流向它，方向单一。清单可序列化，harness 因此可以是一个普通的硬件生成器，以清单为完整参数。

不提供测试平台后端时，探针路径终止于根的 Dangle 端口，即顶层探针端口，供设计外的消费者按 FIRRTL 引用 ABI 接入。

== FIRRTL 层与探针移除 <sec-layers>

每个探针源声明一条#term[层路径][layer path]，例如 `verification.cosim` 或 `verification.assert`。框架将穿过同一模块的探针层路径合并为前缀树：

$ "layers"(w) = "前缀树并" {"layer"(s) : s in "子树"(w) "的全部探针端口"} $

#图([层的前缀树合并。子树包含 `verification.cosim` 与 `verification.assert.fatal` 两条层路径，模块的层声明是二者的前缀树并。])[
  #syn-canvas({
    import cetz.draw: *
    // 左树
    content((0.9, 2.6), text(size: 8.5pt)[`verification`])
    content((0.9, 1.6), text(size: 8.5pt)[`cosim`])
    line((0.9, 2.35), (0.9, 1.85), stroke: 0.55pt + gray)
    // 加号
    content((2.6, 2.0), [$+$])
    // 中树
    content((4.4, 2.6), text(size: 8.5pt)[`verification`])
    content((4.4, 1.6), text(size: 8.5pt)[`assert`])
    content((4.4, 0.6), text(size: 8.5pt)[`fatal`])
    line((4.4, 2.35), (4.4, 1.85), stroke: 0.55pt + gray)
    line((4.4, 1.35), (4.4, 0.85), stroke: 0.55pt + gray)
    // 等号
    content((6.1, 2.0), [$=$])
    // 并
    content((8.6, 2.6), text(size: 8.5pt)[`verification`])
    content((7.7, 1.6), text(size: 8.5pt)[`cosim`])
    content((9.5, 1.6), text(size: 8.5pt)[`assert`])
    content((9.5, 0.6), text(size: 8.5pt)[`fatal`])
    line((8.45, 2.35), (7.75, 1.85), stroke: 0.55pt + gray)
    line((8.75, 2.35), (9.45, 1.85), stroke: 0.55pt + gray)
    line((9.5, 1.35), (9.5, 0.85), stroke: 0.55pt + gray)
  })
]

跨层探针端口带层染色；关闭层路径时，FIRRTL 编译流程移除对应的验证逻辑。声明期核对接口中每个 `Probe` 的层标注（@sec-dv-protocol）；例化期再把生成器的实际 Probe 端口与已声明接口比对，失配时当场报错（@sec-generator-contract）。

#决策([层路径按前缀合并])[
  相同层路径合并为同一声明。不同探针源可以在同一层路径下使用不同协议。
] <dec-layer-merge>
