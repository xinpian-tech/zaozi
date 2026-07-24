#import "../lib.typ": *

= 工具与产物 <ch-tooling>

协商的全部中间态与结果都是普通数据（@sec-passes），这不该只是实现细节，而应兑现为工具能力：可导出、可可视化、错误信息可读。本章规定产物格式的骨架与确定性承诺。格式细节允许随实现演进；*确定性承诺不允许*。

== 导出 <sec-export>

协商结果可按需导出四份 JSON，各自独立成立：

- *拓扑*（`topology.json`）：模块树、节点、连接。每个实体带稳定标识与书写位置。
- *边*（`edges.json`）：每条边的两端（节点标识 $times$ 端口索引）、协议名、协议自述的渲染元数据（@sec-protocol-object 的 `render`）。
- *计划*（`plan.json`）：端口与连线规划的全部产物——每个模块的端口清单、连线计划与层声明（@sec-punch-planning、@sec-layers）。任一端口的来源均可在此文件中追溯。
- *参数*（`params.json`）：每个生成器模块的完整参数序列化——这一份同时就是可直接作为生成器命令行输入的文件（@sec-generator-contract），审计与复现共用一份数据。

```json
{ "modules": [ { "id": 3, "name": "l2", "parent": 1, "kind": "generator" } ],
  "nodes":   [ { "id": 12, "owner": 3, "role": "sink",
                 "valName": "banks", "protocol": "membus/1.0" } ],
  "connections": [ { "source": 9, "sink": 12,
                     "intent": "sink-decides", "at": "Soc.scala:41" } ] }
```

导出属于"可选可序列化"一类（@sec-serialization-list）：供人与工具消费，不构成版本兼容契约。唯一始终受契约保护的是参数导出，因为它是生成器的正式输入。

== 可视化 <sec-visualization>

从同一数据可导出两种图（GraphML / DOT）：*协商前视图*呈现意图——节点、连接与基数算子；*协商后视图*呈现事实——展开的边、结算参数的摘要标注。层次树映射为嵌套子图，边的颜色与标签取自协议的渲染元数据。#ref(<ch-topology>)例题里的每一张图，工具都应能从对应规格自动重现。

== 错误报告 <sec-error-format>

错误值（@sec-error-accumulation）的文本渲染遵守统一版式：首行是类别编号与一句话结论；中间列出全部相关书写位置，每行一个事实；末行给出可行动的修复方向。示例（C3，参数表长度不匹配）：

```text
error[C3] 参数表长度不匹配
  节点 soc.l2.banks（汇，参数表 4 项）无星号，入侧共 5 条边：
    Soc.scala:41   banks.node := xbar.port0   单连
    Soc.scala:42   banks.node := xbar.port1   单连
    ...            （共 5 处）
  期望恰好 4 条。增补参数表项、删减连接，或改用汇定（:*=）吸收可变部分。
```

版式的三条强制规则：*全部*位置都列出（歧义的双方、环的全程），用户无需自行查找其余位置；计数类错误附当前计数快照；修复建议限一句话，不展开成教程。

== 确定性承诺 <sec-determinism>

同一规格两次协商，全部导出逐字节相同；两次例化，FIRRTL 逐字节相同。为此所有集合的序列化次序都有规定：模块按层次树先序、节点按声明序、连接按声明序、边按端口索引、层按名字典序。任何 dump 中出现无序集合都是缺陷。这条承诺是缓存（@sec-dedup）、审计比对与重放既往构建的基础。
