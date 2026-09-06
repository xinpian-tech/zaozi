# rvprobe：外部 RTL → 动态 UT → sequence

当前流程只做验证和 sequence 生成，不在 zaozi 中复刻 benchmark DUT。

```text
原始 RTL + IO manifest ──────────→ 一个共享的 VerilogWrapper
当前 RTL / 覆盖残余 + 框架 RAG ──→ LLM 的目标表达式 JSON
                                      ↓
                           每条意图自动生成一个 UT
                                      ↓
                           Gen(expr) → scalac → JasperGold witness
                                      ↓
                           ABI → stimulus → UVM sequence
                                      ↓
                       逐周期 UVM 回放检查 → VCS / URG
                                      ↓
                        更新残余 → 下一轮 / 明确停止
```

## 代码归属

- `stdlib/`：恢复为 `ut` 基线内容，不新增实验模块或测试。
- `sequence_framework.py`：设计无关的 IO binding、UT 和 runner 生成器。
- `utlib/src/Gen.scala`：唯一的目标生成入口；直接接受 Bool / Sequence / Property，自动保护跨拍历史。
- `coverage_flow.py` / `cycle_replay.py`：闭环编排和根据端口生成的逐周期 UVM bench，不包含 DUT 实现。
- `sequence_experiment.py`：单轮 prompt、模型调用、编译 / 求解反馈；不是兼容转发入口。
- `rag_ablation.py`：共享一次新 baseline 和同一 VCS build 的固定任务 RAG 对照。
- `designs/alu.json`：原始 RTL 路径、端口、时钟 / 复位、sequence item 类型及协议说明，没有用例答案。
- `designs/alu_replay.json`：复位长度、排空长度、固定种子基线流量；没有覆盖目标答案。
- `fixtures/haven/`：迁移过来的第三方 RTL，内容未改。
- `src/`：固定入口和通用 RAG 示例，没有设计专用 UT 或共享 Generated.scala。
- `proofs/`：独立、人工审查的可达性属性，不进入 LLM / RAG，也不自动排除残余。
- `<run>/sources/` 或 `<run>/attempt-N/sources/`：本轮生成的 `DesignBinding.scala` 和 `Generated.scala`。

一个设计只维护一份接口定义，同一轮所有意图共用一个 wrapper。UT 数量取决于本轮意图数，
不是预先为每种场景维护一份 UT 源文件。
用户 label 用于结果归档；SV 属性使用独立的 `rvprobe_generated_N` 名称，避免与表达式里的局部寄存器重名。

## 一条命令运行覆盖闭环

从仓库根目录运行；输出目录必须是新的。以下 ALU 冒烟只使用保存的接口谓词 `io.done`，
不调用模型，也不依赖以前的 `out/`、HAVEN bench 或覆盖报告：

```sh
python3 experiments/coverage_flow.py \
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
python3 experiments/coverage_flow.py \
  --replay-config experiments/designs/alu_replay.json \
  --rounds 3 --attempts 3 --patience 1 \
  --env-file /path/to/provider.env \
  --out out/experiments/my-alu-cycle-live
```

`--rounds` 限制覆盖迭代，`--attempts` 限制每轮编译/求解反馈修正；`--patience` 是连续没有新增覆盖行的轮数。
可重复传 `--response-file`，按顺序验证多轮离线流程；用完后明确停止，不偷偷转为在线调用。
`completed` 表示流程结束，不表示覆盖已闭合；检查 `coverage_closed` 和 `stop_reason`。
无候选、仅有待证信息、无进展、轮数耗尽都有独立停止原因；工具错误和回放不一致为 `failed`。
`pending-proofs.json` 只收集待证信息，不自动排除任何未覆盖行。

每个 flow 只编译一次 VCS bench，各轮重放相同 baseline 前缀和累计 witness，覆盖总 bin 数及残余单调性均检查。
新基线是固定种子的通用脉冲流量，不等同于历史 bulk/FP bench，不能直接比较两个基线的绝对分数。

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
四条保存的目标分别测试输入谓词、输出谓词、时序序列和跨拍数据条件，全部由同一个 Gen 处理，不进行在线模型调用。

生成文件和输入哈希在 `sources/`；输出包括 `solve/report.json`、每条意图的 ABI、lowered SV、
JG 日志、witness VCD、`stimulus.json` 和独立 sequence。不生成跨 witness 串接 sequence，空 / 待证结果不伪造序列。
`generated`、`infeasible`、`unknown` 分开记录；compile-only 通过不代表已求解。
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

当前 coverage prompt 的源窗口取 manifest 的第一个 RTL 文件。多文件设计须让残余对应这个文件，
或补充显式的残余文件映射后再做实验；端口定义不自动完成多文件覆盖目标定位。

## 模型与回放

`sequence_experiment.py` 接收 `--design`、`--modinfo`、`--out`，实现单轮生成和反馈修正。
当前生成契约为 `runtime-ut-v2`。LLM 返回 JSON，其中每个 `expression` 是真正参与编译的硬件 Bool、Sequence 或 Property，
由框架插入 `Gen(expression, label)`，没有 Value / State 等分类，不是预写 UT 的参数。
跨拍值用 `Gen.past(signal, width, cycles)`；它只在 Gen 上下文内可用，框架自动要求在目标起始拍已有足够真实历史。
`Sem`、`Generate`、`Txn` 已删除，不保留兼容层；旧 v1 回答不自动转换，不混入 v2 实验结果。
契约及 framework-only RAG 边界见 [PROMPT](PROMPT.md)。
保存的 `--response-file`、`--prompt-only`、本地检索均不需要模型凭据。

`coverage_flow.py` 和 `rag_ablation.py` 共用 `cycle-replay-v1`：每条 witness 前独立复位，下降沿驱动，下一上升沿通过 clocking input `#1step` 采样，
避开 DUT 在时钟沿上的 blocking / NBA 更新竞争；
一 beat 对应一周期，不等待 done/ready。实际端口值、复位电平、采样数和相邻时刻必须与 schedule 一致。
JG VCD 中可见输出的已知位也与同一拍仿真输出比较；未知位保持 mask，不把 X 当作零值证据。
COI 优化掉的输出不作检查，输入型意图可能没有输出检查；这不等于全状态等价或算术正确性证明。
每条 witness 后可配置排空周期，保留数据并撤销请求控制；排空不计入 formal witness。
当前支持单上升沿时钟、manifest 所述复位和稳定的复位后初态。不能由有限拍复位确定的自由初态可能导致回放失败，
失败不会被记成覆盖成功。多时钟、任意握手事务压缩或全局状态恢复不在此契约内。

正式覆盖回放只消费逐 intent 的 stimulus / VCD，使用上述 UVM 传输后端。
独立 sequence 是数据导出，接入自定义 bench 时仍须自行验证驱动时刻和复位语义。
历史手写 UT、driver、bench、`--legacy`、旧 CLI 别名及旧消融格式适配已删除；复现旧数据请使用相应 Git 版本。
模型 Scala 不是受限语言，类型检查也不是安全沙箱：不可信输出需隔离或审查后执行。

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
它们不代表当前 `runtime-ut-v2` 的在线模型或 RAG 效果。

回放工具测试对同一个 synthetic 外部 RTL 的不同目标表达式实际求解并回放，
检查历史深度、历史采样值、时序 / Property 下的有效性保护、区间延迟、重复序列和不同目标间的隔离。
随后故意破坏一个已知输出期望，要求仿真失败。它们是框架回归，不进入 RAG，也不计作在线样本。
