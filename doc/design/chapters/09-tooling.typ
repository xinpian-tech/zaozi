#import "../lib.typ": *

= 工具与产物 <ch-tooling>

协商的中间态与结果均为可检查的数据（@sec-passes），可用于导出、可视化与错误报告。本章规定这些工具产物的内容和规范化顺序。

== 导出 <sec-export>

`ResolvedDesign` 可按需导出四份 JSON：

- *拓扑*（`topology.json`）：模块树、模块节点、设计 bind、模块内部参数依赖，以及探针源、探针汇与验证 bind。模块、节点、bind 和验证端点包含各自的稳定标识；一条模块内部参数依赖由有序二元组“inward `ModuleNodeId`、outward `ModuleNodeId`”唯一标识。各项同时保存声明顺序和源码位置（`SourceLocation`）。
- *求解结果*（`edges.json`）包含两组记录：
  - `designEdges` 按 bind 声明顺序保存 `BindId`、源与目标节点、`ProtocolId`、传播得到的 `Down` 与 `Up`、`Edge`、`ProtocolBundle` 及协议 `render` 元数据；
  - `dvResults` 按 `DVSinkId` 保存 `ProtocolId`、有序 `DVBindId`、按相同顺序排列的验证协议 `Down` 与 `LayerPath`、验证 `Edge`、`DVInterfaces` 及 `DVProtocol.render` 元数据（@sec-protocol-object、@sec-dv-protocol）。
- *计划*（`plan.json`）：每个模块的跨层端口计划、连线计划与层声明；每项计划以 `Design(BindId)` 或 `Verification(DVBindId)` 标记来源，并记录 bind 的源码位置（@sec-punch-planning、@sec-layers）。
- *整机参数*（`params.json`）：每个生成器模块一条记录，包含模块标识、生成器标识和该实例的 `FullParam` 值；生成器标识确定对应的 `FullParam` schema。

`ModuleId` 直接编码为实例名数组；`ModuleNodeId` 与 `BindId` 按 @sec-identity 的组成字段编码为 JSON 对象。模块内部参数依赖保存 inward、outward 两个 `ModuleNodeId`，重复的有序端点对在结构校验中非法。`DVSourceId`、`DVSinkId` 与 `DVBindId` 同样按 @sec-dv-declarations 的组成字段编码。导出记录保留模块、模块节点、bind、参数依赖、协议标识、源码位置和稳定标识之间的对应关系。

单个生成器定义一个 `FullParam` 的序列化 schema；单个 IP 独立例化时，命令行输入是一份符合该 schema 的 `FullParam` 值（@sec-generator-contract）。`params.json` 采用整机多实例结构，其中每条记录携带一个可作为生成器输入的 `FullParam` 值。

调用方按需选择四份整机导出文件；工具版本确定其容器格式（@sec-serialization-list）。设备树、寄存器映射、UPF 等整机产物由工具从这些导出生成。硬件边界的兼容契约由生成器版本对应的 `FullParam` schema 及单个 `FullParam` 值的序列化格式构成。

== 可视化 <sec-visualization>

从同一数据可导出两种图（GraphML 与 DOT）：*协商前视图*包含模块、inward 与 outward 节点、有向 bind 和模块内部参数依赖；*协商后视图*为每条边增加 `Down`、`Up`、`Edge` 与参数摘要。层次树映射为嵌套子图，边的颜色与标签取自协议的渲染元数据。

== 错误报告 <sec-error-format>

错误报告（@sec-error-semantics）直接陈述问题与结论，随后列出相关源码位置（`SourceLocation`）、触发条件与参数值，末行给出修复方向。例如，地址重叠错误必须同时指出冲突的两个目标节点、各自服务区间、相交区间和收窄地址范围的修复方向。

涉及参数、数量或容量时附相应快照；修复建议限一句话。

== 规范化顺序 <sec-determinism>

模块、端口、子实例与连线的 FIRRTL 发射顺序及结构键采用 @sec-dedup 的规范。

JSON 中模块按显式子实例声明顺序执行层次树先序导出；完整参数记录采用同一模块顺序。

设计边采用 bind 声明顺序；模块内部参数依赖的 inward 端采用节点声明顺序；`EdgeView` 采用模块的层次树先序，内部节点采用节点声明顺序，每个节点保存其唯一的设计边。

探针端点按所属模块先序及端点声明顺序导出；每个 `dvResults` 条目中的 `DVBindId`、验证协议 `Down`、`LayerPath` 及 `DVInterfaces.sources` 均继承该汇的 bind 声明顺序。

Bundle 字段保留协议定义顺序；FIRRTL 层声明树的同级节点和映射键按名字典序。

错误报告内的 `SourceLocation` 按规范化文件路径、行号与列号排序（@sec-error-semantics）。

`SourceLocation.file` 采用相对于设计源码根目录、分隔符统一为 `/` 的路径，行列号使用十进制。源码位置作为诊断字段；实体身份由稳定标识确定，导出顺序由上述规范确定。规范化编码使同一规格与同一工具版本产生逐字节相同的导出。
