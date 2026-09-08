# rvprobe 完整 UT / LLM 输出契约

当前契约：`runtime-ut-v5`。单轮入口为 `sequence_experiment.py`，闭环入口为 `coverage_flow.py`。
外层闭环现为 `haven-shared-v1`：两组共用 HAVEN Stage-1 基线与多指标反馈；独立周期诊断入口为 `cycle_diagnostic.py`。
框架只接受完整 UT 源码，不再接受 expression-only JSON，也不自动转换历史回答。

## 分工

- 人提供原始 RTL、版本化 IO / 时钟 / 复位 manifest 及协议说明。
- 框架生成一个共享的 VerilogWrapper 和固定执行 runner，不实现 DUT，也不生成 UT 主体。
- LLM 每轮编写恰好一个完整 Scala UT：imports、@generator object、architecture、DUT 实例、所有接线、时钟/复位上下文和多个 Gen 调用。
- 模型不得新增 Assume、restrict 或其他全局环境限制；场景条件与时序关系放在各自 Gen 中。固定 runner 负责时钟与复位初始化。
- 框架原样保存源码，执行 scalac、JasperGold、ABI/stimulus 导出、逐周期 UVM 回放与 URG 覆盖反馈。
- 共享实验先运行两边相同的 HAVEN Stage-1 初始序列；不由 rvprobe 再生成另一套随机基线。
  只有独立周期诊断默认使用 2 拍复位和 1 拍空闲。每条 witness 的复位前缀仍按版本化配置执行。

每轮源码保存在 `attempt-N/sources/`。`DesignBinding.scala` 和 `Generated.scala` 由框架生成；
`ModelUT.scala` 是 JSON 解码后的模型原文，逐字节保存，哈希记录在 `model-sources.json`，编译前复核。
这些文件在 bubblewrap 隔离环境中成为显式 scalac 输入；活动 classpath 不含 stdlib。
编译和 elaboration 断网，只读挂载工具链与本轮源码，仅私有工作目录及临时目录可写。
原始 RTL 不加载到模型 JVM；接线检查通过后，另一个不含模型 class 的可信 JVM 执行 JG。

## 输出

只返回一个 JSON 对象，恰有 `ut` 和 `proofObligations`。以下是结构示意，不是可执行示例：

```json
{
  "ut": {
    "module": "UniqueUT",
    "generationLabels": ["goal_one", "goal_two"],
    "source": "完整 Scala 源码，包括 imports 和唯一的 @generator UT object"
  },
  "proofObligations": []
}
```

每个 UT object 继承 `Generator[RunParameter, RunLayers, RunIO, RunProbe] with UT[RunParameter, RunIO]`。
模型自行实现 `architecture(parameter: RunParameter)`，使用 `summon[Interface[RunIO]]` 和
`ImportedDut.instantiate(parameter)`，按 manifest 连接 DUT 输入、输出、时钟和复位。
prompt 提供完整 DesignBinding.scala 及复位极性，模型不得复制或替换该 binding。

每个目标调用一次 `Gen(goal, "属性名")`，全部放在同一个 UT 中；`generationLabels` 必须完整列出所有标签且不重复。
目标数由模型决定，接受 1..64 个。UT 编译 / lower 一次；逐目标只保留对应的生成断言，其他目标既不合取也不作为假设。
闭环与 RAG 对照默认每 intent 请求 4 条不同 sequence：保留原 witness，框架再用软输入偏好寻找最多 3 个额外解。
模型对一个语义 intent 只写一次 Gen，不要为了采样数量复制 Gen，也不要放宽必要的场景条件。
采样保持原 cover 与 witness 长度，不添加环境 Assume；不足 4 条时报告实际数量，不重复填充。
采样失败保留原始成功 witness，单条回放失败不丢弃其他成功序列。数量、seed 与求解时限进入 manifest 和恢复指纹。
编译后的 UT SV 再检查不含 assume / restrict，所有断言与声明标签完全一致；缺失、额外或重名属性都会失败。
`module` 是实际 Scala object 名。源码不能声明 package 或替换 Generated runner。
归档 label 使用小写 ASCII snake_case，两类记录之间也不可重名。
proof 项为 `{"label":"pending","reason":"待独立验证的精确矛盾"}`，不是已证明不可达。

没有新目标时，返回 `{"stop":{"reason":"停止原因"},"proofObligations":[]}`，不生成 UT，不补凑 Gen。
停止不代表覆盖闭合。Gen 的 `generated / infeasible / unknown / error` 独立记录；成功 witness 不因其他目标失败而丢弃。

完整源码可以组织局部谓词、验证辅助函数和监测逻辑。Gen 接受硬件 Bool、Sequence、Property，
不区分 value / state 等类别。Bool 用 `& / | / !`，不是 Scala 的 `&&`；Bool 没有 `.asUInt`。
`.S` 将 Bool 转为时钟序列，`a.S ### b.S` 表示相隔一拍，`a.S.##(delay)(b.S)` 表示固定延迟。

UT 自行声明 ClockEvent；past 使用该时钟事件。
`past(predicate, cycles)` 返回过去某拍的 Bool 条件，不接受 Bits。无自动复位初始化或历史有效性保护；需要真实历史时，在 Gen 中显式描述从有效起点到目标的时序。
不要把这种保护解释为任意无限时域 LTL 都受支持。蕴含可能因前件从未出现而成立，不能单靠蕴含要求生成事务。

## Prompt 与 RAG

任务证据和检索资料分开：

1. 本轮多指标覆盖缺口、共享的规格/协议/初始序列、manifest 的全部 RTL / include 文件全文及行号和哈希、IO 类型和 binding。
2. framework-only API 原文和可编译通用示例。
3. 完整 UT 源码及元数据契约。
4. 本轮失败的编译/求解诊断，或上一轮实测覆盖反馈。

行覆盖已满不能单独作为停止理由，仍应处理 condition、toggle、branch、FSM 的剩余缺口。
共享的设计相关初始序列属于本轮任务证据，不进入 framework-only RAG。

语料版本为 11。默认检索完整 UT 接线、源码 JSON 封装和目标表达式三个示例；
语料还保留通用求解/导出示例。完整 UT 示例展示一个 UT 中两个 Gen，不含 Assume，只有通用外部接口与验证接线，没有 DUT 实现。
必须适配任务给出的 Run* 类型、ImportedDut 和端口，不能把示例 binding 或符号参数当作任务答案。

白名单、原文匹配和源文件哈希由 prompt_rag.py 检查；示例直接读取实际参与编译的源码。
检索 query 只描述框架接口，不包含 DUT 名、覆盖残余或答案。
历史 response、benchmark 操作数、witness、覆盖结论和设计专用不变量均不得进入 RAG。
来源检查不能代替内容审查。编译反馈只补充框架 API 提示，不替模型改写源码。

## 回放与证据边界

编译通过 ≠ UT 正确；witness ≠ 覆盖闭合；proofObligation ≠ 不可达证明。
每条 witness 独立复位、一拍一驱动，通过 clocking input #1step 在上升沿前采样，不等待 done。
JG 使用 `set_trace_optimization standard` 导出完整设计轨迹，回放要求
`jg-full-design-v1` 及匹配的 VCD SHA-256，拒绝旧 COI 轨迹。
实际输入、时序、复位及可见输出的已知位必须通过检查；未知位和排空不视为 formal 证据。
只使用原始 RTL，模型不得复刻 DUT、调用求解器/宿主进程、读取文件或导入历史 benchmark 模块。

每轮累计回放同一初始化前缀与本次实验产生的 UT witness，用 URG 测量增量和残余。
每个 Gen 的每条 sequence 还独立回放“固定 baseline + witness”和“固定 baseline + witness + drain”，分别记录覆盖行和输出检查。
记录中的 intent_label 对应原始 Gen，sample_index 区分其多条序列，不把序列数误计为模型目标数。
`witness_closed_lines` 与 `drain_added_lines` 分开反馈；不同目标的独立收益可能重叠，不能直接相加。
在线默认最多 3 轮，每轮最多 3 次源码修正；单目标回放失败单独记录，其他目标继续。
累计回放/工具基础设施失败停止并保留产物，使用相同参数加 `--resume` 恢复。
离线保存回答仅验证流程，用完后停止，不转为在线调用。不同初始化和输出契约不能直接混比。

JSON 校验、类型检查和哈希本身不是安全边界；执行隔离由 Linux bubblewrap 提供，缺失时拒绝运行。
接线检查保守地接受直接连接/别名和复位反相，不声称判断任意 SV 语义等价；不支持的结构拒绝。
这不是防御内核漏洞或资源耗尽的完整多租户安全平台。工具子进程不需要模型凭据。
实验自动记录 UTC 起止、墙钟/活动耗时、逐阶段和逐目标耗时、请求次数与服务端 token usage；未知用量不冒充零成本。
复现及对比命令见 [README](README.md)，历史记录不改写或重新标记。
