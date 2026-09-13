# 裸端口与 Bool 时序连接（2026-09-13）

接口版本为 `runtime-ltl-v2`，RAG v16。无模型框架验证，未调用 DeepSeek，不宣称实际 token 节省比例。
HAVEN、固定 Stage-1、DUT RTL、求解与 sequence 数量预算没有因本次变更而修改。

## 模型写法

对于 IO 表中的 Bool 端口，可以直接写：

```scala
Gen(req ### ack, "request_then_ack")
Gen(req.##(gap)(ack), "delayed_ack")
```

这是符号化语法示意，req/ack/gap 不是某个设计的历史答案。
第一式要求前后相邻的两个采样周期；并非任意延迟的握手响应。
`Gen`、局部谓词和 helper 仍属于原始 LTL 片段，框架不替模型挑选场景或操作数。

## 自动绑定的实现

`sequence_framework.port_bindings(design)` 仅根据 IO manifest 计算端口名到局部变量的映射。
`render_model_ut` 在固定 UT 中、模型片段之前生成：

```scala
val req = io.req
val ack = io.ack
// 模型的原始 LTL 从这里开始
```

这些是类型保留的引用别名，不是新寄存器、驱动逻辑或 DUT 实现。IO 接线、时钟与复位仍由框架固定生成。
模型源文件逐字节保存；不把裸名字替换成带前缀的字符串，也不改写时序表达式。

普通端口保持同名。与 Scala 关键字或 API 冲突时，IO 表显式列出 `port_...` 别名，
并避让实际存在的同名端口。例如同时有 Gen 和 port_Gen 时，Gen 映射为 port_port_Gen。

未增加宏注入层：当前 Interface 的字段通过 selectDynamic 宏访问，
不是可以直接通配 import 的普通 Scala 成员。trait/given 不能直接把这些动态字段变成调用者的局部词法名字；
普通表达式宏的实参需先通过名字解析。现有 codegen 已在类型检查前持有完整 IO manifest，
可直接声明并审计这些绑定，无需另加编译器级变换。

## 时序简写的实现

重载放在 zaozi 的 `SVAApi` trait 和默认 `given SVAApi` 中。
支持 Bool/Bool、Bool/Sequence、Sequence/Bool 的：

- `a ### b`：相隔一拍。
- `a.##(b)`：零拍连接。
- `a.##(n)(b)`：固定间隔。
- `a.##(lo, Some(hi))(b)`：有界间隔；底层 None 仍沿用已有无界 API。

只在连接处按当前 ClockEvent 提升 Bool，结果为 Sequence，可继续连接。
已有 Sequence 的时钟不被重绑。不是全局隐式类型转换：
硬件 `& / | / !`、数值比较和 past 的类型保持不变；Bits、数值及 Property 不能冒充连接操作数。
重复等其他序列 API 仍可显式使用 `.S`。没有新增自动历史有效性保护。

skill、prompt、IO 表及 RAG 原文摘录同步更新，均只包含通用语义和符号化示例。

## 最终验证

| 检查 | 结果 |
| --- | --- |
| Python 全量离线回归 | 423 项：385 通过、38 按开关跳过，无失败 |
| SVASpec + PastSpec | 54 项通过 |
| 简写与显式 .S 的生成结果 | next、zero、fixed、bounded、连续连接、计算谓词/past 一致 |
| 多时钟与上下文 | 生成结果一致，保留已有序列时钟；缺 ClockEvent 时拒绝 |
| 实际隔离编译 | 裸端口/helper 类型保留、重名端口均通过 |
| 编译负例 | 未知端口、Bits 左/右操作数、Property 操作数均被拒绝 |
| JG + VCS/URG | 四类目标、同 UT 互斥目标、13 类回放及故意错误输出负例通过 |
| skill 实际编译 | 正确 Referable helper 通过，错误 datatype helper 被拒绝 |

真实工具及 skill 检查的 4 个测试方法共约 185 秒，不是模型实验时间。
多时钟新测试是生成一致性检查，不代表任意跨时钟组合均已端到端验证。

工具产物：

- `out/experiments/runtime-ut-regression-tb5_n5g6/`：JG。
- `out/experiments/runtime-ut-regression-t_a1s0u8/`：隔离编译及负例。
- `out/experiments/cycle-replay-regression-lsu_alyg/`：VCS/URG 回放及负例。

复现使用 [LTL-only 验证命令](ltl-only-interface-20260913.md#无模型复现)，另加：

```bash
nix develop -c mill zaozi.tests.testOnly me.jiuyang.zaozitest.SVASpec me.jiuyang.zaozitest.PastSpec
nix develop -c env PYTHONPATH=experiments RVPROBE_RUN_TOOL_TESTS=1 \
  experiments/haven-python /path/to/haven -m unittest \
  test_sequence_framework.RuntimeToolTest.test_bare_ports_preserve_types_and_bool_temporal_overloads \
  test_sequence_framework.RuntimeToolTest.test_escaped_port_aliases_compile_without_capturing_gen_or_past -v
```
