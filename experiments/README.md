# rvprobe：共享 HAVEN 实验流程与 UT 生成后端

当前流程只做验证和 sequence 生成，不在 zaozi 中复刻 benchmark DUT。

当前主实验入口是 `coverage_flow.py prepare/run`（`haven-shared-v1`）：HAVEN 与 rvprobe 共用一次
Stage-1 产物、初始序列、testbench、seed、覆盖反馈和多指标停止规则，只替换激励生成后端。
准备命令不执行模型或 EDA；正式运行需要显式 `run`。范围、修复、完整命令和验证边界见
[共享流程说明](../docs/date2027/haven-shared-flow.md)。这次重构没有运行新实验。

以下 UT/求解/逐周期工具仍可独立使用；旧独立闭环已移到 `cycle_diagnostic.py`，不是 HAVEN 对照入口。

当前框架契约为 `runtime-ut-v5`。此前 v4 的单 UT / 无模型 Assume 在线 ALU 测量见[历史报告](../docs/date2027/single-ut-alu.md)，不重新标记为 v5 结果。
本次 v5 在线结果及耗时/token 对比见[ALU v5 实验报告](../docs/date2027/alu-v5-online.md)。

```text
原始 RTL + IO manifest ──────────→ 一个共享的 VerilogWrapper
当前 RTL / 覆盖残余 + 框架 RAG ──→ LLM 的完整 UT Scala 源码
                                      ↓
                         原样保存一个 ModelUT.scala
                                      ↓
                      UT 内多个 Gen → scalac → 逐目标 JasperGold witness
                                      ↓
                       ABI → stimulus（另导出独立 sequence）
                                      ↓
                       逐周期 UVM 回放检查 → VCS / URG
                                      ↓
                        更新残余 → 下一轮 / 明确停止
```

## 代码归属

- `stdlib/`：恢复为 `ut` 基线内容，不新增实验模块或测试。
- `sequence_framework.py`：设计无关的 IO binding 和 runner 生成器；原样保存模型 UT，不生成 UT 主体。
- `utlib/src/Gen.scala`：唯一的目标生成入口；直接接受 Bool / Sequence / Property，自动保护跨拍历史。
- `coverage_flow.py` / `haven_shared.py`：共享 HAVEN 组件、初始序列、覆盖策略与两个激励后端。
- `cycle_diagnostic.py` / `cycle_replay.py`：独立逐周期诊断，不包含 DUT 实现，不作为配对比较入口。
- `sequence_experiment.py`：单轮 prompt、模型调用、编译 / 求解反馈；不是兼容转发入口。
- `isolation.py` / `ut_validation.py`：断网编译/lower 与保守的 DUT 接线检查；缺少 bubblewrap 时拒绝执行。
- `src/TrustedSolver.scala`：只消费已检查的数据，不加载模型 class；逐目标求解和 checkpoint。
- `goal_coverage.py`：逐目标独立统计 witness 与固定 drain 覆盖。
- `run_records.py` / `compare_runs.py`：统一耗时、请求/token 账本和跨实验对比。
- `rag_ablation.py`：共享一次新 baseline 和同一 VCS build 的固定任务 RAG 对照。
- `designs/alu.json`：原始 RTL 路径、端口、时钟 / 复位、sequence item 类型及协议说明，没有用例答案。
- `designs/alu_replay.json`：复位长度、请求控制、排空长度及最小初始化；默认没有随机基线流量。
- `fixtures/haven/`：迁移过来的第三方 RTL，内容未改。
- `src/`：固定入口和通用 RAG 示例，没有设计专用 UT 或共享 Generated.scala。
- `proofs/`：独立、人工审查的可达性属性，不进入 LLM / RAG，也不自动排除残余。
- `<run>/sources/` 或 `<run>/attempt-N/sources/`：框架的 `DesignBinding.scala`、`Generated.scala`，以及模型的一个 `ModelUT.scala`。

一个设计只维护一份接口定义，每轮模型生成恰好一个 UT，共用固定 wrapper。
目标数量由模型决定，全部写成该 UT 内的 Gen。`module` 指定 UT object，`generationLabels` 完整列出目标属性名。
模型不得新增 Assume / restrict；场景输入与时序条件写入各自 Gen。时钟与复位由固定 runner 管理。
编译后检查 UT 不含环境假设，lower 一次再逐目标求解；其他 Gen 断言不参与该目标的求解。
闭环与 RAG 对照默认 `--sequences-per-intent 4 --sampling-seed 20260906 --sampling-time-limit 30s`。
每个 Gen 保留原解并最多增加 3 个去重后的解；样本不足或失败会明确记录，不重复凑数。
采样不修改模型 UT、不新增 Assume；每条序列单独检查后累计回放，模型目标数与实际序列数分开记录。

## 独立逐周期诊断（不是共享 HAVEN 实验）

从仓库根目录运行；输出目录必须是新的。以下 ALU 冒烟使用保存的完整 UT（其目标为 `io.done`），
不调用模型，也不依赖以前的 `out/`、HAVEN bench 或覆盖报告：

```sh
python3 experiments/cycle_diagnostic.py \
  --replay-config experiments/designs/alu_replay.json \
  --response-file experiments/tests/fixtures/completion_intent.json \
  --out out/experiments/my-alu-cycle-smoke
```

该命令包含 CIRCT IO 检查、生成 UVM bench、VCS baseline、动态 UT 编译、JG 求解、
逐周期采样校验、VCS / URG、残余对比与停止状态。需要 Nix、JG、VCS、URG 及许可证。
`--eda-shell /path/to/wrapper` 可替换本机 EDA 环境，wrapper 接受 `-c COMMAND`。
工具与许可证是环境依赖；不需要未入库的设计/bench 数据。scoreboard 不作算术正确性声明。

在线实验去掉 `--response-file`，按需加 `--env-file /path/to/provider.env`：

```sh
python3 experiments/cycle_diagnostic.py \
  --replay-config experiments/designs/alu_replay.json \
  --rounds 3 --attempts 3 --patience 1 \
  --env-file /path/to/provider.env \
  --out out/experiments/my-alu-cycle-live
```

`--rounds` 限制覆盖迭代，`--attempts` 限制每轮编译/求解反馈修正；`--patience` 是连续没有新增覆盖行的轮数。
可重复传 `--response-file`，按顺序验证多轮离线流程；用完后明确停止，不偷偷转为在线调用。
`completed` 表示流程结束，不表示覆盖已闭合；检查 `coverage_closed` 和 `stop_reason`。
无进展、轮数耗尽、保存回答用尽及模型主动 `stop` 有独立停止原因。模型停止时不需要编造 UT/Gen。
每个 Gen 的失败单独保存，其他成功目标继续；编排、基础设施或累计回放不一致才使整个 flow `failed`。
`pending-proofs.json` 只收集待证信息，不自动排除任何未覆盖行。

每个 flow 只编译一次 VCS bench，各轮重放相同 baseline 前缀和累计 witness，覆盖总 bin 数及残余单调性均检查。
另外每个目标独立测量 baseline + witness 和 baseline + witness + drain，在 `round-N/goal-coverage.json` 中
分开记录 `witness_closed_lines`、`drain_added_lines`、solver 状态及检查结果。独立收益不能相加替代累计覆盖。
默认 ALU 初始化仅 2 拍复位 + 1 拍空闲，不触发 start，也不生成随机操作数。
初始化覆盖单独记录；LLM 的第一轮任务是初始化后的残余，而不是随机测试后的难例子集。
随机流量仅作为显式选择的对照：将配置的 baseline 改为
`{"mode":"random","seed":1,"samples":1024,"active_cycles":1,"idle_cycles":16}`。
请求控制由顶层 `request` 定义；随机值填充其余输入。不同初始化方式或历史 bench 的分数不能直接混比。

主要产物：`manifest.json`、`design.json`、`build/rvprobe_cycle.sv`、`baseline/`、
`round-N/feedback.json`、`round-N/generation/`、`replay-N/schedule.json`、
`replay-N/replay-validation.json`、URG 报告及总 `summary.json`。采样记录在各次 `sim.log`。

## 单独验证生成器（无模型）

在仓库根目录运行；每次换一个新的输出目录。这个独立 synthetic RTL 只是通用框架的回归 fixture，
不是 HAVEN 实现，也不在 RAG 来源白名单中。

```sh
nix develop . -c python3 experiments/sequence_framework.py \
  --design experiments/tests/fixtures/tiny_design.json \
  --response-file experiments/tests/fixtures/tiny_intents.json \
  --out out/experiments/my-runtime-check --check-io

nix develop . -c python3 experiments/ut_harness.py \
  out/experiments/my-runtime-check/sources \
  --out out/experiments/my-runtime-check/solve --compile-only

nix develop . -c env ZAOZI_EDA_SHELL="$PWD/experiments/eda-shell" \
  python3 experiments/ut_harness.py out/experiments/my-runtime-check/sources \
  --out out/experiments/my-runtime-check/solve
```

前两步只需要 Nix 开发环境，不需要 LLM 或商业 EDA。最后一步需要 JasperGold 和许可证；
`eda-shell` 针对原主机，迁移时配置 `SNPS_FHS_ENV` 或换成自己的 wrapper。
保存的一份完整 UT 含四个 Gen，分别测试输入谓词、输出谓词、时序序列和跨拍数据条件，不进行在线模型调用。

生成文件和输入哈希在 `sources/`；输出包括 `solve/report.json`、单 UT 的 ABI 和 lowered SV、
每个目标的 JG 日志、witness VCD、`stimulus.json` 和独立 sequence。不生成跨 witness 串接 sequence，不可行目标不伪造序列。
`model-sources.json` 记录每份模型源码的 SHA-256，编译前校验；JSON 解码后源码逐字节保存，不改写内容或补 UT 壳。
每个目标的 `generated`、`infeasible`、`unknown`、`error` 分开记录；UT 总状态为 `generated / partial / no-witness`。
compile-only 通过不代表已求解。
后端明确选择本轮生成属性，不把原始 RTL 中其他 cover 的 witness 当作本轮结果。
JG 的 wrapper lowering 禁用会删除恒真断言的优化，确保恒假目标仍交给求解器报告不可达；
属性缺失仍是错误，不通过猜测或旧的否定语法启发式补出结果。

## 接入设计与 IO

参照 `designs/alu.json` 填写实际 RTL 文件和端口，路径相对 manifest 所在目录。
支持平铺 input / output、bits / bool / sint、整数参数、一组时钟和复位、至多一个 include 根目录。
时钟角色和复位极性须显式声明，不能仅由 Verilog 位宽推断。
`--check-io` 调用 CIRCT 的 `circt-verilog --ir-hw`，核对 elaborated top 的端口名、方向和位宽，
不使用正则解析 Verilog 源码。这个可选 preflight 当前不支持参数覆盖；参数由后端 elaboration 检查。
尚未实现自动推断协议、多时钟、inout 或任意结构化端口，也没有声称旧的所有设计已完成迁移。

JasperGold 使用原始 RTL 和 wrapper 的 lowered SV；zaozi 只新增接线、验证谓词及历史监测寄存器，
不生成 DUT 的替代实现。`--rtl` 同时替换 prompt 和求解输入。求解前核对 RTL 及 include 文件哈希，
不覆盖已有结果报告。

Prompt 提供 manifest 中全部 RTL 和 include 文件的完整内容、文件名、行号与哈希，不再截取残余附近的短窗口。
超过 1,000,000 字符时明确失败，不悄悄截断；仍需正确选择顶层覆盖模块，文件内行号不是全工程唯一标识。

## 模型与回放

`sequence_experiment.py` 接收 `--design`、`--modinfo`、`--out`，实现单轮生成和反馈修正。
当前生成契约为 `runtime-ut-v5`。LLM 返回 JSON，其中唯一的 `ut` 包含 `module`、`generationLabels`、`source`，另有 `proofObligations`。
没有新目标时允许返回 `{"stop":{"reason":"具体原因"},"proofObligations":[]}`；不生成空 UT，不将停止当作不可达证明。
`source` 是完整 Scala UT：包含 imports、@generator object、architecture、DUT 接线、时钟/复位上下文和 Gen 调用。
框架只提供一个共享 wrapper、固定 runner 和执行工具，不再接收 `expression` 后自动套成 UT。
跨拍条件用原生 `past(predicate, cycles)`，接受 Bool 而非 Bits；不自动保护历史有效性。
`Sem`、`Generate`、`Txn` 已删除，不保留兼容层；历史回答不自动转换或混入当前在线结果。
契约及 framework-only RAG 边界见 [PROMPT](PROMPT.md)。
保存的 `--response-file`、`--prompt-only`、本地检索均不需要模型凭据。

`cycle_diagnostic.py` 和 `rag_ablation.py` 共用独立的 `cycle-replay-v1` bench：每条 witness 前独立复位，下降沿驱动，下一上升沿通过 clocking input `#1step` 采样，
避开 DUT 在时钟沿上的 blocking / NBA 更新竞争；
一 beat 对应一周期，不等待 done/ready。实际端口值、复位电平、采样数和相邻时刻必须与 schedule 一致。
JG 在生成轨迹前设置 `set_trace_optimization standard`，重建并导出完整设计轨迹，
不使用默认 `ar` 模式下仅含目标影响范围（COI）的轨迹作输出期望。
否则，目标只引用 `flags[0]` 时，向量里其他位即使显示为 0/1，也不一定是完整 RTL 的仿真值。
结果中的 `witnessContract: jg-full-design-v1` 和 `witnessSha256` 绑定导出方式与实际 VCD；
回放拒绝缺少此记录的旧轨迹或哈希不符的文件，旧回答可以重新求解，不直接复用旧 VCD。
完整轨迹中可见输出的已知位与同一拍仿真输出比较，包括目标未引用的位；
未知位保持 mask，不把 X 当作零值证据。这仍不等于全状态等价或算术正确性证明。
每条 witness 后可配置排空周期，保留数据并撤销请求控制；排空不计入 formal witness。
当前支持单上升沿时钟、manifest 所述复位和稳定的复位后初态。不能由有限拍复位确定的自由初态可能导致回放失败，
失败不会被记成覆盖成功。多时钟、任意握手事务压缩或全局状态恢复不在此契约内。

正式覆盖回放只消费逐 Gen 目标的 stimulus / VCD，使用上述 UVM 传输后端。
独立 sequence 是数据导出，接入自定义 bench 时仍须自行验证驱动时刻和复位语义。
历史手写 UT、driver、bench、`--legacy`、旧 CLI 别名及旧消融格式适配已删除；复现旧数据请使用相应 Git 版本。
模型源码在 Linux bubblewrap 中断网编译和 lower；没有宿主文件或模型凭据访问权限，只能写私有目录。
可信执行器检查一个原始 DUT、精确边界端口、直接/别名连接、复位极性和无额外驱动，随后独立运行 JG。
任意复杂 SV 的语义等价、多租户资源配额和内核漏洞防御不在这个安全边界的保证范围内。

## 恢复与实验成本记录

`cycle_diagnostic.py`、`sequence_experiment.py`、`rag_ablation.py` 均支持原命令加 `--resume`；新的共享 `coverage_flow.py` 暂不支持恢复。
只允许相同 RTL、配置、源码版本、保存回答和预算；失败产物保留，不覆盖或混合不同条件。
已有原始回答不会重新请求模型；成功 goal checkpoint 验证 witness/stimulus 哈希后复用，未完成目标可继续求解。
已完成的回放检查 schedule 与产物哈希后复用；未完成目录保留为 `*.interrupted-N`。
`--request-retries` 默认 3，限定同一源码尝试的传输重试总次数；只重试超时/临时 HTTP 错误，不更换模型。
源码修正预算与请求重试预算分开；基础设施失败不通过让模型另写 UT 来掩盖。

每次实验保留 `events.jsonl`、`summary.json` 和 `comparison.json`：

- UTC 起止时间，跨恢复的总墙钟时间、活动会话时间和最后一次会话耗时。
- 请求次数、实际报告的模型名、输入/输出/总 token；失败、重试、中断请求仍计入分母。
- 编译、lower、求解、逐目标 JG、VCS 和 URG 的次数与耗时。嵌套阶段不能相加当作墙钟时间。
- RTL/框架哈希、模型/温度、RAG、基线和预算，最终覆盖与新覆盖行数。

`usage_reported` 只合计服务端已报告用量，`requests_without_usage` 明示未知请求；超时不代表免费。
离线回归请求数为 0，不能衡量模型/RAG 效果；未提供价格时不编造货币成本。

```sh
python3 experiments/compare_runs.py out/experiments/run-a out/experiments/run-b
```

输出可机器读取的对比 JSON；基线、任务、框架或预算不同时标出 `same_comparison_basis: false`。

## 固定 UT 的多序列采样对照

不重新调用模型，复用一次已完成实验的 `attempt-*/solve` 和原始 replay 配置：

```bash
python3 experiments/witness_sampling.py \
  --source-solve out/experiments/alu-v5-live-20260906T143755Z/round-1/generation/attempt-1/solve \
  --config experiments/designs/alu_replay.json \
  --out out/experiments/alu-sampling-1-4-16-64 \
  --counts 1 4 16 64
```

- 每个 intent 的第一个样本是原始 witness；其他样本通过 JasperGold Visualize 的
  `-force -soft` 输入偏好与 `-replot` 得到。原 cover 保持为硬目标，不修改 UT、不添加环境 Assume。
- 输入偏好按 seed、label 确定，逐输入端口、逐拍生成；没有 ALU 特殊操作数或历史答案。
  这是启发式多解采样，不是均匀随机采样，也不枚举所有解。
- 固定每个 intent 的原 witness 长度，按完整输入序列去重。最多尝试所需新增数量的两倍；
  不足时报告真实样本数与 `partial`，不重复填充到目标数量。
- 各档使用同一池的嵌套前缀，共享一份 VCS bench；分别报告完整回放与去掉 drain 的回放覆盖。
  全部新样本要求完整设计 VCD、已知输入位、原 cover 配置与回放输出检查通过。
- `manifest.json` 固定 UT、RTL、配置、框架哈希，`pool.json` 保存每个样本来源、哈希与 replot 耗时。
  `summary.json` / `comparison.json` / `events.jsonl` 记录 UTC、总耗时、工具阶段与新增模型 token（零）。
  各档的 replot 时间不含工具启动、初次 prove 与导出成本，不能当作各档独立运行的总时间。
- 支持 `--resume` 复用经哈希检查的完成目标；`--labels` 仅用于显式缩小冒烟范围。

这是固定 UT 的预算对照入口；普通覆盖闭环与 RAG 对照现已复用相同采样实现，默认每个 Gen 请求 4 条不同 witness。
固定 ALU UT 的四档实际覆盖、耗时与审计见[多序列实验报告](../docs/date2027/alu-multi-sequence.md)。

## 固定任务 RAG 对照

```sh
python3 experiments/rag_ablation.py \
  --replay-config experiments/designs/alu_replay.json \
  --samples 5 --attempts 1 --env-file /path/to/provider.env \
  --out out/experiments/my-alu-rag
```

一次 CIRCT preflight、一次 VCS 编译、一次新 baseline。两组使用同一份初始残余，每组 5 个样本，
只改变 `--rag off/local`，交替调用顺序；每个样本回放 baseline + 自己的 witness，不累积另一组的结果。
默认共 10 次模型请求；增加 `--attempts` 会允许等预算反馈修正，实际调用数可能增加。
这是一项固定任务对照，不是闭环多轮对照。summary 保留失败 / 缺失样本的分母、已报告 token、
检查通过的回放数量和完整配对差值；待证元数据不是成功证明，缺失测量不填成零收益。

离线验证两组接线时去掉 `--env-file`，加 `--samples 1 --response-file experiments/tests/fixtures/completion_intent.json`。
两组使用同一个保存回答，仅验证框架一致性，不衡量模型或 RAG 效果。当前工具不读取旧 bench 或旧消融结果格式。

## 回归

```sh
python3 -m unittest discover -s experiments -p 'test_*.py' -v
nix develop . -c mill --no-server experiments.tests.testForked
nix develop . -c mill --no-server utlib.tests.testForked

RVPROBE_RUN_TOOL_TESTS=1 RVPROBE_RUN_JG_TESTS=1 \
  python3 -m unittest discover -s experiments -p 'test_sequence_framework.py' -v

RVPROBE_RUN_REPLAY_TESTS=1 \
  python3 -m unittest discover -s experiments -p 'test_cycle_replay.py' -v
```

`test_sequence_framework.py` 的工具测试实际检查 CIRCT IO、正确 / 错误端口和错误类型的编译、统一目标的求解及导出。
另用 synthetic IO 编译 Bool、Sequence、Property 和带保护的历史引用，验证错误的 Bool `&&` / `.asUInt`
确实被拒绝且触发框架提示；Bits 和旧 Sem 分类表达式不能作为 Gen 目标。
utlib 的类型测试验证 Gen 外没有历史上下文。这些测试输入不进入 RAG。
只测 CIRCT / scalac 时省略 `RVPROBE_RUN_JG_TESTS=1`。工具测试产物保存在
`out/experiments/runtime-ut-regression-*/`。没有任何测试向模型服务发请求。

旧结果和污染审计保持原样，参见 [DATE 总览](../docs/date2027/README.md)；
它们不代表当前 `runtime-ut-v5` 的在线模型或 RAG 效果。

回放工具测试对 synthetic 外部 RTL 的不同目标表达式实际求解并回放，
检查历史深度、历史采样值、时序 / Property 下的有效性保护、区间延迟、重复序列和不同目标间的隔离。
另有只引用一个输出位的目标，以及 `split_output.v` 中独立按位赋值的回归，
要求导出并验证整个输出值；随后故意改错目标未引用的输出位，
要求仿真失败。它们是框架回归，不进入 RAG，也不计作在线样本。
