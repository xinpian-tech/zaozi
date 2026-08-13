#set page(margin: 2cm, numbering: "1")
#set text(lang: "zh")
#show link: underline

= Diplomacy 2.0 设计文档

#quote(block: true)[Scala 3 重构版本。本文档是后续实现的设计基线，记录所有已定的架构决策与未决项。]

#line(length: 100%)

== 1. 概述

Diplomacy 2.0 是面向 SoC 设计的参数协商与拓扑连接框架。在保留旧 Diplomacy 核心思想（图协商、协议抽象、参数双向传播）的前提下，彻底重构了它与硬件构造的耦合方式：

- *后端从 Chisel 切换到 #link("https://github.com/xinpian-tech/zaozi")[zaozi]*；通过 zaozi 间接复用 CIRCT/MLIR 工具链
- *`InModuleBody` / `LazyModuleImp` 等阶段裂痕产物全部消除*
- *DSL 与硬件构造解耦*：Diplomacy 描述"要例化什么 zaozi 模块、用什么参数、怎么连"，自身不构造任何电路
- *CDE 移除*：参数显式流动，不再有全局隐式 `Parameters` PartialFunction
- *三阶段流水线*：Build → Negotiate → Elaborate，阶段间边界清晰

#line(length: 100%)

== 2. 设计哲学与不变量

=== 2.1 三阶段

```
   Phase 1: Build                Phase 2: Negotiate            Phase 3: Elaborate
┌──────────────────────┐     ┌──────────────────────────┐  ┌──────────────────────┐
│ 用户 DSL 构造 spec   │ ──▶ │ 跑协商算法               │──▶ 调 zaozi + circtlib  │
│ DiplomaticModule     │     │ 解析 cardinality         │  │ 例化模块、连线       │
│ 节点声明、连接、bind │     │ 传播 D/U 参数            │  │ 生成 CIRCT FIRRTL    │
│                      │     │ DV 路由 + Layer merge    │  │                      │
│ JVM 内、含闭包       │     │ 调用 computeProtocolParam│  │ 同 zaozi 上下文      │
└──────────────────────┘     └──────────────────────────┘  └──────────────────────┘
```

每个阶段输入输出明确，无 lazy val 链式触发。

=== 2.2 核心不变量

+ *每个 DiplomaticModule wrap 0 或 1 个 zaozi Generator*：sealed 类型层面强制
+ *DiplomaticModule 二选一*：`WrapperModule`（无 Generator）或 `TerminatorModule`（含 Generator），不能混合
+ *只有传给 zaozi 的 PARAM 必须可序列化*；spec 中 binding / lambda 是 JVM-local
+ *PARAM 分两层*：`UserParam` 由用户在 Phase 1 给，`ProtocolParam` 由 Negotiator 在 Phase 2 算
+ *DV 只能向上传播*：从 Source 一路打洞到 Sink 所在 ancestor，路径上每个 wrapper 自动增加 DV IO
+ *硬件逻辑只能在 zaozi Generator 里写*：Diplomatic 模块不接受 inline 硬件代码（彻底消除 InModuleBody）

#line(length: 100%)

== 3. 架构与边界

=== 3.1 模块依赖图

```
┌────────────────────────────────────────────────────┐
│ diplomacy-core                                     │
│  - Protocol / DVProtocol trait                     │
│  - ProtocolInterface ADT                           │
│  - NodeSpec / ConnectionSpec (pure data)           │
│  - WrapperModule                                   │
│  - DiplomaticModule (sealed trait)                 │
│  - Negotiator (算法层)                              │
└──────────────────┬─────────────────────────────────┘
                   │
                   ▼
┌────────────────────────────────────────────────────┐
│ diplomacy-zaozi   （depends on zaozi + circtlib）   │
│  - TerminatorModule[UP, PP, FP, I]                 │
│  - HWBinding 类型与 lambda 工具                      │
│  - Elaborator                                      │
│      ├─ Wrapper path (circtlib 直接 emit FIRRTL)    │
│      └─ Terminator path (zaozi gen.instantiate)    │
└────────────────────────────────────────────────────┘
```

模块切分按"职责"组织，`core` 放图模型和协商算法，`zaozi` 放硬件实例化。`core` 是否额外引入 zaozi / circtlib 依赖不强制——按实现便利性决定。如果 core 内部用 zaozi 的某些 pure type 帮忙写更顺手，引入即可。

=== 3.2 边界与序列化

#table(
  columns: 4,
  table.header([边界], [跨越者], [必须 serializable], [形态]),
  [Phase 1 → Phase 2], [同 JVM 函数调用], [✗], [spec 持有 TerminatorModule 实例（含闭包）],
  [Phase 2 → Phase 3], [同 JVM 函数调用], [✗], [ResolvedDesign + lambdas in memory],
  [*Diplomacy ↔ zaozi*], [*`gen.instantiate(param)`*], [✓], [*PARAM 必须 derive upickle ReadWriter*],
  [可选 debug dump], [落盘], [✓（部分）], [ModuleSpec + topology + PARAM JSON],
)

*唯一强制序列化点*是 PARAM（既包含 UserParam 也包含 ProtocolParam）。其它 spec 字段可选择序列化用于调试/可视化，但不是契约。

#line(length: 100%)

== 4. 核心抽象

=== 4.1 Protocol（设计协议）


注意 *没有 `bundleType: Edge => zaozi.Bundle`*——zaozi 的 Bundle 形状由 Generator 的 `HWInterface` 决定，不由 Protocol 决定。Protocol 只提供 `ProtocolInterface`（Diplomacy 内部 ADT），仅 wrapper IO 生成路径用。

=== 4.2 DVProtocol（验证协议）


DVProtocol 不像 Protocol 那样双向协商；只有 `Up`（向上传），由 Sink 端 resolve。

=== 4.3 ProtocolInterface

纯数据 ADT，描述 edge 在协议层面的 bundle 形状。*只用于 wrapper IO 生成*（terminator path 不需要）：


elaborate 时 `piToFirrtlType(pi)` 单向翻译到 circtlib 的 `BundleType` / `UIntType` 等。

=== 4.4 PARAM 双层结构

每个 Generator 的 PARAM 由 UserParam（用户填）+ ProtocolParam（Diplomacy 协商出）组合：


zaozi 看到的是完整 `TLRAMParam`。Diplomacy 在 Phase 3 用 `combine: (UP, PP) => FP` 合成。

=== 4.5 DiplomaticModule 层次（sealed）


Phase 2 末尾对每个 TerminatorModule 调用 `computeProtocolParam(view)` 拿 PP。

#line(length: 100%)

== 5. Phase 1：Builder DSL

=== 5.1 入口


`DesignBuilder` 是 sealed trait，只能由框架入口注入。`:=` 等连接操作只在有 `given DesignBuilder` 时编译通过。

=== 5.2 节点构造

*Design 节点*：


*DV 节点*：


=== 5.3 连接 DSL

Design 连接：4 种 binding 保留，sealed 操作符在 NodeHandle 上：


DV 连接：


=== 5.4 完整示例


#line(length: 100%)

== 6. Phase 2：Negotiator

=== 6.1 算法流水线


每一步纯函数，错误是值（`Either`）。

=== 6.2 Cardinality 解析

*完全保留旧 Diplomacy 的算法*：

- 4 种 binding：`BIND_ONCE` / `BIND_STAR` / `BIND_QUERY` / `BIND_FLEX`
- `resolveStar(iKnown, oKnown, iStars, oStars)` 按 NodeRole 分派
- `flexOffset` 通过 DFS 在 flex 连通分量上求解方向
- `edgeArityDirection` / `edgeAritySelect`

实现重写为纯函数，去掉 `lazy val starCycleGuard` 等 mutable 哨兵；环检测通过显式 visited set 在拓扑排序前完成。

=== 6.3 参数传播

按拓扑序：


`uFn` / `dFn` 是 NodeSpec 上的 Scala 闭包（`α` 决定 spec JVM-local，允许闭包）。

=== 6.4 computeProtocolParam 调用


PP 算完后跟用户提供的 UP 组合：`FP = t.combine(t.userParam, pp)`，存入 ResolvedDesign。

=== 6.5 DV 路由（"打洞"）


每经过一个 wrapper 边界产生一段 wire；wrapper module 的 IO 自动追加对应字段。

=== 6.6 Layer 合并

每个 wrapper 模块的 `mergedLayers: LayerTree` 是子树所有 DV 节点 LayerSpec 的 prefix tree union：

```
Source A: "verification.cosim"
Source B: "verification.assert.fatal"
Source C: "verification.cosim"
==> merged:
    verification
      ├─ cosim
      └─ assert
           └─ fatal
```

merge 完展平成 `Seq[Seq[String]]` 喂给 circtlib `Module.op(..., layers = ...)`。

#line(length: 100%)

== 7. Phase 3：Elaborator

=== 7.1 入口


要求外部提供 `Arena` / `Context`（zaozi 上下文）。模块按拓扑序（bottom-up）elaborate。

=== 7.2 Wrapper Path（circtlib）

对 `WrapperModule`：


Wrapper 模块名：`<diplomatic_module_valName>_<topologyHash>`。

=== 7.3 Terminator Path（zaozi）

对 `TerminatorModule`：


zaozi 的 `:=` 操作符负责 bundle-level 连接，Diplomacy 不需要枚举字段。

=== 7.4 WirePlan 执行


设计 wires 和 DV wires 走同一套机制，区别只在 `resolveRef` 走 design IO 还是 DV IO。

#line(length: 100%)

== 8. Spec 数据结构


注意 `DesignNodeSpec` 和 `DVNodeSpec` 里的 lambda 字段是 `Any => Any` 等粗类型——spec 是 JVM-local 容器，类型安全在 Builder DSL 入口由 generics 保证；进 spec 后类型擦除。

#line(length: 100%)

== 9. 序列化模型

*唯一必须 serializable*：每个 TerminatorModule 的 FullParam（user + protocol 合成后）。这是 `gen.instantiate(param)` 的入参，zaozi 要求 `upickle.default.ReadWriter[PARAM]`。

*可选 serializable*（用于 debug / 可视化 / 跨进程分析）：
- `ModuleSpec.id / name / parent / children`（拓扑）
- `DesignConnection / DVConnection`（连接图）
- 每条 edge 的 edge param
- 每个 LayerTree

*永远不 serializable*：
- `TerminatorModule` 实例（含 `computeProtocolParam` 等方法）
- 所有 `hwBinding` lambda
- 所有 `dFn` / `uFn`
- WirePlan（间接通过 NodeId 引用，本身字段可序列化但语义解释依赖 spec）

实践上：dump 一份 `design.json` 包含可序列化部分用于 review / 工具消费；运行时 elaborate 用 in-memory ResolvedDesign。

#line(length: 100%)

== 10. 与旧 Diplomacy 的差异

#table(
  columns: 2,
  table.header([旧概念], [新设计中的处理]),
  [`LazyModule` 基类], [拆成 sealed `DiplomaticModule` → `WrapperModule` / `TerminatorModule`],
  [`LazyModuleImp`], [删除。硬件逻辑在 zaozi Generator 里],
  [`LazyModuleImpLike extends RawModule`], [删除。Diplomatic 模块不是 zaozi 模块],
  [`InModuleBody { ... }`], [删除。所有依赖协商参数的逻辑在 Generator 内（PARAM 已含 PP）],
  [`AutoBundle` + `Dangle` 自动打孔], [wrapper IO 由 Negotiator 显式算出，circtlib 直接 emit],
  [`CloneLazyModule`], [删除（zaozi 自己有 Instance 复用机制）],
  [CDE `Parameters` / `Field`], [删除。配置走显式 PARAM；Diplomacy 自身配置走 `NegotiationConfig`],
  [`bundleOut/bundleIn` lazy Wire], [删除。Wire 只在 zaozi 调用里出现],
  [`iPush/oPush` mutate `ListBuffer`], [改为 Builder DSL 显式累积（spec 不可变）#footnote[严格地说 `:=` 仍然是副作用语法，无法做成纯函数。新设计是把 `iPush/oPush` 这两个函数从 NodeSpec 上*彻底消掉*，把状态搬到一个有边界的 `DesignBuilder` 累加器里：

- *NodeSpec 完全 immutable*（case class，无 var / ListBuffer）。
- 每次 `:=` 在 `DesignBuilder` 上做*单次* `addConnection`，不再双端 mutate。
- *Index 由 Negotiator 派生*：connection list 的插入顺序决定每个节点 `iBindings`/`oBindings` 的位置，跟老的 `iPush(index = y.oPushed, ...)` 语义等价。
- *Freeze 时机由生命周期保证*：`design { body }` 块结束后 builder 不再被引用，Negotiator 拿到的是 immutable spec，"在 negotiate 后再 push"在类型/作用域层面不可能发生（替代了老的 `require(!iRealized)` 运行时检查）。
- 副作用通过 `using DesignBuilder` 编译期收敛——脱离 design block 写 `:=` 编译不过。
- 全部正确性靠这一个 contextual scope token 把死。]],
  [8 个类型参数 `MixedNode[DI,UI,EI,BI,DO,UO,EO,BO]`], [类型成员收敛到 `Protocol` trait],
  [`JunctionNode` / `EphemeralNode`], [保留（SoC 偶有用）],
  [`IdentityNode`], [保留],
  [4 种 binding（ONCE/STAR/QUERY/FLEX）], [*全部保留*，原算法],
  [16 种 `:= / :*= / :=* / :*=*` 重载], [*全部保留*，迁移到 sealed Handle trait],
  [`BundleBridge*`], [改造成基于 `Protocol` 的标准协议],
  [GraphML 可视化], [保留，从 spec 重新生成],
)

#line(length: 100%)

== 11. 未决项

=== Tier 2（实现时随手定）

- *ProtocolInterface 必选 vs 可选*：倾向必选；wrapper forwarding 强制要求 PI
- *hwBinding 必填 vs 默认*：倾向必填；多字段下默认会出怪事
- *LazyScope 替代*：动态生成子模块树的 API（普通 `for { submodule(...) }` 是否足够，还是需要专门 scope）
- *SourceLocation 实现*：用 `sourcecode` 库（跟 zaozi 一致），少一个差异点
- *错误报告格式*：`Either[NegotiationError, ResolvedDesign]` + 累积式错误，让 SoC 协商一次看完所有问题

=== Tier 3（推迟）

- *AddressMap / ClockDomain 抽象层归属*：倾向放协议库或 SoC framework，不进 diplomacy-core
- *mill 模块切分*：纯工程，按 `core / zaozi / protocols` 切
- *ResolvedDesign JSON schema*：等有外部工具消费需求再约定
- *测试策略*：core 纯函数单测；zaozi 层 integration test（lit 风格）
- *老 Diplomacy 迁移路径*：用户已表态彻底重构，可能不需要 scalafix；rocket-chip 类项目直接 fork

#line(length: 100%)

== 12. 实施路线图

按"自下而上、可独立合并"原则分 8 步：

#table(
  columns: 4,
  table.header([步骤], [内容], [依赖], [工程量估计]),
  [1], [`diplomacy-core/protocol/` Protocol / DVProtocol / ProtocolInterface], [无], [小],
  [2], [`diplomacy-core/spec/` NodeSpec / ModuleSpec / DesignSpec], [1], [小],
  [3], [`diplomacy-core/negotiator/` Cardinality + 参数传播 + DV 路由 + Layer merge], [2], [*大*（原算法搬迁 + 重写为纯函数）],
  [4], [`diplomacy-core/builder/` Builder DSL（WrapperModule、节点构造、operator）], [2], [中],
  [5], [core 层 unit test：纯函数协商可独立验证], [1-4], [中],
  [6], [`diplomacy-zaozi/` TerminatorModule + hwBinding 工具], [4], [中],
  [7], [`diplomacy-zaozi/elaborator/` Wrapper path (circtlib) + Terminator path (zaozi)], [3,6], [*大*],
  [8], [至少一个完整协议实现（BundleBridge / TileLink 子集），验证端到端], [7], [中],
)

整体估计 4-6 周（一人全职）。

#line(length: 100%)

== Appendix A：术语对照

#table(
  columns: 3,
  table.header([中文], [英文], [旧 Diplomacy 中的对应]),
  [设计协议], [Protocol], [NodeImp],
  [验证协议], [DVProtocol], [（新增）],
  [协议接口], [ProtocolInterface], [（新增，部分覆盖 Bundle）],
  [拓扑模块], [DiplomaticModule], [LazyModule],
  [包装模块], [WrapperModule], [SimpleLazyModule],
  [终结模块], [TerminatorModule], [LazyModule + LazyModuleImp],
  [用户参数], [UserParam], [（旧的 case class 构造参数）],
  [协议参数], [ProtocolParam], [（旧的 `node.in/.out._2`）],
  [完整参数], [FullParam], [（隐式合成）],
  [协商器], [Negotiator], [散布在 `MixedNode` 的 lazy val],
  [例化器], [Elaborator], [`LazyModuleImp.instantiate`],
  [边描述], [EdgeView], [`node.in / node.out`],
  [硬件绑定], [HWBinding], [旧 `LazyModuleImp` body 里手写 connect],
)

#line(length: 100%)

== Appendix B：示例 SoC


#line(length: 100%)
