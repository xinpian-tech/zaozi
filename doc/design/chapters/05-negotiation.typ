#import "../lib.typ": *

= 协商算法 <ch-negotiation>

协商是一个纯函数：输入设计规格，输出协商结果或一组错误。本章把它拆成一列顺序执行的遍，规定每一遍的输入、输出与失败模式，然后给出参数传播的精确语义与协议参数的计算规则。

== 遍的流水线 <sec-passes>

#图([协商的遍。每一遍是纯函数；箭头是唯一的数据流。前五遍确定图的形状，中间四遍确定参数，后三遍规划端口、连线与层（@ch-hierarchy、#ref(<ch-verification>)详述），最终装配为协商结果。])[
  #syn-diagram(
    spacing: (5.5mm, 11mm),
    node((0, 0), [校验 \ #text(size: 8pt, fill: c-dim)[结构合法性]], name: <v>),
    node((1, 0), [弹性判向 \ #text(size: 8pt, fill: c-dim)[@sec-flex]], name: <f>),
    node((2, 0), [基数求解 \ #text(size: 8pt, fill: c-dim)[@sec-star-solving]], name: <s>),
    node((3, 0), [端口映射 \ #text(size: 8pt, fill: c-dim)[前缀和]], name: <pm>),
    node((4, 0), [瞬态消除], name: <ee>),
    node((0, 1), [下行传播], name: <pd>),
    node((1, 1), [上行传播], name: <pu>),
    node((2, 1), [边结算], name: <se>),
    node((3, 1), [协议参数], name: <pp>),
    node((0, 2), [打洞规划 \ #text(size: 8pt, fill: c-dim)[@ch-hierarchy]], name: <pu2>),
    node((1, 2), [连线计划 \ #text(size: 8pt, fill: c-dim)[@ch-hierarchy]], name: <wp>),
    node((2, 2), [层合并 \ #text(size: 8pt, fill: c-dim)[@ch-verification]], name: <lm>, shape: fletcher.shapes.rect),
    node((3.6, 2), [协商结果 \ `ResolvedDesign`], name: <r>, shape: fletcher.shapes.pill, fill: c-fill),
    edge(<v>, <f>, "-|>"),
    edge(<f>, <s>, "-|>"),
    edge(<s>, <pm>, "-|>"),
    edge(<pm>, <ee>, "-|>"),
    edge(<ee>, (4.7, 0), (4.7, 0.5), (-0.7, 0.5), (-0.7, 1), <pd>, "-|>"),
    edge(<pd>, <pu>, "-|>"),
    edge(<pu>, <se>, "-|>"),
    edge(<se>, <pp>, "-|>"),
    edge(<pp>, (4.7, 1), (4.7, 1.5), (-0.7, 1.5), (-0.7, 2), <pu2>, "-|>"),
    edge(<pu2>, <wp>, "-|>"),
    edge(<wp>, <lm>, "-|>"),
    edge(<lm>, <r>, "-|>"),
  )
]

*校验*在一切计算之前拦截结构错误：连接两端协议一致（类型系统已在构建期拦截绝大多数，此处是防御性复查）；节点角色与其连接侧向相容；以及最重要的——*协商图无环*。参数要沿边单向传播两遍，图必须是有向无环图；环报告与基数依赖环（C8）互相独立，各自呈现完整回路。

为什么必须无环？下行与上行传播（@sec-propagation）各是一次单遍扫描，良定义依赖参数流不折返；节点图一旦有向成环，参数就成了需要迭代求解的不动点，单遍算法与"错误可定位"双双失守。这条约束并*不*排斥物理上带环的结构：一条边承载的是完整的双向物理链路（协议接口两个方向的字段都有，@sec-protocol-interface），环形、网状互连的内部结构属于生成器内部，或整体归入一个枢纽节点；两个模块互为主从时是两对节点、两条方向相反的边——节点图依旧无环。真正被拒绝的只有*参数流自身的回路*，那是建模错误，不是拓扑需求。

*瞬态消除*把瞬态节点从图上抹去：它两侧的端口按索引一一对接，边被重定向为直连；此后任何遍都不再见到瞬态节点。恒等节点保留到连线计划，届时变成一条连线。

遍与遍之间是不可变数据。这带来两个直接可用的能力：任何一遍的输出都可以独立 dump 检查（@ch-tooling），任何一遍都可以在没有后续遍的情况下单元测试。

== 参数传播 <sec-propagation>

基数求解后，每个节点两侧的端口向量已经定形。下行传播沿图的拓扑序进行：访问一个节点时，它全部入侧端口的下行参数已经就绪，按角色变换产出它全部出侧端口的下行参数。

#table(
  columns: (auto, 1fr, 1fr),
  table.header([角色], [下行变换], [上行变换]),
  [源], [出侧端口 $i$ 的下行参数 $=$ 参数表第 $i$ 项。], [——（无入侧）],
  [汇], [——（无出侧）], [入侧端口 $i$ 的上行参数 $=$ 参数表第 $i$ 项。],
  [适配], [`dFn : Down => Down`，*逐端口独立*应用：出侧端口 $i$ 由入侧端口 $i$ 变换而来。], [`uFn : Up => Up`，逐端口反向。],
  [枢纽], [`dFn : Seq[Down] => Down`：全体入侧参数聚合为一个值，广播到每个出侧端口。], [`uFn : Seq[Up] => Up`：全体出侧的上行参数聚合，广播到每个入侧端口。],
  [比例], [`dFn : Seq[Down] => Seq[Down]`，*按份*应用：第 $j$ 份收取每条入侧连接区间中的第 $j$ 个端口（长度 $"uRatio"$），产出该份的出侧参数（长度 $"dRatio"$）。], [对称反向。],
)

上行传播按逆拓扑序对称进行。两遍结束后，每条边的两端各有一份就绪的参数：上游侧给出的 `Down` 与下游侧给出的 `Up`。

三条规则使传播保持可判定与可测试：

+ *适配变换不得读取相邻边。*`dFn` 的签名是单参数的——这不是简化而是承诺：适配的变换与它承载几条边无关，因此适配可以自由复用、缓存、并行求值。需要访问全体入边的计算属于枢纽。
+ *变换是纯函数。*与结算函数同样的三性质（@sec-protocol-object）：全函数、确定、不读环境。
+ *计数即契约。*框架在每次变换后核对产出数量（枢纽广播的份数、比例的 $"dRatio"$ 长度）；违约立即报错并指出节点与期望/实际数量，而不是任由错位的参数继续向下游传播。

#图([两遍传播。蓝色下行沿拓扑序（①→③），红色上行沿逆序（④→⑥）；两遍完成后每条边拥有一对参数，进入结算。])[
  #syn-diagram(
    spacing: (24mm, 9mm),
    node((0, 0), [源], name: <a>),
    node((1, 0), [适配], name: <b>),
    node((2, 0), [枢纽], name: <c>),
    node((3, 0), [汇], name: <d>),
    edge(<a>, <b>, "-|>", stroke: c-down, label: text(fill: c-down, size: 8pt)[① Down], label-side: left),
    edge(<b>, <c>, "-|>", stroke: c-down, label: text(fill: c-down, size: 8pt)[② Down], label-side: left),
    edge(<c>, <d>, "-|>", stroke: c-down, label: text(fill: c-down, size: 8pt)[③ Down], label-side: left),
    edge(<d>, <c>, "--|>", stroke: c-up, bend: 30deg, label: text(fill: c-up, size: 8pt)[④ Up]),
    edge(<c>, <b>, "--|>", stroke: c-up, bend: 30deg, label: text(fill: c-up, size: 8pt)[⑤ Up]),
    edge(<b>, <a>, "--|>", stroke: c-up, bend: 30deg, label: text(fill: c-up, size: 8pt)[⑥ Up]),
  )
]

== 边结算与协议参数 <sec-settle-pp>

*边结算*对每条边独立调用其协议的 `negotiate(down, up)`。所有参数冲突累积成列表一并报出，每条附带边的两端节点与连接书写位置。结算成功后，边参数向量按端口索引排列——从此"第几条边"与"哪个参数"永久对应。

*协议参数*是协商结果进入生成器的唯一通道。每个生成器模块声明一个纯函数：

```scala
def computeProtocolParam(view: EdgeView): ProtocolParam
```

#term[边视图][`EdgeView`]是该*模块*的只读视图：模块内每个节点 → 它的入侧与出侧边列表，每项含边参数、对端身份与连接位置，按端口索引排序。排序继承自声明序与基数解，因此协议参数的计算和其余一切同样确定。

#图([边视图。生成器模块 M 的两个节点各自的边（绿）被汇成只读视图，交给 `computeProtocolParam` 折算出协议参数。])[
  #syn-diagram(
    spacing: (17mm, 8mm),
    node((0.1, 0.35), [节点 a], name: <na>, shape: fletcher.shapes.circle),
    node((0.1, 1.15), [节点 b], name: <nb>, shape: fletcher.shapes.circle),
    node(enclose: (<na>, <nb>), stroke: c-hier, inset: 14pt, snap: false, name: <m>),
    node((0.1, -0.55), text(size: 8pt, fill: c-hier)[生成器模块 M], stroke: none),
    edge((-1.1, 0.2), <na>, "-|>", stroke: c-edge),
    edge((-1.1, 0.55), <na>, "-|>", stroke: c-edge),
    edge((-1.1, 1.15), <nb>, "-|>", stroke: c-edge),
    node((1.55, 0.75), [`EdgeView`], name: <ev>, fill: c-fill),
    edge(<m>, <ev>, "..>"),
    node((3.05, 0.75), [协议参数], name: <pp>),
    edge(<ev>, <pp>, "-|>", label: text(size: 7.5pt)[`computeProtocolParam`], label-side: left),
  )
]

#决策([协议参数的依赖界限是本模块])[
  `computeProtocolParam` 的可见范围仅限*本模块*节点的边，不含其他模块，也不含全图。理由：一旦允许跨模块读取，A 的参数便可依赖 B 的边、B 又依赖 C——这张依赖网不出现在图上，也不出现在任何声明中，只存在于各模块推导函数的调用链里。此时报错信息无法追溯参数值的来源：其推导链横跨任意多个模块的任意多层函数。经验上被这条界限排除的需求只有两类，且各有对应的表达方式：需要*全体接入者信息*的，应建模为枢纽的聚合变换（信息经由图传播至本模块的边）；需要*全局分配*的（如自动编址），属于构建期的规格前处理或协议库的图算法，不属于协商内核。
] <dec-pp-local>

#开放([显式多轮协商])[
  若未来出现既非枢纽聚合、又非规格前处理能表达的全局计算，候选方案是显式的多轮协商：第一轮的协商结果作为第二轮规格构造的输入。它保持每一轮内部的纯与界限，代价是使用者要自行保证轮间收敛。v1 不提供，等待真实用例。
] <open-multi-round>

== 错误累积 <sec-error-accumulation>

协商的返回类型是 `Either[Seq[NegotiationError], ResolvedDesign]`。累积的粒度按遍划分：一遍之内尽量收集同类错误（全部参数冲突、全部计数违约），遍与遍之间短路——基数未解出就不传播参数，传播未完成就不结算。短路是必要的：下游遍的输入根本不存在，强行继续只会制造海量的衍生噪音掩盖真因。

每个错误值携带四要素：类别编号（C1–C9、传播与结算类）、主体（节点/连接/模块的稳定标识）、全部相关书写位置（`SourceLocation`）、渲染为人类可读文本的上下文快照。错误的文本格式规范见@ch-tooling。

至此图定形、参数落定。剩下的问题是把结果安置进模块层次——下一章。
