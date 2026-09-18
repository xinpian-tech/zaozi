# RVProbe LTL-only / LLM 输出契约

当前契约：`runtime-ltl-v4`。单轮入口为 `sequence_experiment.py`，闭环入口为 `coverage_flow.py`。
HAVEN 侧 prompt、DSL 和执行方式不变。Stage-1 是固定实验环境，不由本轮模型生成。

## 分工与输入

模型只接收 DUT spec、规范化 IO 类型、LTL skill 和当前覆盖/物理环境证据；
RTL 实现通过只读工具按需读取。初始 prompt 不包含完整 RTL、Scala binding、UT 接线骨架或 runner ABI。

模型只负责在 IO 上表达验证意图：局部 `val`、已有 helper 调用、时序表达式和 `Gen` 调用，不生成函数定义。
`utlib.Ltl` 提供按信号类型/位宽比较常量的 `is`、全零判断 `isZero` 和全一判断 `isOnes`。
skill 只说明这些 API 的语义与调用方式；实现由框架编译提供，不含 DUT 地址或协议时序模板。
IO 表直接列出可用的端口标识符，无需 `io.`。框架按 manifest 生成类型保留的局部别名；
普通端口同名，Scala/API 名称冲突时使用明确列出的 `port_...` 别名，避免捕获 Gen、past 等符号。
这是编译前的 codegen，不替换模型文本，也不通过 given/macro 猜测未定义标识符。
框架确定性生成 imports、唯一 UT、DUT 实例、所有 IO 接线、时钟/复位作用域及执行 runner；
不实现 DUT、不新增场景 Assume、不注入任何设计答案。
输出可以作为目标条件，但激励只驱动输入，输出必须由原始 DUT 产生。

## 输出

优先返回原始 Scala LTL，不要 JSON 或解释。整个回答恰好是一个完整的 Scala 代码围栏时，
允许确定性解包：仅删除外层围栏与块外空白，块内源码逐字节不变。多块、夹带说明、
未知语言标签或缺少闭合围栏均拒绝，不猜测应执行哪段源码。以下是符号语法示意：
`p`、`q` 为 IO Bool 谓词，`gap` 是 Int 拍数，不是任何设计的答案。

```scala
val ordered = p.##(gap)(q)
Gen(ordered, "ordered_events")
```

不输出 imports、模块名、architecture、接线、时钟声明、重复标签列表或 proof 分类。
每个语义 intent 只调用一次 Gen；标签必须是唯一的 snake_case 字符串字面量，框架自动提取。
一份 LTL 含 1..64 个目标，并受当前配对批次上限限制。所有目标组装进同一个固定 UT。
没有新目标时只返回 `STOP`；它不是不可达证明或覆盖闭合声明，不生成空 UT。
旧完整 UT JSON 不再作为模型入口接受，也不自动转换旧实验回答。

Gen 接受硬件 Bool、Sequence、Property，不区分 value/state 等类别。
`p ### q`、`p.##(q)`、`p.##(gap)(q)`、`p.##(lo, Some(hi))(q)` 在连接时自动提升 Bool；
可与 Sequence 混用并连续连接，不改变 Bool 运算、数值类型或 past 类型。其他序列操作仍可显式用 `.S`。
`past(value, cycles)` 是原生 API，保留 Bool/UInt/SInt/Bits 类型，要求 cycles > 0。
框架已提供时钟作用域，但没有自动历史有效性保护；必要历史起点与间隔条件必须写入目标。

## 产物与验收

`attempt-N/response.txt` 逐字节保留完整回答，`response-normalization.json` 记录解包策略、
原回答/块内源码哈希、字符起止偏移和起始行；resume 校验此记录。
`attempt-N/sources/model.ltl` 逐字节保存块内 LTL（裸回答则完全相同）；
`ModelUT.scala`、`DesignBinding.scala`、`Generated.scala` 由框架生成。
`response.json` 是框架内部解析记录，不是模型输出格式。
`model-sources.json` 分别记录 LTL 和生成 UT 的哈希；编译、续跑、采样均核验来源和确定性产物。

保持已有 bubblewrap 编译/lower 隔离、单 DUT 及接线检查、逐目标 JG 求解、
raw 输入回放、原始 LTL 验收和 VCS/URG 覆盖统计。语法检查不是执行安全边界。
其他 Gen 既不合取也不作为该目标的假设；各目标独立求解。
每 intent 默认最多 4 条不同 sequence，采样不改原始 cover 或 witness 长度，
不足时报告实际数量，不补重复样本；编译或得到 witness 本身不等于覆盖收益。

## Prompt 与 RAG

RVProbe 在线生成及修复直接携带冻结的 [rvprobe-skill.md](../rvprobe-skill.md)，
包含 LTL API 的语义、类型、时序用法和通用示例。策略为 `frozen-inline-ltl-skill-v4`。
直接传 skill 原文，不将正文做第二层 JSON 转义；文件名、哈希等 provenance 保存在产物，不重复塞进模型上下文。
同一次配对在 RVProbe 首轮保存 `frozen-skill.json`，后续各轮和修复复用同一内容并校验哈希，
不再调用 `read_skill`，也不伪造 assistant/tool 历史。首个请求即包含 skill 和当前任务。
每轮保持独立对话，但 `read_context(history)` 可读取本次运行中已被仿真接受的模型 LTL；
通过实际已接受 sequence 集合筛选，排除失败候选，不携带旧聊天/推理或其他实验的答案。
真实任务工具交换仍保留协议所需消息字段，skill 文本仍计入输入 token，不声称免费继承。
在线主提示词不再内联 RTL 全文、覆盖报告中的源码片段、完整反馈、基线 DSL 或组件声明。
RVProbe 实现策略为 `compact-evidence-first-ltl-v2`。首个请求附带批准的 RTL 文件目录、
固定物理环境、基线标识及精确覆盖计数；不内联 RTL 正文，不选取设计答案。
完整 coverage 仍按需读取，初始统计不冒充全部缺口信息。仅 RVProbe 使用以下只读工具：

- `list_rtl({})`：列出 manifest 批准的 RTL / HDL include 文件 ID、名称、大小与 SHA-256。
- `search_rtl`：按字面文本检索，返回文件 ID、行号、片段和读取偏移。
- `read_rtl`：优先用 start_line / line_count（默认 80 行，最多 2,048 行）定位，或用 offset / limit 字符分页；两种模式不混用。每页最多 32,768 个 Unicode 字符，用 next_offset 完整续读长行。相关小文件可以一次指定其全部行，不强制拆成 200 行小块。
- `read_context`：分页读取 coverage、environment、baseline、该臂自身 history 或 coverage_reports。coverage 保留全部 gap/count；大段 report_section 以哈希引用单独移入 coverage_reports，不删除原报告。
- `read_coverage`：优先读取无损列式缺口表；相邻同字段行共享常量，按 columns/rows 还原，保持原始顺序、重复行及缺失/null 区别。最多 2,048 行和 24,000 字符，超限保留行偏移续读；原始详细表示仍由 read_context 提供，不按设计或目标筛选。
- `search_rtl_batch`：一次最多 8 个查询，共享 20 个命中返回额度，每个查询独立保留续读偏移。
- `read_rtl_batch`：一次最多 8 个行区间，按文件合并相邻/重叠区间；共用 32,768 字符正文额度，返回原请求索引、结束偏移及未读部分的续读偏移。不静默丢弃超限部分。
- `read_framework`：按 ID 读取冻结的 framework-only 原文/示例，逐次校验源文件哈希；skill 未覆盖的语法才需读取。

不提供任意路径、shell、文件写入或历史实验答案。include 扫描只允许 HDL 后缀，拒绝越界符号链接；
每次读取 RTL 检查固定哈希，内容变化直接失败。提示模型在选择缺口前按需读取 coverage、编写刺激前遵守已提供的 environment，
不要求默认遍历全部 RTL。先识别缺失实现事实并批量取证，再构造 LTL；证据已足够时直接回答，
不强制增加一个取证请求。spec 原文和已读 RTL 保留，不以摘要替代事实，不删除协议历史推理字段。
后续轮小型完整反馈采用同一无损列式 gaps 编码，保持全部行、顺序、重复项及元数据；
内联大小按编码后的长度判断。bins/percent/score 已在 current_feedback 时不再在摘要中重复。
原始完整 JSON 仍保存在 coverage topic；超限反馈和 RTL 仍可分页读取，不截断。
RVProbe 每个对话最多 24 次模型请求、64 次任务工具调用，没有额外的 skill 引导请求。
批量中的每个子查询/区间各计一次工具预算，不因为新增批量接口提高 64 次预算。
`initial-evidence.json` 保存首轮附带证据；`task-context.json` 保存证据清单/哈希，
`task-tool-N-M.json` 保存请求、读取范围、实际返回内容及 budget_cost。

公共证据投影采用 `common-evidence-v7`：HAVEN 保留原生协议、基线 DSL、seq_item 字段及事务契约，
不向 HAVEN 暴露 RVProbe 私有 raw 回放字段；RVProbe 的任务上下文去除这些事务模板及基线 DSL，
只保留 DUT 规格、IO、固定物理环境、覆盖反馈和自身历史，RTL 按需读取。
HAVEN 按原流程内联完整 RTL/反馈；不把上下文访问方式或激励表达能力描述为两侧相同。
不再发送编译后的基线 UVM 源码、完整 blueprint 和派生 structured_spec。省略产物保留在 bundle，记录哈希；
这是显式的公共证据投影，不声称能无损还原被省略内容。HAVEN 不再重复展开同一协议、映射与基线 DSL，
并补入与 RVProbe 相同的原始规格正文；各自本轮覆盖反馈和后续生成历史仍按各臂实际结果维护。
保留的结构化反馈采用无损紧凑 JSON，保留源码字符串内部空白。
RVProbe 服务端不支持工具调用或违反协议时明确失败，不静默回退。HAVEN 保持原 prompt、DSL API/schema 和单次无工具请求，不注入 skill 或任何新任务工具。
每个 HTTP 请求单独记录时间与 usage（后续失败也保留前面成本）；
如供应商提供，还记录推理 token、缓存 token 分项；缺失记为未知，分项不得再次加到 total_tokens。
只记录 reasoning_content 的字符数，不把推理正文写入这些请求统计文件。
两侧记录供应商 `finish_reason`（未提供则 null）、正文长度及 complete/empty/truncated/filtered 状态。
空正文、length 截断或内容过滤不进入LTL 语法/编译修复循环，也不使用网络重试预算自动重生成；
保存 `incomplete-response-N.json` 及全部已知成本后失败。普通 resume 不重复该付费请求，需要显式新建运行。
这不能避免推理预算耗尽，也不是已验证的 token 上限；模型及 sequence 采样预算未改变。新增工具往返同样计入实际成本。
`prompt.json` 保存各节字符/UTF-8 字节数；请求事件记录 payload 哈希、字符数及输出字符数。
`skill-context.json` 记录注入策略与快照，`dialogue-N.json` 保存快照及请求元数据。
skill 哈希进入运行恢复指纹。离线 response-file 不发请求。

离线开销回归可运行 `python experiments/profile_prompt_cost.py --design DESIGN.json --generation OLD_GENERATION_DIR ... --bundle SHARED_BUNDLE.json --out NEW_REPORT.json`。
它核对 bundle 与旧任务的来源、RTL 哈希与 IO 契约；旧 UT RAG 不混入新的表达式目录，对比单条旧/新任务 prompt 的字符数。
不假定旧版重复发送两次完整任务；bootstrap、skill、按需读取交换及重试不计入该文本比较。不调用模型、不修改历史目录。
字符下降比例不等于 token 或金额下降比例；真实输出消耗和覆盖效果仍需付费 A/B 实验测量。

每次 typecheck 失败自动更新运行目录的 `compile-repairs.json`，记录诊断、
相邻尝试的源码 diff 和是否通过编译；无后续修正时保留 pending。
局部修复采用 `local-source-repair-v1`：只发送原始 LTL、IO 类型、skill、物理条件和诊断，
不重发完整 spec、覆盖报告或 RTL，不开放设计搜索/覆盖重新规划工具。
保留源码错误类别、错误码、位置和消息；初始优先展示语法错误并去重，最多 8 项。
完整诊断保持不变，随时可由修复专用 `read_diagnostics` 分页读取；框架 API 仍可按需读取。
明确的响应包装错误使用 `format-only-repair-v1`：只开放完整诊断，不开放框架参考，
提示模型仅修复包装。混合/未知诊断不降格成包装错误，仍保留正常语法/类型修复入口。
修复必须保留原目标及必要时序条件，不删除目标来换取编译通过；框架生成的 UT 仍经过原编译、接线和回放检查。
框架不猜测或自动改写不明确的 LTL 回答。
这些完整修复记录不进入 skill/RAG；审查确认的框架通用规则才更新 skill，
避免历史 DUT 答案污染。历史运行可用 `python experiments/rvprobe_skill.py RUN --out ARCHIVE.json`
另存修复记录，不改写原实验产物。
加 `--recursive` 可汇总目录树内的历史运行；无法识别的旧目录布局单独列出，不推断修复关系。

任务证据和检索资料分开：

1. 初始任务：DUT 原始 spec、IO 类型、物理环境和覆盖统计；按需工具：本轮完整覆盖缺口、自身历史、manifest 批准的 RTL / HDL include 正文。RVProbe 不读取 HAVEN 基线 DSL。
2. framework-only API 原文和可编译通用示例。
3. LTL-only 输出契约；UT 由框架确定性组装。
4. 本轮失败的编译/求解诊断，或上一轮实测覆盖反馈。

行覆盖已满不能单独作为停止理由，仍应处理 condition、toggle、branch、FSM 的剩余缺口。
共享的设计相关初始序列不进入 framework-only RAG；HAVEN 可见原生基线 DSL，RVProbe 仅可见基线标识和统计。

语料版本为 16；`predefined-ltl-helpers-v4` 内联核心 skill 和补充 LTL API 目录。
完整 UT、JSON 外壳、求解/导出流程和 runner ABI 示例不再进入模型参考白名单。
补充参考只包含 Gen 接受类型及序列、逻辑组合、属性、past 的原文摘录，按需读取；
白名单、原文匹配和源文件哈希由 prompt_rag.py 检查。rag.json 保存实际检索内容及来源。
检索 query 只描述框架接口，不包含 DUT 名、覆盖残余或答案。
历史 response、benchmark 操作数、witness、覆盖结论和设计专用不变量均不得进入 RAG。
来源检查不能代替内容审查。skill 优先用符号化 IO 谓词展示同拍、固定/有界延迟、
历史比较及显式历史起点；只讲 API，不提供设计场景、操作数或覆盖答案。
对应时序写法由 `FrameworkGoalExample.scala` 和 `FrameworkLtlTest.scala` 编译及 lower 回归验证。
编译反馈直接转发诊断，不替模型改写源码，也不从 Bool 类型猜测修正方案。
zaozi 的动态成员诊断在源位置报告实际成员名和硬件类型；已知非法 Bool 操作给出精确 API 提示。
优化应比较首次编译成功率、修复次数、输入/输出/推理 token、运行时间及覆盖收益；
不能把表达式较短或提示词字数较少直接等同于总 token 必然低于 HAVEN。

read_coverage 首次使用空参数，后续只传 offset=next_offset；页面大小由框架自动决定，
不再让模型选择几十行的小页。原始顺序与全部缺口仍可完整获取；不默认注入 RTL 正文。

跨轮仅复用同一 run 已返回给模型的 read_rtl/read_rtl_batch 原文：按当前源码哈希、字符偏移和原文逐段校验，合并已读重叠区间，不补入未读邻行。
首轮不附带缓存；后续轮已读内容合计不超过 48,000 字符时直接继承。超限时显式提供范围目录，完整缓存经 read_context(rtl_history) 分页读取，无静默丢弃。
缓存不包含推理、框架示例、覆盖报告、其他 run 的答案或失败 LTL；新覆盖反馈独立读取。原生 HAVEN 流程不变。

后续轮的结构化覆盖 JSON 不超过 32,768 字符时，完整作为 current_feedback 提供；本次已接受的 LTL 历史不超过 48,000 字符时，作为 accepted_ltl 提供。
两者均明确 complete=true，并移除重复的读取工具入口；超限时继续保留原有完整分页工具，不只给前几项或静默截断。首轮仍通过工具获取覆盖/RTL。
已读源码、已接受 LTL 和新覆盖反馈均来自当前 run，不冒充历史 assistant/tool 消息，不携带或删改供应商协议中的推理字段。

不调用模型/求解器的语法与诊断回归：

```bash
nix develop -c mill zaozi.tests.testOnly me.jiuyang.zaozitest.BundleSpec me.jiuyang.zaozitest.ReferableSpec
nix develop -c mill experiments.tests.testOnly FrameworkLtlTest FrameworkExamplesTest
RVPROBE_RUN_TOOL_TESTS=1 python3 -m unittest discover -s experiments -p 'test_sequence_framework.py' -k actual_bool_predicates -v
```

最后一项走真实 bubblewrap/scalac 入口，检查正确表达式通过、错误成员诊断和源码行列可被原样反馈。

## 回放与证据边界

编译通过 ≠ 意图正确；witness ≠ 覆盖闭合；STOP ≠ 不可达证明。
共享事件路径采用 `native-io-ltl-replay-v1`：每条 sequence 独立进程复位，按多时钟事件计划驱动，
从原始已校验 UT 提取不含 DUT 的 Cover 观察器，在真实四态 IO 上验证 witness 窗口内命中。
目标不得重新编写；源码和 prepared job 绑定校验。
当前 `independent-dut-v1` 边界下，RVProbe 直接 raw 回放求解得到的 DUT 输入事件，绕过 HAVEN 的事务调度和响应 BFM；
只保留明确声明的时钟、复位、静态连接等物理条件。输出由真实 DUT 产生，不能任意驱动。
HAVEN 仍走原生事务/BFM 接口；共享的是原始 DUT、固定基线和覆盖统计规则，不是模板限制。
旧 `shared-event-environment-v1` 边界仍由 BFM 决定外设响应，不与当前独立边界混报。
直接刺激、时序和复位保持严格检查；完整波形差异记录为诊断，不冒充原始 LTL 目标。
目标未命中仍失败；这不证明整个形式轨迹等价，也不证明 DUT 功能正确。

以下逐位检查描述适用于独立 cycle 诊断，以及没有原始 LTL 来源证明的旧回放：
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

LTL 语法校验、类型检查和哈希本身不是安全边界；执行隔离由 Linux bubblewrap 提供，缺失时拒绝运行。
接线检查保守地接受直接连接/别名和复位反相，不声称判断任意 SV 语义等价；不支持的结构拒绝。
这不是防御内核漏洞或资源耗尽的完整多租户安全平台。工具子进程不需要模型凭据。
实验自动记录 UTC 起止、墙钟/活动耗时、逐阶段和逐目标耗时、请求次数与服务端 token usage；未知用量不冒充零成本。
复现及对比命令见 [README](README.md)，历史记录不改写或重新标记。
