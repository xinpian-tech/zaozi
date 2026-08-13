#import "../lib.typ": *

= 协议抽象 <ch-protocol>

本章定义设计协议的 `Down`、`Up`、`Edge` 类型、逐边求解和硬件接口。模块内部的参数传播见第 2 章和第 5 章。

== 边上传播的参数与求解结果 <sec-three-param-kinds>

每条设计 bind 都有源节点与目标节点。bind 方向规定 `Down` 的传播方向：

- #term[下行参数][downward parameter]（类型 `Down`）：`negotiate` 的第一项输入，沿 bind 方向从源节点传向目标节点。
- #term[上行参数][upward parameter]（类型 `Up`）：`negotiate` 的第二项输入，逆 bind 方向从目标节点传向源节点。

bind 与模块内部参数依赖共同形成 `Down` 参数依赖 DAG；每条内部依赖都从输入节点指向输出节点。反转这些依赖得到 `Up` 参数依赖 DAG。协商器按拓扑序计算各输出节点的 `dFn`，再按反向拓扑序计算各输入节点的 `uFn`。

传播的初始值由参数 DAG 的边界节点提供。以 AXI 内存互连为例，无前驱依赖的输出节点可以声明要求可达的地址范围、本地事务 ID 位宽、操作集合与数据位宽能力；无后继依赖的输入节点可以声明服务地址区域、支持的操作、数据位宽能力与可接受的下游 ID 位宽。中间模块的端口参数函数继续变换这些值。以 CHI 类协议为例，同一机制会传播节点编号、每个请求节点的事务标签空间、转发事务携带的原始请求者身份以及数据缓冲标识能力。常见协议的传播方向与边界声明如下：

#table(
  columns: (auto, auto, 1fr, 1fr),
  table.header([应用场景], [源 → 目标], [边界输出节点的初始 `Down`], [边界输入节点的初始 `Up`]),
  [内存互连], [发起者 → 响应者], [要求可达的地址范围、事务身份需求、将发出的操作种类、数据位宽能力], [供给的地址集合、支持的操作、数据位宽能力、可接受的事务身份能力],
  [中断], [设备 → 中断控制器], [供出的中断线数量与触发语义], [可汇聚的线数、支持的触发类型、编号空间],
  [时钟], [时钟源 → 时钟接收端], [可产生的频率集合、时钟间的同源关系], [所需频率范围、抖动与关系约束],
  [复位], [复位源 → 被复位模块], [复位类型（同步或异步）、极性与施加时序], [可接受的类型、需要的保持拍数],
  [电源], [供电端 → 负载模块], [电压档位、可供预算、可用的电源状态], [功耗需求、状态转换需求],
  [Debug], [Debug 控制器 → 处理器核], [访问机制与可寻址范围], [断点与触发器数量、编号需求],
  [Trace], [处理器核 → Trace 汇聚器], [Trace 格式与源标识范围], [缓冲深度、可分配的端口编号],
  [MBIST], [存储宏 → MBIST 控制器], [存储几何参数与测试接口形态], [可调度的接口数、支持的测试算法],
)

两遍传播结束后，每条 bind 恰好得到一项 `Down` 和一项 `Up`。`negotiate` 将二者合成为#term[边参数][edge parameter]（类型 `Edge`）；`interfaceOf(edge)` 生成该边的硬件接口，`render(edge)` 生成可视化元数据。边的 `Down`、`Up`、`Edge` 同时作为两端节点的唯一已求解连接进入相应生成器模块的 `EdgeView`（@sec-two-layer-params、@sec-settle-pp）。

#图([一条边的求解输入。`Down`（蓝）沿 bind 方向传递，`Up`（红）沿相反方向传递；`negotiate` 将二者合成 `Edge`（绿）。])[
  #syn-diagram(
    spacing: (34mm, 8mm),
    node((0, 0), [源节点], name: <s>),
    node((1, 0), [目标节点], name: <k>),
    edge(<s>, <k>, "-|>", stroke: c-down, label: text(fill: c-down)[`Down` 下行], label-side: left),
    edge(<k>, <s>, "-|>", stroke: c-up, bend: 32deg, label: text(fill: c-up)[`Up` 上行], label-side: left),
    node((0.5, 1.45), text(fill: c-edge)[边参数由双向输入合成], stroke: c-edge, fill: rgb("#f2f9f4"), name: <e>),
    edge(<e>, (0.5, 0), "..>", stroke: c-edge),
  )
]

== 协议对象 <sec-protocol-object>

协议对象定义一条边上的三种关联类型：`Down`、`Up` 与 `Edge`。`Codec[A]` 提供类型 `A` 的 schema、规范化编码与解码。`ProtocolBundle` 是协议接口的非空顶层 Bundle 描述，字段结构见 @sec-protocol-interface。模块端口参数函数发现的传播冲突由 `dFn` 或 `uFn` 返回，单边约束冲突由 `negotiate` 返回；第 5 章把它们连同主体标识和源码位置包装为 `NegotiationError`。

协议对象必须给出协议标识、下行参数、上行参数、边参数、逐边求解函数、接口描述函数、三种参数的 codec 和可视化渲染函数。协议标识由协议种类、名称和版本组成；可视化渲染结果由显示标签和一组属性构成。

`Down`、`Up` 与 `Edge` 关联到同一个协议值。给定协议值 `p`，`p.negotiate` 的参数类型是 `p.Down` 与 `p.Up`，成功结果类型是 `p.Edge`。bind 的源节点输出协议与目标节点输入协议均为 `p`，因此该边上的参数和求解调用共享同一组类型；未经显式转换的跨协议连接表现为类型错误。`Protocol.id.kind` 对设计协议固定为 `Design`。

`DesignBuilder` 维护一份协议注册表，并在 `DesignSpec` 固化时保存其不可变副本。一个 `ProtocolId` 在同一设计中只对应一个协议对象；同一对象可以被多处引用，不同对象声明相同 `ProtocolId` 则在结构校验中报告标识冲突。模块节点使用注册表中与该 `ProtocolId` 对应的同一个协议对象；兼容性按 `ProtocolId` 判断，关联类型调用使用注册表保存的该对象。

双向传播完成后，框架按 bind 声明顺序为每条边调用一次 `negotiate`。参数兼容时返回 `Right(Edge)`；参数冲突时返回 `Left(TermViolation)`。协商器为失败结果补充相关节点、bind 与模块的源码位置（@ch-interconnect、@ch-negotiation）。

`ProtocolId`、`Down`、`Up` 与 `Edge` 均不可变、可序列化。相应 `Codec` 用于在工具文件中编码、解码协议数据。读取工具文件时，调用方提供包含相应 `ProtocolId` 条目的注册表。`ProtocolId` 显式包含协议种类、名称与版本；任何会改变 `negotiate`、接口、渲染结果或 codec schema 的变更都必须更新版本。

`RenderedValue` 是可序列化的可视化数据。同一 `RenderedValue` 内的属性名唯一，属性按名称排序后编码。

跨协议转换由显式的#term[协议转换模块][protocol converter]表达。该模块声明一个协议 A 的输入节点和一个协议 B 的输出节点，并在二者之间声明参数依赖：输出节点的 `dFn` 执行 `A.Down => B.Down`，输入节点的 `uFn` 执行 `B.Up => A.Up`。两侧 bind 分别按协议 A 与协议 B 调用 `negotiate`；参数转换与硬件转换位于同一个生成器模块。

== 协议接口 <sec-protocol-interface>

每条已求解的设计边都对应一个实际硬件接口。顶层接口由 `ProtocolBundle` 表示，字段类型由 `ProtocolInterface` 递归描述。接口描述支持 Bundle、定长 Vec、无符号整数、有符号整数、布尔、时钟、复位和带层路径的探针字段。每个字段记录名称、方向翻转标记和内部类型。

`LayerPath` 是从 FIRRTL 层根开始的非空名称序列，例如 `verification.cosim` 对应 `["verification", "cosim"]`。

每个 Bundle 层级的字段名必须唯一；`NonEmptyVector` 保证每个 Bundle 至少有一个字段，`NonEmptyString` 保证字段名至少包含一个字符，`PosInt` 保证 Vec 长度与整数位宽均为正数。

协议接口用于两项工作：

+ *结构模块的端口发射。*框架从穿越结构模块的边推导端口（@ch-hierarchy）；推导的依据是每条边的 `interfaceOf(edge)`，翻译为 FIRRTL 类型后发射。
+ *端口结构的校验。*每条已求解边在两端生成器上各对应一个端口；例化期框架把端口的实际类型与协议接口逐层比对，失配即报错并指出节点与 bind（@ch-hardware）。

#决策([每个设计协议必须实现 `interfaceOf`])[
  每个设计协议（`Protocol`）必须实现 `interfaceOf`，为每个成功求解的 `Edge` 返回一个 `ProtocolBundle`。跨层端口与生成器端口校验均以这份结构为准。
] <dec-pi-required>

`ProtocolInterface` 是可序列化数据。协商期处理该数据，例化期将其翻译为 FIRRTL 类型。

设计协议的接口由 `Bundle`、`Vec`、`UInt`、`SInt`、`Bool`、`Clock` 与 `Reset` 构成。验证协议的接口以 `Probe` 包装每个信号叶，并在 `Probe` 中记录 `LayerPath`。

`ProtocolBundle` 描述源端视角的字段结构。框架为源模块端口赋予 Output 根方向，为目标模块端口赋予 Input 根方向；`flip = false` 跟随根方向，`flip = true` 取反。字段顺序是接口结构的一部分。

== 参数的双层结构 <sec-two-layer-params>

一个生成器最终使用的参数从两个来源合并而来：

- #term[用户参数][user parameter]（`UserParam`）：构建期声明的容量、关联度、基地址与功能开关。它在协商开始前就完全确定。
- #term[协议参数][protocol parameter]（`ProtocolParam`）：`computeProtocolParam` 在协商期从该生成器模块的只读求解结果计算得到的生成器自有参数（@sec-settle-pp）。

协商期调用生成器模块声明的合成函数，将两者合并为该模块的 `FullParam` 并存入 `ResolvedDesign`。完整参数穿越 @sec-serialization-boundary 定义的序列化边界：

#图([参数的双层合并。用户参数写于构建期，协议参数算于协商期，二者在协商期合并为完整参数，并在例化期交给生成器。])[
  #syn-canvas({
    import cetz.draw: *
    rect((0, 2.1), (3.6, 3.0), stroke: 0.7pt, radius: 0.08, fill: rgb("#f0f6fd"))
    content((1.8, 2.55), [用户参数 \ #text(size: 8pt, fill: c-dim)[构建期声明]])
    rect((0, 0.6), (3.6, 1.5), stroke: 0.7pt, radius: 0.08, fill: rgb("#f2f9f4"))
    content((1.8, 1.05), [协议参数 \ #text(size: 8pt, fill: c-dim)[协商期生成]])
    line((3.75, 2.55), (5.1, 1.95), mark: (end: ">"))
    line((3.75, 1.05), (5.1, 1.65), mark: (end: ">"))
    content((4.35, 2.65), text(size: 8pt)[`combine`])
    rect((5.2, 1.25), (8.9, 2.35), stroke: 1pt, radius: 0.08)
    content((7.05, 1.8), [完整参数 \ #text(size: 8pt, fill: c-dim)[例化期输入 · 可序列化]])
    line((9.05, 1.8), (10.35, 1.8), mark: (end: ">"), stroke: 1.1pt + c-edge)
    content((11.15, 1.8), [生成器])
  })
]

zaozi 以 `GeneratorId` 与完整参数的规范化序列化作为模块缓存键。用户参数相同而协议参数不同的实例具有不同缓存键，并分别生成模块定义。

== 验证协议 <sec-dv-protocol>

验证协议规定探针源为上游、探针汇为下游。其参数契约由探针源的 `Down` 和汇端聚合后的 `Edge` 组成；探针汇通过 `resolve` 聚合全部声明并生成 `Edge`。求解结果同时给出各探针源沿途使用的接口、汇端聚合接口，以及每个源接口在汇端接口中的路径。路径由字段选择和 Vec 索引组成。

`NonNegativeInt` 表示大于或等于零的整数。

`resolve` 的输入按探针 bind 声明顺序排列，且每个探针汇至少连接一个源。框架以相同顺序把各探针源声明的 `LayerPath` 传给 `interfacesOf`。返回值中的 `sources` 与输入一一对应；`sink` 是汇端接口；`sinkPaths(i)` 在 `sink` 中选择与 `sources(i)` 结构完全相同的 Bundle。空路径选择 `sink` 根 Bundle；非空路径用 `Field` 进入具名字段、用 `Index` 进入 Vec 元素，并且最后一段必须落在 Bundle。所有路径必须有效、互异且互不重叠，其选中 Bundle 的信号叶必须精确覆盖 `sink` 的全部信号叶。

验证连接是从源到汇的单向观测。`sources` 与 `sink` 中的每个信号叶都必须是 `Probe`，所有 `flip` 必须为 `false`；`sources(i)` 及 `sinkPaths(i)` 选中的汇端子树中，每个 `Probe` 的 `LayerPath` 必须等于 `layers(i)`。源接口用于跨层端口规划，路径用于汇端连接，汇端接口用于生成器端口校验（@sec-dv-routing）。

`Down`、`Edge`、`DVInterfaces`、`InterfacePath` 与 `LayerPath` 为不可变、可序列化的数据。`downCodec` 与 `edgeCodec` 提供两个关联类型的 schema 与规范化编码；其余三种类型采用框架定义的 schema。`DVProtocol.id.kind` 固定为 `Verification`；任何会改变求解函数、接口、渲染结果或 codec schema 的变更都必须更新版本。验证协议与设计协议共用注册表，`ProtocolKind` 为二者建立各自的标识空间。
