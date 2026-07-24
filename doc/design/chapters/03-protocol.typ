#import "../lib.typ": *

= 协议抽象 <ch-protocol>

协商图上流动的一切参数都由#term[协议][protocol]定义。协议回答三个问题：沿边向下流的是什么、向上流的是什么、两者相遇时如何结算。本章给出协议对象的形状、协议接口的表示，以及参数的双层结构。

== 一条边上的三种参数 <sec-three-param-kinds>

回看#ref(<ch-motivation>)的三个例子：位宽、地址、标识。它们的共性是每条链路上都有*两个视角*——上游端描述自身的构成与将要发起的操作，下游端描述可接受的操作与所提供的资源。Syntheke 把这两个视角类型化为每条边上的两股流：

- #term[下行参数][downward parameter]（类型 `Down`）：沿边的方向流动，由上游一侧给出或变换而来。典型内容：发起者列表、各自的并发度、可能发出的操作种类。
- #term[上行参数][upward parameter]（类型 `Up`）：逆边的方向流动，由下游一侧给出或变换而来。典型内容：目的地地址集合、支持的操作、时序特性。

两股流在边上相遇，由协议的结算函数合成#term[边参数][edge parameter]（类型 `Edge`）——这条链路的最终参数，双方从此都以它为准。此后一切都从边参数导出：硬件接口的确切形状、协议检查器的配置、可视化标注，乃至下游模块的协议参数（@sec-two-layer-params）。

#图([一条边上的参数流。下行（蓝）与上行（红）各自给出参数，结算函数在边上合成最终的边参数（绿）。])[
  #syn-diagram(
    spacing: (34mm, 8mm),
    node((0, 0), [上游节点], name: <s>),
    node((1, 0), [下游节点], name: <k>),
    edge(<s>, <k>, "-|>", stroke: c-down, label: text(fill: c-down)[`Down` 下行], label-side: left),
    edge(<k>, <s>, "-|>", stroke: c-up, bend: 32deg, label: text(fill: c-up)[`Up` 上行], label-side: left),
    node((0.5, 1.45), text(fill: c-edge)[`Edge = negotiate(Down, Up)`], stroke: c-edge, fill: rgb("#f2f9f4"), name: <e>),
    edge(<e>, (0.5, 0), "..>", stroke: c-edge),
  )
]

== 协议对象 <sec-protocol-object>

协议是一个普通对象，以类型成员携带三种参数类型：

```scala
trait Protocol:
  type Down   // 下行参数
  type Up     // 上行参数
  type Edge   // 结算后的边参数

  def negotiate(d: Down, u: Up): Either[TermViolation, Edge]
  def interfaceOf(e: Edge): ProtocolInterface   // 协议接口
  def name: String
  def render(e: Edge): RenderedEdge = ...       // 可视化元数据
```

选择*类型成员*而非类型参数是刻意的：边上的全部类型信息由一个协议值一次性给出，节点、连接、算法的签名只需携带 `P <: Protocol` 一个参数，而不是把 `Down/Up/Edge` 乃至两侧变体展开成一长串类型参数、在每个签名中重复出现。协议库作者定义一个对象；使用者引用一个名字。

`negotiate` 承担的职责对应三条必须遵守的性质：

+ *全函数。*对参数不兼容的组合（下游不支持上游要发的操作、地址集越界……）返回描述性的参数冲突，而非抛出或生成错误硬件。协商器把所有边的冲突累积后一并报告（R4）。
+ *确定性。*只读两个入参，不读任何环境——没有全局配置、没有时间戳、没有随机性。同一规格永远结算出同一设计。
+ *逐边局部。*每次结算只看本边的一对参数。所有需要"看见多条边"的计算——聚合、分割、取齐——都发生在参数*传播*阶段，由节点角色的变换函数表达（@ch-topology、@ch-negotiation）。这条分工线让结算函数保持简单，也让传播算法无需理解任何协议的内部结构。

#不变量[一条连接的两端服从同一协议，因此每条边恰有一个 `Down`、一个 `Up`、一个 `Edge`，与恰好一次结算。]

跨协议的转换如何表达？由一个显式的#term[混合适配节点][mixed adapter node]：它的入侧服从协议 A、出侧服从协议 B，携带跨协议的参数变换 `A.Down => B.Down` 与 `B.Up => A.Up`。转换的硬件实现则是一个普通的生成器模块。换言之，协议转换是图上的一等节点，而不是连接的隐藏属性——转换发生的位置在图上直接可见。

== 协议接口 <sec-protocol-interface>

协议还必须回答：给定一条已结算的边，这条链路的硬件长什么样？答案用一个纯数据的形状描述——#term[协议接口][protocol interface]：

```scala
enum ProtocolInterface:
  case Bundle(fields: Vector[Field])   // Field = (name, flip, ProtocolInterface)
  case Vec(size: Int, element: ProtocolInterface)
  case UInt(width: Int)
  case SInt(width: Int)
  case Bool, Clock, Reset
  case Probe(inner: ProtocolInterface, layer: LayerPath)   // @ch-verification
```

协议接口有两个消费者，也只有两个：

+ *结构模块的端口发射。*结构模块没有生成器，它的端口完全由框架从穿越它的边推导（@ch-hierarchy）；推导的依据就是每条边的 `interfaceOf(edge)`，翻译为 FIRRTL 类型后发射。
+ *硬件绑定的校验。*生成器模块自带硬件接口（由生成器声明），节点绑定到接口字段上；例化期框架把字段的实际类型与协议接口逐层比对，失配即报错并指出节点与连接位置（@ch-hardware）。

#决策([协议接口必选])[
  每个协议必须实现 `interfaceOf`。没有它，跨层次打洞与绑定校验都无从谈起；允许缺省只会把失败推迟到例化期更晦涩的位置。确有协议不落任何导线——例如只交换地址映射、时钟约定这类纯信息的协商——返回空 `Bundle` 即可。
] <dec-pi-required>

注意协议接口刻意*不是*硬件类型：它是可序列化的普通数据，属于协商域；到硬件类型的翻译发生在例化期的边界上，方向单一。协商域因此完全不依赖硬件构造库。

== 参数的双层结构 <sec-two-layer-params>

一个生成器最终消费的参数从两个来源合并而来：

- #term[用户参数][user parameter]（`UserParam`）：构建期由设计者写下的意图——容量、关联度、基地址、功能开关。它在协商开始前就完全确定。
- #term[协议参数][protocol parameter]（`ProtocolParam`）：协商期由框架计算的环境事实——对端的集合、每条边的最终参数。设计者*声明如何从边推导它*（@sec-settle-pp 的 `computeProtocolParam`），但不亲手给值。

两者由生成器模块声明的合成函数合并为#term[完整参数][full parameter]（`FullParam`），即 @sec-serialization-boundary 里唯一穿越序列化边界的对象：

#图([参数的双层合并。用户参数写于构建期，协议参数算于协商期，二者在例化期合并为交给生成器的完整参数。])[
  #syn-canvas({
    import cetz.draw: *
    rect((0, 2.1), (3.6, 3.0), stroke: 0.7pt, radius: 0.08, fill: rgb("#f0f6fd"))
    content((1.8, 2.55), [用户参数 \ #text(size: 8pt, fill: c-dim)[构建期 · 人写]])
    rect((0, 0.6), (3.6, 1.5), stroke: 0.7pt, radius: 0.08, fill: rgb("#f2f9f4"))
    content((1.8, 1.05), [协议参数 \ #text(size: 8pt, fill: c-dim)[协商期 · 算出]])
    line((3.75, 2.55), (5.1, 1.95), mark: (end: ">"))
    line((3.75, 1.05), (5.1, 1.65), mark: (end: ">"))
    content((4.35, 2.65), text(size: 8pt)[`combine`])
    rect((5.2, 1.25), (8.9, 2.35), stroke: 1pt, radius: 0.08)
    content((7.05, 1.8), [完整参数 \ #text(size: 8pt, fill: c-dim)[例化期 · 可序列化]])
    line((9.05, 1.8), (10.35, 1.8), mark: (end: ">"), stroke: 1.1pt + c-edge)
    content((11.15, 1.8), [生成器])
  })
]

这个双层结构是#ref(<ch-motivation>)顺序死锁的解法：旧困境"构造 A 需要知道 B 的属性"被拆解为——构建期只写用户参数（无相互依赖），协商期由图算出所有环境事实，例化期生成器一次性拿到二者之和。生成器内部再不需要任何"先猜后断言"。

同一生成器、同一完整参数，必然生成同一模块；zaozi 按参数缓存并去重模块。协议参数参与合并意味着：两个用户参数相同、但协商环境不同的实例，其完整参数不同，自然生成两个不同的模块——去重的正确性不需要任何额外机制维护。

== 验证协议 <sec-dv-protocol>

验证探针的参数流动是单向的：探针源声明"我暴露什么"，向上汇聚到某个祖先处的探针汇，没有下行的对偶。为此设一个更简单的协议形态：

```scala
trait DVProtocol:
  type Up     // 探针源声明的参数
  type Edge   // 汇端聚合后的结果

  def resolve(ups: Seq[Up]): Either[TermViolation, Edge]
  def interfaceOf(e: Edge): ProtocolInterface
```

`resolve` 在汇端一次性看到所有到达的上行参数并聚合——与设计协议逐边结算不同，因为探针汇的职责本来就是"收集全体"。`DVProtocol` 与 `Protocol` 是并列的两个契约，*没有*子类型关系：探针没有下行流，强行共用一套类型只会使一半类型成员失去意义。一个协议库要同时服务设计连接与验证探针时，分别给出两个对象即可（内部尽可复用共同的参数定义）。验证协议的连接规则、层机制与打洞细节见@ch-verification。

至此，参数的静态结构已经完整：协议给出类型与结算，接口给出形状，双层参数给出来源与去向。下一章处理另一半问题——图本身的形状如何确定：一条连接究竟代表几条边。
