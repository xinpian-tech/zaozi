# rvprobe 动态 UT / LLM 输出契约

当前契约：`runtime-ut-v1`。入口为 `sequence_experiment.py`（`alu_residual_loop.py` 保留兼容命令名），
生成器为 `sequence_framework.py`。旧的 `cases: Seq[(String, Int, Long, Long)]` 不是当前模型输出。

## 分工

- 人提供原始 RTL、版本化 IO / 时钟 / 复位 manifest、当前 URG 残余及必要的协议说明。
- 框架据 manifest 生成一个共享 `VerilogWrapper`，只描述端口和外部模块绑定，不实现 DUT 逻辑。
- LLM 为每个候选返回实际的 `Sem.Intent` 表达式。
- 框架为每条意图生成独立 UT，接线后将该表达式放进 `Generate(expression, label)`。
- 固定 runner 执行编译、JasperGold、trace → ABI → stimulus → UVM；不从预写的设计专用 UT 中选模板。

每轮生成文件在 `<run>/attempt-N/sources/`，通过 `RVPROBE_EXPERIMENT_SOURCES` 成为显式编译输入。
仓库不再有共享的 `experiments/src/Generated.scala` 槽位。活动 classpath 只有 zaozi / utlib，不含 stdlib。

## 输出

只返回一个 JSON 对象，恰有 `intents` 和 `proofObligations` 两个字段：

```json
{"intents": [], "proofObligations": []}
```

这只是空的格式示意，不是推荐模型返回空答案。每条 intent 为
`{"label": "唯一的_snake_case", "expression": "返回 Sem.Intent 的 Scala 表达式"}`；
proof 为 `{"label": "唯一的_snake_case", "reason": "待独立验证的精确矛盾"}`。
实际 label 只能使用小写 ASCII 字母、数字、下划线，以字母开头。两类记录之间也不能重名。

表达式可以使用 `Sem.value`、`Sem.state`、`Sem.relation`、`Sem.temporal` 以及 `&&` 组合。
也可以使用 Scala block 声明局部谓词 / `Txn.window`，最后返回意图。
端口以当前 prompt 的 `io.<port>` 为准；框架提供 `io.clock`、`io.reset`、
`ClockEvent`、`ClockScope`、`ResetScope`。不要再次调用 Generate、声明 DUT、替换 runner 或引用内部信号。
表达式由真实 Scala 编译器检查，不是字符串检查后映射到某个历史用例。

## Prompt 与 RAG

任务证据和检索资料分开：

1. 当前未覆盖行、对应 RTL 窗口、IO 类型、协议说明。
2. framework-only API 原文及三个可编译的参数化示例：JSON 数据封装、四类 Sem 组合、求解与导出。
3. 当前 JSON / 表达式契约。
4. 仅紧邻上一轮的编译或求解反馈。

语料版本为 4。来源白名单、原文匹配与源文件哈希由 `prompt_rag.py` 检查；
示例直接读取 `src/rag/` 中实际参与编译的源码，不维护第二份示例。
检索 query 只描述框架接口，不包含 DUT 名、残余分支或答案。
禁止历史 response、设计操作数、witness、覆盖结论和设计专用不变量进入语料。
来源验证不能替代新增语料的人工审查。

两组对照必须使用同一个生成契约和任务证据，只改变 RAG 上下文。
旧污染语料保存在审计目录，loader 拒绝使用；旧静态 UT 结果也不能混入新契约的对照。

## 证据与安全边界

编译通过 ≠ 意图正确；witness ≠ 覆盖目标闭合；proofObligation ≠ 不可达证明。
JasperGold 的 `infeasible` 表示该意图在其模型假设下不可达，不自动证明某个 RTL 分支是 dead code。
`unknown` 不计为成功；VCS / URG 才决定覆盖增量。

每条 witness 独立从 formal reset 后搜索。导出的 UVM sequence 不负责实现协议 driver，
也不在串接 witness 之间自动 reset；握手等待可能改变周期关系。
必须验证 driver 的时序映射和初始状态，必要时独立回放各 intent。原始 VCD、逐 intent stimulus 和 sequence 均保留。

模型输出是会被编译执行的 Scala。JSON 校验、类型系统和历史依赖检查均不是安全沙箱；
运行不可信输出前须审查，或使用隔离执行环境，不向执行进程暴露模型密钥等不必要凭据。
复现命令与当前支持范围见 [README](README.md)；历史驱动见 [legacy](legacy/README.md)。
