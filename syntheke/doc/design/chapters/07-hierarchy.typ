#import "../lib.typ": *

= 跨层连接与模块生成 <ch-hierarchy>

已求解连接的两端可能位于层次树上相距任意远的两个模块（@req-hierarchy）。跨层连接需要在经过的模块边界生成端口并逐层连线。本章规定跨层端口规划、设计边界上的端口、端口命名、结构模块发射与模块身份。

== 跨层端口规划 <sec-punch-planning>

设计 bind 的两端是源 outward 节点与目标 inward 节点所标识的生成器端口（@sec-interconnect-nodes），层次路径由这两个硬件端点确定。探针路径的规则见 @sec-dv-routing，它复用本节的分支规划。

设连接 $c$ 的源端位于生成器模块 $A$，目标端位于生成器模块 $B$。当 $A != B$ 时，连线作用域 $W$ 取 $A$ 与 $B$ 的最近公共祖先（lowest common ancestor，`LCA(A, B)`）；当 $A = B$ 时，$W$ 取 $A$ 的父模块。结构模块 $W$ 发射两端之间的连接。设计连接的源端与目标端分别来自 bind 的源、目标节点。规划规则：

+ 连接的两个端点是两端生成器的端口，使用两端的完整顶层 Bundle。为了让连接穿过中间的结构模块，框架在这些模块上生成端口，称为#term[Dangle 端口][dangle]：从 $A$ 的父模块开始逐层上行，在到达 $W$ 之前为每个模块边界生成一个 Output 方向的 Dangle 端口；从 $B$ 的父模块开始逐层上行，在到达 $W$ 之前为每个模块边界生成一个 Input 方向的 Dangle 端口。
+ 源端分支按 Bundle 整体连接“子实例 Output → 本层 Output”，直到 $W$；目标端分支按 Bundle 整体连接“本层 Input → 子实例 Input”，直到 $B$。在 $W$ 内部，两条分支的末端整体连接；若某端点模块的父模块就是 $W$，直接以该子实例的完整 Bundle 或选定 Bundle 作为末端。
+ 当 $A = B$ 时，$W$ 在同一子实例的两个端口之间生成整体连接；当 $A$ 与 $B$ 是兄弟模块时，$W$ 直接连接两个子实例端口。

设计连接的沿途端口结构取自 `interface(edge)` 返回的 `ProtocolBundle`（@sec-protocol-interface）；探针路径取自公开探针端口的 `Probe` 类型（@sec-dv-declarations）。框架将这两种结构翻译为 FIRRTL 类型。设计连接的源端路径使用 Output、目标端路径使用 Input，内部字段方向由 `Flipped` 确定。

#图([设计连接的跨层端口生成。a、b 是端点模块已有的模块节点；其严格祖先 M1、M2 分别生成 Output、Input 方向的 Dangle 端口，LCA 在顶层连接两条分支。])[
  #syn-diagram(
    spacing: (16mm, 8mm),
    node((0, 0), [节点 a], name: <a>, shape: fletcher.shapes.circle),
    node((1, 0), [▸], name: <p1>, inset: 2.5pt, fill: rgb("#fdf3d7")),
    node((2, 0), [▸], name: <p2>, inset: 2.5pt, fill: rgb("#fdf3d7")),
    node((3, 0), [节点 b], name: <b>, shape: fletcher.shapes.circle),
    node(enclose: (<a>,), stroke: c-hier, inset: 7pt, snap: false, name: <ma>),
    node(enclose: (<b>,), stroke: c-hier, inset: 7pt, snap: false, name: <mb>),
    node(enclose: (<ma>, <p1>), stroke: c-hier, inset: 11pt, snap: false, name: <m1>),
    node(enclose: (<p2>, <mb>), stroke: c-hier, inset: 11pt, snap: false, name: <m2>),
    node(enclose: (<m1>, <m2>), stroke: c-hier, inset: 24pt, snap: false),
    node((0, -0.48), text(size: 7pt, fill: c-hier)[模块 A], stroke: none),
    node((3, -0.48), text(size: 7pt, fill: c-hier)[模块 B], stroke: none),
    node((0.5, -0.9), text(size: 8pt, fill: c-hier)[模块 M1], stroke: none),
    node((2.5, -0.9), text(size: 8pt, fill: c-hier)[模块 M2], stroke: none),
    node((1.5, -1.48), text(size: 8pt, fill: c-hier)[顶层（LCA）], stroke: none),
    edge(<a>, <p1>, "-|>", stroke: c-edge, label: text(size: 8pt)[M1 内连线], label-side: left),
    edge(<p1>, <p2>, "-|>", stroke: c-edge, label: text(size: 8pt)[顶层连线], label-side: left),
    edge(<p2>, <b>, "-|>", stroke: c-edge, label: text(size: 8pt)[M2 内连线], label-side: left),
  )
]

跨层端口规划在协商之后、装配 `ResolvedDesign` 之前进行，产出端口计划与连线计划。计划使用带种类的来源标识：设计连接为 `Design(BindId)`；探针的源侧路径为 `Verification`，观测者侧路径为 `Observation`，在 $W$ 内读出引用的一条连线为 `ProbeRead`，后三者都以探针源端口的 `ModuleNodeId` 标识。每项同时记录对应声明的源码位置。例化期按计划发射端口和连线。

== 设计边界上的端口 <sec-boundary-ports>

设计根的端口只来自两处：设计边界与探针公开路径（@sec-design-boundary、@sec-dv-routing）。

本设计的边界由根下的边界模块代表外侧。一条连到边界模块的 bind，规划时不连到边界模块，而是从内侧端点一路上行到设计根：源侧生成 Output 分支、目标侧生成 Input 分支，在根上生成一个以边界名命名的端口，再把分支末端连到它。边界模块本身不产生硬件。

外层设计例化一个冻结的设计时，所例化设计的边界模块代表它的实例。连到其上某个节点的 bind，以该实例上同名的根端口为端点，按普通规则规划。规划结果不得在冻结设计内部增加端口或改变连线，否则报错：冻结设计的电路已经单独生成（@sec-elaboration-flow）。

== 端口命名 <sec-port-naming>

框架生成的 Dangle 端口名在所属结构模块内唯一，并能还原产生该端口的连接标识和层次路径。生成器端点使用声明中的原始名称（@sec-generator-module）；Dangle 端口名称在规划期表示为字符串段序列，发射时再编码：

- *Dangle 端口基段。*设计边源端对应 `["node", 节点声明名, "out"]`，目标端对应 `["node", 节点声明名, "in"]`；探针源侧对应 `["probe", 公开探针端口名, "out"]`，观测者侧对应 `["observation", 观测输入端口名]`（@sec-attach、@sec-dv-declarations、@sec-dv-routing）。每个模块节点恰好对应一条边，节点声明名和方向足以确定基段。这些基段用于框架生成的 Dangle 端口，不改变生成器端点的原始名称。
- *父层端口名。*端点位于子实例 $c$ 时，父模块中的第一层 Dangle 端口使用 `["inst", c 的实例名]` 加端点名称段。继续向上一层时，若子实例内 Dangle 端口的名称段序列为 $P$，父模块中的对应端口使用 `["inst", c 的实例名] ++ P`。
- *字符串编码。*发射时每段先转义——`$` 写作 `$$`，`_` 写作 `$u`，`-` 写作 `$m`——再以 `_` 连接。转义后段内不含单独的 `_`，所以编码可逆。生成器端口与设计边界端口使用原始名称，不编码。

例如：生成器模块 `l2` 内 outward 节点 `mem` 的实际端口名为 `mem`。`l2` 在结构模块 `soc` 中的实例名也为 `l2`；当该连接需要穿过 `soc` 边界时，`soc` 的 Dangle 端口名称段为 `["inst", "l2", "node", "mem", "out"]`，编码为 `inst_l2_node_mem_out`。

框架要求同一模块内的实例名互异、节点声明名互异；`node`、`inst` 标签、方向段和可逆编码使不同的名称段序列得到不同端口名。命名冲突表示规格违反名称唯一性约束，必须直接报错。

声明名称（实例名、节点名、探针名、边界名、层段名）原样成为 FIRRTL 符号，形状限定为 `[A-Za-z_][A-Za-z0-9_]*`，在声明处检查。用户名称不含 `$`，因此转义后的名字与用户名称空间构造性隔离。

#决策([Dangle 端口名采用可逆路径编码])[
  框架生成的 Dangle 端口名完整编码实例路径、端点声明名与方向。名字长度随层深线性增长；每条连接的 Dangle 端口名与连线名由两端端点和层次路径独立计算。生成器端点仍使用声明中的原始名称。
] <dec-port-naming>

== 结构模块的发射 <sec-wrapper-emission>

结构模块（@sec-module-kinds）的电路内容由框架整体生成，由以下四类语句组成：

+ *端口*：跨层端口规划产生的全部端口，按协议接口翻译；
+ *子实例*：每个子模块一条实例语句；
+ *连线*：连线计划中属于本层的全部连接，Bundle 级整体连接；
+ *层声明*：经过本模块的探针所需的层，以及子树中生成器与所例化设计的层（@sec-layers）。

协议转换逻辑由对应的生成器模块生成（@sec-protocol-object）。

== 模块身份与去重 <sec-dedup>

生成器模块的定义按链接键共享：同一（生成器名字，完整参数的规范化序列化）产生同一个模块名，dump 一次、链接一份（@sec-elaboration-flow）。模块名是生成器名字加上述内容 SHA-256 的前 8 字节十六进制；观测者的观测绑定一并计入（@sec-dv-observation）。完整参数规范化序列化：JSON 对象键排序，数值编码固定。

结构模块每实例发射一份模块定义，模块名在声明处显式给出；根的模块名是 `Design` 的参数。结构相同模块的网表级合并交给 FIRRTL 编译流程自带的去重，Syntheke 不维护结构键。

#决策([结构模块的模块名显式声明])[
  生成器模块的模块名由链接键决定，那是模块本身的身份；结构模块没有完整参数可供取名，它的内容就是模块体组合出的东西，只有作者知道该叫什么。模块名因此是 `wrapper` 的显式参数，实例名仍取自绑定它的 val。同一设计中两个结构模块不得取同一模块名，根同样参与该检查（@sec-structural-check）；结构模块名也不得与本设计某个生成器模块发射的模块名相同，该检查在例化期生成器模块名确定后进行（@sec-elaboration-flow）。
] <dec-wrapper-module-name>
