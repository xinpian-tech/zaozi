# HAVEN / rvprobe 共享流程（2026-09-07）

契约 `haven-shared-v1`。本次只完成代码与离线回归，没有请求模型、启动 JasperGold/VCS/URG，
也没有新的覆盖率结果。原有 ALU 结果仍属于旧流程，不改标成共享流程结果。

## 共用与替换的边界

| 部分 | 两组的安排 |
|---|---|
| 初始环境和 sequence | 一次 HAVEN Stage 1，修复、冻结后共用同一份源码 |
| 初始覆盖 | 只测一次，作为两组共同起点；每组后续累计运行同一初始序列前缀 |
| UVM 组件 | 相同 interface、seq_item、driver、monitor、scoreboard；不在单侧修复 |
| 编译和覆盖 | HAVEN 的模板渲染、VCS 编译入口、URG 调用与缺口解析；固定 DUT 范围和指标 |
| 随机性和预算 | 同一 seed、模型、temperature、轮数、请求超时与重试预算，实际成本分别记录 |
| HAVEN 后端 | 原生 gap prompt、DSL schema 和 DSL → UVM CodeGen |
| rvprobe 后端 | 完整 UT → Gen → JasperGold witness → 每 intent 最多四条去重序列 → UVM sequence |

共同反馈包含设计规格、协议、blueprint、初始序列与实测缺口；rvprobe 的检索仍只提供框架信息。
后续每组的生成历史只属于该组，不把另一组的答案放进 prompt。
反馈对相同缺口去重，不再给每条样本重复附带整份覆盖报告。

本地 HAVEN 版本的 Stage 1 会调用模型生成初始 DSL，不能说成全部由预置规则生成。
共享的是实际 Stage-1 序列；其准备成本单列，不重复算到两个后端。
`prepare` 拒绝包含 Phase-7 产物的输入，避免将历史闭环答案当作初始基线。

这是统一条件的 **HAVEN DSL 后端对照**，不是不加修改的整套 HAVEN 论文复现：
两组均不开启 BO、动态 testbench 修复或 formal coverage exclusion。
HAVEN DSL 的 schema 修复与 rvprobe 的 Scala/求解反馈分别受 `--attempts` 约束，
并不声称两种后端每次尝试的计算工作相同，或实际 token 必然相等。

## 停止和失败

只在新序列完成仿真后判断：有效 line / condition / toggle / branch / FSM 指标等权平均，
增益小于 `--min-gain 0.1` 个百分点则停止；否则继续至 `--rounds 3`，或达到 `--target 100`。
FSM 的零 bin 不作为 0% 拉低分数。行覆盖不变而其他指标上升，仍可继续。
分母或指标集合变化是错误；覆盖回退如实记录，保留最佳和最终结果，不伪装成单调提升。

显式停止原因包括 `coverage_target`、`coverage_stalled`、`round_budget`、`model_stop`、
`no_generated_sequences`。一个后端失败不会冒充有效样本，也不阻止另一个后端给出记录。
编译/累计仿真错误使该组失败，不用未通过的序列计分，不让模型单侧改动固定 testbench。
当前不支持共享流程的断点恢复；必须使用新输出目录。

缺口使用 HAVEN 解析器；对于旧版本解析器不能详细解析的指标，保留明确的 summary-only 标记，
FSM 同时附带原始模块报告，不伪造具体状态/边的语义。
功能 covergroup 不混入当前代码覆盖停止分数；不能把代码覆盖视为功能正确性证明。

## Testbench 修正与支持范围

- 根据真实 IO 补齐 driver/monitor 所读的 seq_item 字段、修正字段宽度及输出 rand 属性。
- 直接握手 driver 在下降沿驱动，按配置发送一拍请求，检查本次请求之后的完成信号；超时 fatal。
- 在同一个 driver 中加入 raw-cycle 模式，普通 HAVEN sequence 默认仍用事务模式；
  witness 走 raw-cycle 模式，不能因等待 ready/done 被拉长。
- 两组均安装相同复位请求通路、确定的输入初值和 `#1step` 输出采样检查。
- sequence 超时由“警告后继续”改为 fatal，不能将不完整执行计为成功。
- HAVEN 旧错误解析器的 `UVM_ERROR : 0` / `UVM_FATAL : 0` 汇总误报在共用适配层修正；
  只过滤精确零计数汇总，真实诊断和非零计数仍失败。
- 通用修复函数支持按显式映射消除静态配置/时钟的多重驱动；合成回归覆盖 SDRAM 类型的问题，
  但这不等于真实 SDRAM testbench 已完成配对验证。

当前配对适配范围：**单活动 agent、单时钟、无外部 BFM/inout、默认 RTL 参数**。
ALU 属于该范围，但新适配后的真实商业工具编译/仿真仍未执行。
CAN、SDRAM 等设计不能仅凭字段/时钟修复就声称完成接入：它们还需要与形式求解一致的
复位、BFM 或多时钟环境契约。超出范围明确报错，不偷偷施加新 Assume。
未知模板形状、未知 typedef 宽度、IO/RTL/复位不一致也拒绝准备。

## 入口与依赖

`experiments/coverage_flow.py` 现为配对入口；旧逐周期诊断移到 `cycle_diagnostic.py`，
仅用于独立回放/历史方法诊断，不作为新的 HAVEN 对照。旧参数不转发兼容。
`rag_ablation.py` 仍是单独的 RAG 消融工具，不等于 HAVEN/rvprobe 配对实验。

HAVEN 是显式外部依赖，用 `--haven-root` 指定；仓库没有写死 scratchpad 路径，
也没有复制整个第三方项目。运行解释器需要安装该 HAVEN checkout 的 Python 依赖。
`bundle.json` 固定 HAVEN Python/模板/prompt 哈希、原始 Stage-1 来源哈希、RTL/IO、修复后的共享组件和初始序列。
运行前会核对 bundle、HAVEN checkout、RTL 和回放配置，变更后不能复用。

准备已有的、只执行至 Stage 1 的 HAVEN 产物（此命令本身不调用模型或 EDA）：

```sh
python3 experiments/coverage_flow.py prepare \
  --haven-root /path/to/haven \
  --stage1-run /path/to/haven-stage1-run \
  --replay-config experiments/designs/alu_replay.json \
  --out out/experiments/alu-shared-bundle
```

如果没有 Stage-1 产物，需要先单独执行 HAVEN 的 Stage-1 生成，并记录其成本。
本次没有执行这个步骤，不能用以前的 Phase-7 最优结果替代。

以下才是正式运行命令，**本次未执行**：

```sh
python3 experiments/coverage_flow.py run \
  --haven-root /path/to/haven \
  --bundle out/experiments/alu-shared-bundle/bundle.json \
  --eda-config experiments/designs/haven_eda.json \
  --eda-shell /absolute/path/to/eda-shell \
  --model deepseek-v4-flash-vision-exp \
  --rounds 3 --min-gain 0.1 --sequences-per-intent 4 \
  --env-file /path/to/provider.env \
  --out out/experiments/alu-shared-pair
```

模型凭据只在生成子进程中加载；`--eda-config` 不接受模型配置，EDA 环境由共用 wrapper 提供。
主要产物为共同 `baseline/`、`haven/round-N/`、`rvprobe/round-N/`、`summary.json`、
`comparison.json`、阶段日志及逐次模型用量。每组记录 UTC、耗时、输入/输出 token、失败与停止原因。
原始 response、DSL CodeGen 过滤统计、rvprobe 采样不足均保留，不仅记录成功的最终答案。

## 本次验证

```sh
python3 -m unittest discover -s experiments -p 'test_*.py' -q
# 可选：使用安装了 HAVEN 依赖的解释器，仅检查真实模板和 DSL CodeGen
HAVEN_TEST_ROOT=/path/to/haven python3 -m unittest discover -s experiments -p 'test_haven_shared.py' -q
```

回归禁止调用模型/EDA，覆盖共享基线、两组隔离、多指标停止、覆盖回退、分母变化、重复 sequence、
只有翻转缺口时的 prompt、缺字段与错宽度、时钟/配置所有权、逐拍驱动及模板不匹配拒绝。
真实 HAVEN 模板/CodeGen 的离线兼容检查也通过；不等于新的端到端实验通过。

最终默认回归：133 项，117 项通过、16 项按环境配置跳过。
另在 HAVEN 依赖环境中执行共享流程测试：27 项全部通过；其中真实模板、DSL 后端和模拟器适配测试
禁止外部进程，模型/VCS/URG 均用测试替身。因此本次在线模型请求和正式实验数均为零。
