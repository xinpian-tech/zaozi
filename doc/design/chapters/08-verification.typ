#import "../lib.typ": *

= 验证协议 <ch-verification>

验证环境需要观察设计深处的信号：流水线的架构状态供协同仿真比对、总线事务供记分板检查、断言散布在各级。直接从模块内部引出信号破坏封装；在顶层以层次路径字符串引用信号则脆弱且无法参与协商。Syntheke 的方案与设计侧一致：*探针也是图*——用验证协议的节点与连接声明，走同一套打洞机制（@req-verification），并全程带有 FIRRTL 层标注（层本身的能力见 @sec-layers 的职责划分）。

== 模型 <sec-dv-model>

- #term[探针源][DV source]：位于生成器模块，给出验证协议的下行声明（@sec-dv-protocol），携带到生成器*探针接口*字段的硬件绑定。生成器的探针接口（@sec-generator-contract）暴露只读引用，不是驱动线。
- #term[探针汇][DV sink]：位于层次树上方的某个模块——通常是顶层附近的验证壳。收齐全部到达的声明后，由协议的 `resolve` 聚合出边参数，据此确定汇端聚合接口的形状。

参数只有一股流：探针源（上游端）的下行声明，汇端不回传——被观察者不需要知道谁在观察它。此处需要区分两种"方向"：下行与上行相对*边*而言（@sec-three-param-kinds）；而探针边的*路由*沿层次树向上（@sec-dv-routing）。参数沿边下行、边本身向层次上方走线，两者互相独立，并不矛盾。

#图([探针的层次路由。两个深处的探针源（紫）沿层次树向上打洞，汇聚于顶层验证壳的探针汇。沿途每个边界出现带层路径标注的探针端口。])[
  #syn-diagram(
    spacing: (15mm, 7.5mm),
    node((0, 0.4), [源 α], name: <s1>, shape: fletcher.shapes.circle, stroke: c-dv),
    node((1.4, 0.4), [源 β], name: <s2>, shape: fletcher.shapes.circle, stroke: c-dv),
    node(enclose: (<s1>,), stroke: c-hier, inset: 10pt, snap: false, name: <m1>),
    node(enclose: (<s2>,), stroke: c-hier, inset: 10pt, snap: false, name: <m2>),
    node(enclose: (<m1>, <m2>), stroke: c-hier, inset: 22pt, snap: false, name: <mid>),
    node((2.9, -0.75), [探针汇], name: <k>, stroke: c-dv, fill: rgb("#f6f1fd")),
    node(enclose: (<mid>, <k>), stroke: c-hier, inset: 34pt, snap: false),
    node((0, -0.35), text(size: 8pt, fill: c-hier)[核], stroke: none),
    node((1.4, -0.35), text(size: 8pt, fill: c-hier)[核], stroke: none),
    node((0.7, -0.95), text(size: 8pt, fill: c-hier)[簇], stroke: none),
    node((0.7, -1.62), text(size: 8pt, fill: c-hier)[顶层], stroke: none),
    edge(<s1>, <k>, "--|>", stroke: c-dv),
    edge(<s2>, <k>, "--|>", stroke: c-dv),
  )
]

== 连接与路由规则 <sec-dv-routing>

#决策([探针连接必须逐条显式枚举])[
  验证观察点必须被*显式枚举*，不提供任何通配收集。设计侧的成员数量随附着增删而自动变化（@sec-attach），服务于集成的敏捷；验证的覆盖范围恰恰相反——"自动收集所有可用探针"式的通配会让验证环境的覆盖范围随设计漂移而无声变化，这在验证方法学上是缺陷不是便利。每条探针连接都是一条独立、可审计的显式声明。同构多核之类的批量场景不构成反例：构建期是普通宿主语言代码，对核列表循环生成 $N$ 条单连即可。循环与通配有本质区别——循环枚举的是设计者显式持有的列表，规格中记录的仍是 $N$ 条独立、可审计的连接；通配的匹配范围由框架的图搜索隐式决定，覆盖范围才会随设计漂移。
] <dec-dv-once>

#决策([探针汇必须是探针源的严格祖先])[
  兄弟模块之间的横向探针连接非法。理由有三：其一，沿层次树向上的路由让每条探针的走线路径唯一且无环，不需要任何布线决策；其二，一个模块有哪些探针端口只取决于其*内部*内容，与例化它的环境无关——同一个核，在被监听与不被监听的两颗芯片里仍是同一个模块，去重（@sec-dedup）不被观察者破坏；若允许横向连接，核的端口形状将随环境变化，模块复用随之不再成立；其三，探针汇集中于祖先模块，与验证环境的组织方式（顶层协同仿真、子系统级测试台）一致。需要横向观察时，把汇提升到公共祖先，再由汇端分发。
] <dec-dv-ancestor>

路由与打洞与设计侧共用机制（@sec-punch-planning），仅两点不同：路径必为直线（源到祖先，无 LCA 分叉）；端口带层路径标注——探针端口的类型是 `Probe(inner, layer)`（@sec-protocol-interface，其中 `layer` 即 @sec-layers 定义的层路径），方向永远向上。

== 层 <sec-layers>

在发布构建中整体移除探针硬件，是 FIRRTL 层机制自身提供的能力，不是 Syntheke 的功能。Syntheke 的职责只是把层的*声明*放置正确：每个探针源声明它所属的#term[层路径][layer path]——一条命名链，如 `verification.cosim`、`verification.assert`；层次树上每个模块的*层声明*由框架合并计算：

$ "layers"(w) = "前缀树并" {"layer"(s) : s in "子树"(w) "的全部探针端口"} $

#图([层的前缀树合并。子树里出现过 `verification.cosim` 与 `verification.assert.fatal` 两条层路径，模块的层声明是二者的前缀树并。])[
  #syn-canvas({
    import cetz.draw: *
    let tree(x0, nodes, edges) = {
      for (p, t) in nodes { content(p, text(size: 8.5pt, t)) }
      for (a, b) in edges { line(a, b, stroke: 0.55pt + gray) }
    }
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

关闭某条层路径后，该层的端口、连线与层内逻辑由 FIRRTL 编译流程整体移除，设计侧电路逐位不变——移除是 FIRRTL 的语义，此处不再展开。Syntheke 在其中承担且仅承担两件事：把探针端口与连线放进正确的层（打洞产物带层标注），以及核对生成器声明的层结构与探针源声明的层路径一致（生成器的层接口与探针源的层路径由同一参数推导，@sec-generator-contract；一致性在协商期检查）。

#决策([同路径合并，同路径异协议报错])[
  多个探针源声明相同层路径是常态（全芯片的 `cosim` 探针都在一条层里），前缀树并自然合并。但同一层路径下若出现*不同验证协议*的探针汇流到*同一个汇*，`resolve` 的输入将不再同质——这是声明冲突，协商期报错，指出两侧源的位置。不同协议应使用不同汇（可以共层）。
] <dec-layer-merge>

== 汇端形状 <sec-sink-shape>

汇端的聚合接口由 `resolve` 的结果经 `interfaceOf` 给出，典型形状是"每个到达的源一个子 bundle"，顺序继承打洞路径的确定性排序（子实例声明序 × 节点声明序 × 端口索引）。验证壳因此可以按稳定的名字索引每一路探针；增删一个源只增删它自己的那一路。

至此设计与验证两条通路都已闭合。最后一章交代工具面——协商产物如何被看见。
