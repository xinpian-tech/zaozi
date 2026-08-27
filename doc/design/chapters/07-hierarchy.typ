#import "../lib.typ": *

= 跨层连接与模块生成 <ch-hierarchy>

已求解连接的两端可能位于层次树上相距任意远的两个模块（@req-hierarchy）。跨层连接需要在经过的模块边界生成端口并逐层连线。本章规定跨层端口规划、端口命名、结构模块发射与模块身份。

== 跨层端口规划 <sec-punch-planning>

每条已求解连接具有两个生成器模块端点。对于设计 bind，两端是源 outward 节点与目标 inward 节点所标识的生成器端口（@sec-interconnect-nodes）；对于验证 bind，两端分别是探针源生成器与探针汇生成器（@sec-dv-declarations）。层次路径由这两个硬件端点确定。

设连接 $c$ 的源端位于生成器模块 $A$，目标端位于生成器模块 $B$。当 $A != B$ 时，连线作用域 $W$ 取 $A$ 与 $B$ 的最近公共祖先（lowest common ancestor，`LCA(A, B)`）；当 $A = B$ 时，$W$ 取 $A$ 的父模块。结构模块 $W$ 发射两端之间的连接。设计连接的源端与目标端分别来自 bind 的源、目标节点；第 $i$ 条验证 bind 的源端是探针源顶层 Bundle，目标端是探针汇顶层 `sink` 中由 `sinkPaths(i)` 选定的 Bundle。规划规则：

+ 连接的两个端点是两端生成器的端口。设计连接使用两端的完整顶层 Bundle；验证连接按源接口的信号叶展开，每叶一条纯 `Probe` 路径（@sec-dv-routing）。为了让连接穿过中间的结构模块，框架在这些模块上生成端口，称为#term[Dangle 端口][dangle]：从 $A$ 的父模块开始逐层上行，在到达 $W$ 之前为每个模块边界生成一个 Output 方向的 Dangle 端口；从 $B$ 的父模块开始逐层上行，在到达 $W$ 之前为每个模块边界生成一个 Input 方向的 Dangle 端口。
+ 源端分支按 Bundle 整体连接“子实例 Output → 本层 Output”，直到 $W$；目标端分支按 Bundle 整体连接“本层 Input → 子实例 Input”，直到 $B$。在 $W$ 内部，两条分支的末端整体连接；若某端点模块的父模块就是 $W$，直接以该子实例的完整 Bundle 或选定 Bundle 作为末端。
+ 当 $A = B$ 时，$W$ 在同一子实例的两个端口之间生成整体连接；当 $A$ 与 $B$ 是兄弟模块时，$W$ 直接连接两个子实例端口。

根结构模块没有端口，设计是封闭电路。根模块充当测试平台：芯片顶层是它的子模块，端口由本节规则生成；DDR、PCIe 等外部模型是根下的生成器模块。

设计连接的沿途端口结构取自 `interfaceOf(edge)` 返回的非空 `ProtocolBundle`（@sec-protocol-interface）；验证连接取自对应的 `DVInterfaces.sources(i)`，它与目标端选定 Bundle 结构相同（@sec-dv-protocol）。框架将这两种结构翻译为 FIRRTL 类型。设计连接的源端路径使用 Output、目标端路径使用 Input，内部字段方向由 `Flipped` 确定；验证连接固定为源端 Output、目标端 Input，不含 `Flipped`。

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

跨层端口规划在协商期生成端口计划与连线计划。计划使用带种类的稳定来源标识：设计连接为 `Design(BindId)`，验证连接为 `Verification(DVBindId)`；每项同时记录对应 bind 的源码位置（`SourceLocation`）。例化期按计划发射端口和连线。

== 端口命名 <sec-port-naming>

框架生成的 Dangle 端口名在所属结构模块内唯一，并能还原产生该端口的连接标识和层次路径。生成器端点使用声明中的原始名称（@sec-generator-module）；Dangle 端口名称在规划期表示为字符串段序列，发射时再编码：

- *Dangle 端口基段。*设计边源端对应 `["node", 节点声明名, "out"]`，目标端对应 `["node", 节点声明名, "in"]`；探针源的第 $i$ 个信号叶对应 `["dv-source", 源名] ++ 叶路径段 ++ ["out"]`，叶路径段为字段名与 Vec 下标的数字段（@sec-attach、@sec-dv-declarations、@sec-dv-routing）。每个模块节点恰好对应一条边，节点声明名和方向足以确定基段。这些基段用于框架生成的 Dangle 端口，不改变生成器端点的原始名称。
- *父层端口名。*端点位于子实例 $c$ 时，父模块中的第一层 Dangle 端口使用 `["inst", c 的实例名]` 加端点名称段。继续向上一层时，若子实例内 Dangle 端口的名称段序列为 $P$，父模块中的对应端口使用 `["inst", c 的实例名] ++ P`。
- *字符串编码。*发射时把名称段序列编码为唯一且可逆的字符串，具体编码方案由实现决定。

例如：生成器模块 `l2` 内 outward 节点 `mem` 的实际端口名为 `mem`。`l2` 在结构模块 `soc` 中的实例名也为 `l2`；当该连接需要穿过 `soc` 边界时，`soc` 的 Dangle 端口名称段为 `["inst", "l2", "node", "mem", "out"]`。

框架要求同一模块内的实例名互异、节点声明名互异；`node`、`inst` 标签、方向段和可逆编码使不同的名称段序列得到不同端口名。命名冲突表示规格违反名称唯一性约束，必须直接报错。

声明名称（实例名、端点名、层段名）原样成为 FIRRTL 符号，形状限定为 `[A-Za-z_][A-Za-z0-9_]*`，在声明处检查。用户名称不含 `-` 与 `$`，因此可逆编码的转义字符与 `dv-source` 等框架段名和用户名称空间构造性隔离。

#决策([Dangle 端口名采用可逆路径编码])[
  框架生成的 Dangle 端口名完整编码实例路径、端点声明名与方向。名字长度随层深线性增长；每条连接的 Dangle 端口名与连线名由两端端点和层次路径独立计算。生成器端点仍使用声明中的原始名称。
] <dec-port-naming>

== 结构模块的发射 <sec-wrapper-emission>

结构模块（@sec-module-kinds）的电路内容由框架整体生成，由以下四类语句组成：

+ *端口*：跨层端口规划产生的全部端口，按协议接口翻译；
+ *子实例*：每个子模块一条实例语句；
+ *连线*：连线计划中属于本层的全部连接，Bundle 级整体连接；
+ *层声明*：子树探针所需的层集合（@ch-verification）。

协议转换逻辑由对应的生成器模块生成（@sec-protocol-object）。

== 模块身份与去重 <sec-dedup>

生成器模块的定义按链接键共享：同一（生成器名字，完整参数的规范化序列化）产生同一个模块名，dump 一次、链接一份（@sec-elaboration-flow）。完整参数规范化序列化：字段顺序固定，映射键排序，数值编码固定。

结构模块每实例发射一份模块定义，模块名是实例路径的可逆编码（@sec-port-naming 的编码方案），根为 `Top`。结构相同模块的网表级合并交给 FIRRTL 编译流程自带的去重，Syntheke 不维护结构键。
