#import "../lib.typ": *

= 协议抽象 <ch-protocol>

协议规定一条连接上传播的参数类型和求解规则（@sec-node-conn-proto）。本章定义设计协议：一条设计 bind 上的 `Down`、`Up`、`Edge` 三种参数，逐边求解函数 `negotiate`，硬件接口描述，以及生成器参数的双层结构。验证协议见 @ch-verification；参数在模块内部如何传播见 @sec-propagation。

== 边上传播的参数与求解结果 <sec-three-param-kinds>

每条设计 bind 都有源节点与目标节点。协商时两端各提供一项参数，协议的求解函数 `negotiate` 把两项合成这条边的最终参数：

- #term[下行参数][downward parameter]（类型 `Down`）：由源节点的 `dFn` 算出，沿 bind 方向从源节点传向目标节点，是 `negotiate` 的第一项输入。
- #term[上行参数][upward parameter]（类型 `Up`）：由目标节点的 `uFn` 算出，逆 bind 方向从目标节点传向源节点，是 `negotiate` 的第二项输入。

传播的初始值由边界节点提供。以 AXI 内存互连为例，边界 outward 节点可以声明要求可达的地址范围、本地事务 ID 位宽、操作集合与数据位宽能力；边界 inward 节点可以声明服务地址区域、支持的操作、数据位宽能力与可接受的下游 ID 位宽。中间模块的端口参数函数继续变换这些值。以 CHI 类协议为例，同一机制会传播节点编号、每个请求节点的事务标签空间、转发事务携带的原始请求者身份以及数据缓冲标识能力。常见协议的传播方向与边界声明如下：

#table(
  columns: (auto, auto, 1fr, 1fr),
  table.header([应用场景], [源 → 目标], [边界 outward 节点的初始 `Down`], [边界 inward 节点的初始 `Up`]),
  [内存互连], [发起者 → 响应者], [要求可达的地址范围、事务身份需求、将发出的操作种类、数据位宽能力], [供给的地址集合、支持的操作、数据位宽能力、可接受的事务身份能力],
  [中断], [设备 → 中断控制器], [供出的中断线数量与触发语义], [可汇聚的线数、支持的触发类型、编号空间],
  [时钟], [时钟源 → 时钟接收端], [可产生的频率集合、时钟间的同源关系], [所需频率范围、抖动与关系约束],
  [复位], [复位源 → 被复位模块], [复位类型（同步或异步）、极性与施加时序], [可接受的类型、需要的保持拍数],
  [电源], [供电端 → 负载模块], [电压档位、可供预算、可用的电源状态], [功耗需求、状态转换需求],
  [Debug], [Debug 控制器 → 处理器核], [访问机制与可寻址范围], [断点与触发器数量、编号需求],
  [Trace], [处理器核 → Trace 汇聚器], [Trace 格式与源标识范围], [缓冲深度、可分配的端口编号],
  [MBIST], [存储宏 → MBIST 控制器], [存储几何参数与测试接口形态], [可调度的接口数、支持的测试算法],
)

两遍传播结束后，每条 bind 恰好得到一项 `Down` 和一项 `Up`。`negotiate` 将二者合成为#term[边参数][edge parameter]（类型 `Edge`）；`interfaceOf(edge)` 生成该边的硬件接口（@sec-protocol-interface），接口不得含 `Probe`——探针属于验证协议，求解期检查。边的 `Down`、`Up`、`Edge` 随后进入两端生成器模块各自的 `EdgeView`（@sec-two-layer-params）。

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

一个协议在代码里是一个协议对象，它的身份就是这个对象。它给出：三种关联类型 `Down`、`Up`、`Edge`；逐边求解函数 `negotiate`；接口描述函数 `interfaceOf`；三种参数各自的规范化序列化（编码与解码）。

`Down`、`Up` 与 `Edge` 关联到同一个协议值。给定协议值 `p`，`p.negotiate` 的参数类型是 `p.Down` 与 `p.Up`，成功结果类型是 `p.Edge`。bind 的源节点与目标节点使用同一个协议 `p`，因此该边上的参数和求解调用共享同一组类型；未经显式转换的跨协议连接表现为类型错误。

同一对象可以被多个模块的节点引用；bind 两端引用同一个协议对象由构造保证，兼容性即对象同一性。

双向传播完成后，框架按 bind 声明顺序为每条边调用一次 `negotiate`。参数兼容时返回 `Right(Edge)`；参数冲突时返回 `Left(Violation)`，`Violation` 是协议给出的冲突描述。端口参数函数发现的传播冲突同样以值返回。@ch-negotiation 把这两类失败连同相关节点、bind 与模块的源码位置写入异常消息，并立即终止协商（@sec-error-semantics）。

`Down`、`Up` 与 `Edge` 均不可变、可序列化，相应序列化用于在工具文件中编码、解码协议数据。

跨协议转换由显式的#term[协议转换模块][protocol converter]表达。该模块声明一个协议 A 的 inward 节点和一个协议 B 的 outward 节点，并在二者之间声明参数依赖：outward 节点的 `dFn` 执行 `A.Down => B.Down`，inward 节点的 `uFn` 执行 `B.Up => A.Up`。两侧 bind 分别按协议 A 与协议 B 调用 `negotiate`；参数转换与硬件转换位于同一个生成器模块。

== 协议接口 <sec-protocol-interface>

每条已求解的设计边都对应一个实际硬件接口。顶层接口由 `ProtocolBundle` 表示：Bundle 是带具名字段的聚合类型，字段类型由 `ProtocolInterface` 递归描述。接口描述支持 Bundle、定长 Vec、无符号整数、有符号整数、布尔、时钟、复位和带层路径的探针字段。每个字段记录名称、方向翻转标记和内部类型。

`LayerPath` 是从 FIRRTL 层根开始的非空名称序列，例如 `verification.cosim` 对应 `["verification", "cosim"]`（@sec-layers）。

每个 Bundle 层级的字段名必须唯一；`NonEmptyVector` 保证每个 Bundle 至少有一个字段，`NonEmptyString` 保证字段名至少包含一个字符，`PosInt` 保证 Vec 长度与整数位宽均为正数。

协议接口用于两项工作：

+ *结构模块的端口生成。*框架从穿越结构模块的边推导端口（@ch-hierarchy）；推导的依据是每条边的 `interfaceOf(edge)`，翻译为 FIRRTL 类型后生成。
+ *端口结构的校验。*每条已求解边在两端生成器上各对应一个端口；例化期框架把端口的实际类型与协议接口逐层比对，失配即报错并指出节点与 bind（@ch-hardware）。

#决策([每个设计协议必须实现 `interfaceOf`])[
  每个设计协议（`Protocol`）必须实现 `interfaceOf`，为每个成功求解的 `Edge` 返回一个 `ProtocolBundle`。跨层端口与生成器端口校验均以这份结构为准。
] <dec-pi-required>

没有连线的关系不作为协议。设备树、寄存器映射、UPF 等整机元数据由工具从导出数据生成（@sec-export）。

`ProtocolInterface` 是可序列化数据。协商期处理该数据，例化期将其翻译为 FIRRTL 类型。

设计协议的接口由 `Bundle`、`Vec`、`UInt`、`SInt`、`Bool`、`Clock` 与 `Reset` 构成。验证协议的接口以 `Probe`（FIRRTL 对内部信号的只读引用，@ch-verification）包装每个信号叶，并在 `Probe` 中记录 `LayerPath`。

`ProtocolBundle` 描述源端视角的字段结构。框架为源模块端口赋予 Output 根方向，为目标模块端口赋予 Input 根方向；`flip = false` 跟随根方向，`flip = true` 取反。字段顺序是接口结构的一部分。

== 参数的双层结构 <sec-two-layer-params>

一个生成器最终使用的参数从两个来源合并而来：

- #term[用户参数][user parameter]（`UserParam`）：构建期声明的容量、关联度、基地址与功能开关（@sec-module-kinds）。它在协商开始前就完全确定。
- #term[协议参数][protocol parameter]：完整参数中由协商结果决定的部分。协商结束后，框架把本模块每个节点求出的边整理成该模块的#term[边视图][`EdgeView`]（@sec-generator-records）；生成器模块声明的函数 `computeFullParam` 只读这份视图，与闭包中的用户参数直接合成完整参数（@sec-settle-pp）。

协商期调用生成器模块声明的 `computeFullParam`，将两者合并为该模块的 `FullParam` 并存入 `ResolvedDesign`。完整参数穿越 @sec-serialization-boundary 定义的序列化边界：

#图([参数的双层合并。用户参数写于构建期，协议参数算于协商期，二者在协商期合并为完整参数，并在例化期交给生成器。])[
  #syn-canvas({
    import cetz.draw: *
    rect((0, 2.1), (3.6, 3.0), stroke: 0.7pt, radius: 0.08, fill: rgb("#f0f6fd"))
    content((1.8, 2.55), [用户参数 \ #text(size: 8pt, fill: c-dim)[构建期声明]])
    rect((0, 0.6), (3.6, 1.5), stroke: 0.7pt, radius: 0.08, fill: rgb("#f2f9f4"))
    content((1.8, 1.05), [协议参数 \ #text(size: 8pt, fill: c-dim)[协商期生成]])
    line((3.75, 2.55), (5.1, 1.95), mark: (end: ">"))
    line((3.75, 1.05), (5.1, 1.65), mark: (end: ">"))
    content((4.35, 2.65), text(size: 8pt)[`computeFullParam`])
    rect((5.2, 1.25), (8.9, 2.35), stroke: 1pt, radius: 0.08)
    content((7.05, 1.8), [完整参数 \ #text(size: 8pt, fill: c-dim)[例化期输入 · 可序列化]])
    line((9.05, 1.8), (10.35, 1.8), mark: (end: ">"), stroke: 1.1pt + c-edge)
    content((11.15, 1.8), [生成器])
  })
]

zaozi 以生成器名字与完整参数的规范化序列化作为模块缓存键。用户参数相同而协议参数不同的实例具有不同缓存键，并分别生成模块定义。
