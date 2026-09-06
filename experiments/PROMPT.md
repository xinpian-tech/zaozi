# rvprobe 动态 UT / LLM 输出契约

当前契约：`runtime-ut-v2`。单轮入口为 `sequence_experiment.py`，闭环入口为 `coverage_flow.py`，
生成器为 `sequence_framework.py`。框架仅接受下述 JSON / Gen 表达式，不提供旧 API 或命令兼容层。

## 分工

- 人提供原始 RTL、版本化 IO / 时钟 / 复位 manifest 及必要的协议说明；闭环入口自动测量 baseline 和后续 URG 残余。
- 框架据 manifest 生成一个共享 `VerilogWrapper`，只描述端口和外部模块绑定，不实现 DUT 逻辑。
- LLM 为每个候选返回实际的硬件 Bool / Sequence / Property 表达式，即 `Gen.Expr`。
- 框架为每条目标生成独立 UT，接线后将该表达式放进唯一入口 `Gen(expression, label)`。
- 固定 runner 执行编译、JasperGold、trace → ABI → stimulus → UVM；不从预写的设计专用 UT 中选模板。

每轮生成文件在 `<run>/attempt-N/sources/`，通过 `RVPROBE_EXPERIMENT_SOURCES` 成为显式编译输入。
仓库不再有共享的 `experiments/src/Generated.scala` 槽位。活动 classpath 只有 zaozi / utlib，不含 stdlib。

## 输出

只返回一个 JSON 对象，恰有 `intents` 和 `proofObligations` 两个字段：

```json
{"intents": [], "proofObligations": []}
```

这只是空的格式示意，不是推荐模型返回空答案。每条 intent 为
`{"label": "唯一的_snake_case", "expression": "返回 Gen.Expr 的 Scala 表达式"}`；
proof 为 `{"label": "唯一的_snake_case", "reason": "待独立验证的精确矛盾"}`。
实际 label 只能使用小写 ASCII 字母、数字、下划线，以字母开头。两类记录之间也不能重名。

表达式不再区分 value / state / relation / temporal，不需要任何类别包装。
硬件 `Bool` 直接作为谓词传入；用 `&` / `|` / `!` 组合，不支持 `&&` 或 `.asUInt`。
时序组合使用 `.S` 将 Bool 变为时钟序列，`a.S ### b.S` 表示相隔一拍，
`a.S.##(delay)(b.S)` 表示固定拍数延迟；Sequence / Property 沿用框架时序算子。
也可以使用 Scala block 声明局部谓词，最后返回表达式。
端口以当前 prompt 的 `io.<port>` 为准；框架提供 `io.clock`、`io.reset`、
`ClockEvent`、`ClockScope`、`ResetScope`、`Gen.Scope`。不要再次调用 Gen、声明 DUT、替换 runner 或引用内部信号。
表达式由真实 Scala 编译器检查，不是字符串检查后映射到某个历史用例。

跨拍引用用 `Gen.past(signal, width, cycles)`（当前支持 Bits）；它只能在 Gen 上下文中创建。
框架生成复位初始化的历史寄存器和有效深度计数器，在整个目标的起始拍自动合取所有历史有效性条件，
即使 past 出现在未来子序列中也遵守这一规则。不需要模型手写 ready，也不暴露未保护的历史窗口。
每拍指采样时钟周期，不自动按握手筛选。Gen 的历史上下文是每次调用独立的，不跨目标保留。

表达式描述要找到的轨迹，不是要求所有轨迹成立的断言。优先使用可产生有限 witness 的目标；
Property 类型可接受，不代表任意无限时域 LTL 都被求解 / 回放支持。
蕴含可能因前件从未出现而成立，不能单靠蕴含要求生成一次事务。

## Prompt 与 RAG

任务证据和检索资料分开：

1. 当前未覆盖行、对应 RTL 窗口、IO 类型、协议说明。
2. framework-only API 原文及三个可编译的参数化示例：JSON 数据封装、统一目标表达式、求解与导出。
3. 当前 JSON / 表达式契约。
4. 紧邻上一轮的编译或求解反馈；闭环模式另提供本次运行实测的覆盖变化和最新残余。

语料版本为 8。目标示例包含 `Bits.asUInt`、`BigInt.U(width)`、`BigInt.B(width)` 和十六进制文本构造说明，
直接演示 Bool `&` / `!`、时序延迟、带保护的 `Gen.past` 和统一 `Gen` 调用。
所有数值和宽度仍由调用者的任务提供，不包含设计答案。
来源白名单、原文匹配与源文件哈希由 `prompt_rag.py` 检查；
示例直接读取 `src/rag/` 中实际参与编译的源码，不维护第二份示例。
检索 query 只描述框架接口，不包含 DUT 名、残余分支或答案。
禁止历史 response、设计操作数、witness、覆盖结论和设计专用不变量进入语料。
来源验证不能替代新增语料的人工审查。

两组对照必须使用同一个生成契约和任务证据，只改变 RAG 上下文。
旧污染语料保存在审计目录，loader 拒绝使用；旧静态 UT 结果也不能混入新契约的对照。

编译修正保留原始诊断；仅当失败的 typecheck 明确报告 `DynamicSubfield ... Bool` 时，
额外附上通用 Bool API 提示及源码路径，不猜测设计条件、不替模型修改表达式。
该诊断增强对 RAG on/off 两组一致。它与语料更新同时改变时，不能把效果单独归因于 RAG。

## 与旧契约的关系

JSON 的 `intents` 字段名保留，它只是候选列表，不再具有 Sem.Kind 分类。
旧 `Sem` / `Generate` / `Txn`、legacy 模块和旧命令入口均已删除；需要旧行为时使用对应 Git 历史。
旧 `runtime-ut-v1` response 不能直接当作 v2 样本；框架不自动改写历史回答。
比较 v1 / v2 时需明确契约差异，既有日志和覆盖记录不重新标记。

## 证据与安全边界

编译通过 ≠ 意图正确；witness ≠ 覆盖目标闭合；proofObligation ≠ 不可达证明。
JasperGold 的 `infeasible` 表示该意图在其模型假设下不可达，不自动证明某个 RTL 分支是 dead code。
`unknown` 不计为成功；VCS / URG 才决定覆盖增量。

每条 witness 独立从 formal reset 后搜索。闭环和 RAG 对照按 `cycle-replay-v1` 每条独立复位、一拍一驱动、
上升沿前以 clocking input `#1step` 采样；不通过等待 done 拉长 witness。实际输入/时刻/复位和已知输出位须通过回放检查。
排空周期明确在 witness 之外；COI 不可见输出及未知位不能视为已验证。
不再生成跨 witness 串接 sequence。原始 VCD、逐 intent stimulus / sequence 和回放 schedule 均保留。
独立 sequence 是导出数据，不能单靠导出成功宣称通过逐周期回放检查。

模型输出是会被编译执行的 Scala。JSON 校验、类型系统和历史依赖检查均不是安全沙箱；
运行不可信输出前须审查，或使用隔离执行环境，不向执行进程暴露模型密钥等不必要凭据。
复现命令与当前支持范围见 [README](README.md)；历史测量及污染审计保持原样，不重新标记为新框架结果。
