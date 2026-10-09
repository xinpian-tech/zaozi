#import "../lib.typ": *

= 验证观测 <ch-verification>

验证环境需要观察设计内部的信号，例如供协同仿真比对的指令退休记录、供记分板检查的互连事务与各级断言。Syntheke 把观察关系拆成三件事：生成器在电路上公开哪些只读引用；每个引用属于哪类探针、带什么参数；谁来读取它们。信号以 FIRRTL 的探针（`Probe`，对内部信号的只读引用）形式引出，框架沿层次树为它们规划端口与连线（@ch-hierarchy）。探针不经 bind，也不参与协商。每个探针端口带一条 FIRRTL 层路径，用于控制对应验证逻辑的生成与移除（@req-verification、@sec-layers）。

== 公开探针端口与探针 <sec-dv-declarations>

生成器从完整参数决定它的#term[公开探针端口][public probe port]：每个端口是一个 Output 方向的 `Probe` 端口，带端口名、被引用数据的类型和层路径。zaozi 生成器在自己的探针接口中声明这些字段，框架从完整参数读出端口清单。公开探针端口的名字与本模块节点名不得相同。

#term[探针契约][probe contract]代表一类探针：一个 `ProbeContract[P]` 对象，按对象同一性比较。`P` 是#term[探针参数][probe parameter]类型，描述一个引用是什么，例如“第 0 个 hart 的指令退休记录，XLEN 为 32”。

#term[探针][probe]是生成器模块对一个公开探针端口的描述：`probe(selector)` 声明一个名为所在 val 的探针，返回 `ProbeNode[P]`。`selector` 属于一个契约，在完整参数算出后执行：它读取完整参数与公开端口清单，选出一个端口并给出 `P` 的值，或者说明本实例没有这个探针（例如未打开 trace）。探针名与节点名共用本模块的命名空间；它的稳定标识是 `ModuleNodeId`。

== 探针目录 <sec-dv-catalog>

协商结束、完整参数算出后，框架执行全部探针的 `selector`，得到设计的#term[探针目录][probe catalog]。目录中的每条记录是一个 `ResolvedProbe[P]`：探针标识、`P` 的值和它选中的公开探针端口。选中不存在的端口、或 `selector` 返回冲突，都在协商期报错。

一个设计#term[公开][publish]的目录包含本设计声明的探针，以及以 `probe.boundary` 从所例化设计转发来的探针。外层设计从例化结果读取它：`instance.probes.query(contract)` 取出该契约的全部探针。查询发生在外层的构建期，此时内层设计已经冻结，所以目录是完整的，与声明顺序无关。

#决策([按契约查询探针，不按名字或路径])[
  消费者按契约取得探针，不写模块路径或端口名字符串。探针参数说明引用的含义，硬件数据类型由生成器库随契约给出（@sec-dv-observation）。拓扑改变时，查询结果随之改变，消费者代码不变。
] <dec-dv-typed-query>

== 观测 <sec-dv-observation>

读取探针的生成器称为#term[观测者][observer]。观测者是外层设计中的普通生成器模块：它把查询得到的 `ResolvedProbe[P]` 作为用户参数，在完整参数中记录要读的探针。zaozi 侧的契约是 `ProbeBindingFor[P, T]`，它把探针参数 `P` 关联到硬件数据类型 `T`；`resolved.observe` 得到一个可放进完整参数的探针句柄。

观测者的生成器从完整参数报告它的#term[观测绑定][observation binding]：每个被读探针一条，记录探针标识、输入端口名与引用类型。输入端口是 Input 方向的数据端口，类型是被引用的数据类型，端口名是该探针在设计根上的 Dangle 端口名（@sec-port-naming）。框架核对每个绑定的探针属于本次协商的目录，端口名不与观测者的节点名或公开探针端口名相同。

观测绑定进入观测者的模块名（@sec-dedup）：读不同探针的两个实例是不同模块。

== 路由规则 <sec-dv-routing>

#图([探针的层次路由。两个公开探针端口（紫）沿层次树逐层上提，在设计根引出为探针端口；跨越的模块边界均产生带层路径标注的端口。])[
  #syn-diagram(
    spacing: (15mm, 7.5mm),
    node((0, 0.4), [源 α], name: <s1>, shape: fletcher.shapes.circle, stroke: c-dv),
    node((1.4, 0.4), [源 β], name: <s2>, shape: fletcher.shapes.circle, stroke: c-dv),
    node(enclose: (<s1>,), stroke: c-hier, inset: 10pt, snap: false, name: <m1>),
    node(enclose: (<s2>,), stroke: c-hier, inset: 10pt, snap: false, name: <m2>),
    node(enclose: (<m1>, <m2>), stroke: c-hier, inset: 22pt, snap: false, name: <mid>),
    node((2.9, -0.75), [根上的探针端口], name: <k>, stroke: c-dv, fill: rgb("#f6f1fd")),
    node(enclose: (<mid>, <k>), stroke: c-hier, inset: 34pt, snap: false),
    node((0, -0.35), text(size: 8pt, fill: c-hier)[核], stroke: none),
    node((1.4, -0.35), text(size: 8pt, fill: c-hier)[核], stroke: none),
    node((0.7, -0.95), text(size: 8pt, fill: c-hier)[簇], stroke: none),
    node((0.7, -1.62), text(size: 8pt, fill: c-hier)[设计根], stroke: none),
    edge(<s1>, <k>, "--|>", stroke: c-dv),
    edge(<s2>, <k>, "--|>", stroke: c-dv),
  )
]

探针路由复用设计侧的跨层端口规划（@sec-punch-planning），有两种路径：

+ *公开路径。*本设计目录中每个探针所选中的公开探针端口，都从源模块的父模块逐层上提到设计根（含根），每个模块边界一个 Output 方向的 `Probe` Dangle 端口，逐层 `ref.define` 传递。根上的端口就是这个设计对外的探针端口，外层设计例化它时从这里读取。
+ *观测路径。*对每个观测绑定，取探针源模块与观测者的最近公共祖先 $W$。源侧从源模块向上生成 `Probe` 端口直到 $W$；观测者侧从观测者向上生成数据输入端口直到 $W$；在 $W$ 内以 `ref.resolve` 读出引用，再连到观测者一侧。

两条路径都只经过结构模块，不改变任何生成器的端口。

#决策([探针公开到设计根，读取由观测者声明])[
  设计中每个被探针选中的公开端口都引出到设计根，构成设计对外的观测面；设计内不设收集端。读取是观测者自己的声明：它在完整参数中列出要读的探针，框架据此规划从源到观测者的路径。测试平台不是特殊模块，它就是例化被测设计的外层设计。
] <dec-dv-top>

== FIRRTL 层与探针移除 <sec-layers>

每个公开探针端口带一条#term[层路径][layer path]，例如 `verification.cosim` 或 `verification.assert`。框架把探针路径经过的每个模块的层路径合并为前缀树，生成器自带的内部层也并入它的全部祖先：

$ "layers"(w) = "前缀树并" {"layer"(s) : s in "经过" w "的探针端口"} union {"子树"(w) "中生成器的层"} $

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

跨层探针端口带层染色；关闭层路径时，FIRRTL 编译流程移除对应的验证逻辑。例化期把生成器的实际 Probe 端口与公开端口清单逐个比对，失配时当场报错（@dec-binding-check）。

== 不提供自动监视器 <sec-no-auto-monitor>

#决策([不提供自动监视器插入])[
  Syntheke 不提供 Diplomacy `NodeImp.monitor` 的对应机制。监视器拆成三件已有之物：观测面由生成器的公开探针端口承担；检查逻辑是外层设计中的观测者，按契约查询探针并声明读取；可移除性由 FIRRTL 层承担。代价是不声明探针的生成器的边无法被观测。
] <dec-no-auto-monitor>
