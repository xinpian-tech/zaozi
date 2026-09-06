# DATE / rvprobe 分支工作总览

更新于 2026-09-06。本文汇总 `date` 分支相对 `ut` 基线 `7f1a276` 的工作；下列历史覆盖数据引用
已有实验记录，不代表整理提交时重新执行了在线模型实验或完整商业 EDA 回放。

## 代码分层

| 层次 | 入口 | 已完成工作 |
|---|---|---|
| 框架与求解后端 | [`utlib/src/`](../../utlib/src/) | 单一 `Gen` 入口，接受 Bool / Sequence / Property；作用域内受保护的 `Gen.past`；外部 SV 导入、circt-bmc / JasperGold、trace → ABI → stimulus / UVM |
| 外部设计接口 | [`experiments/designs/`](../../experiments/designs/)、[`fixtures/`](../../experiments/fixtures/) | 原始 RTL 与 IO manifest；运行时生成共享 wrapper，不在 stdlib 复刻 DUT |
| 实验流程 | [`experiments/`](../../experiments/) | 新 baseline → 编译 / 求解反馈 → 每条 witness 独立复位的逐周期回放 → URG 残余反馈；RAG 对照共用同一后端 |
| 独立证明 | [`experiments/proofs/`](../../experiments/proofs/) | 人工可达性属性，独立于模型候选和 RAG |
| 结果与审计 | 本目录及 [`data/`](data/) | 复现结果、原始 prompt / response、模型成本、覆盖测量、失败记录与结论更正 |

框架区分 `Generated`、`Infeasible`、`Unknown`；circt-bmc 的有界不可行不能解释为无界不可达。
模型候选、合法求解 witness、仿真覆盖增量、独立不可达证明是不同证据，不相互替代。

旧 `Sem` / `Generate` / `Txn`、手写基准 UT、legacy 构建模块和专用旧 bench 已删除，不保留兼容入口。
下述历史数据及 `data/` 不改写；重现旧版本时使用对应 Git 历史。当前实验不需要历史 bench 或保存的 out 数据。

## 历史实验结论与当前证据边界

- **ALU 残余闭合**：2026-09-04 的 DeepSeek / JasperGold 两轮实验把 DUT 综合覆盖分从
  90.27 提升至 95.00，行覆盖为 177/179；剩余两行经独立属性证明不可达。该历史结果不是
  当前框架 RAG 的效果验证。见 [ALU 流程与逐轮记录](rvprobe-in-haven-testbench.md) 和
  [原始数据](data/alu-jg-loop.json)。
- **多设计回放**：同日重新生成 witness 并回放的 I2C / SDRAM / CAN 分数分别为
  87.50 / 71.02 / 50.47；这些设计的意图由作者编写，不能表述为已验证 LLM 自动生成。
  CAN 的三个深层流程仍为 `Unknown`。见 [统一口径对比及限制](rvprobe-vs-haven.md) 和
  [重新运行数据](data/rvprobe-rerun.json)。
- **2×2 消融**：每格 4 个样本；类型检查确实捕获过错误，但 ALU 上 typed / untyped
  的覆盖结果接近，不能泛化为类型层必然提高覆盖。见 [消融报告](ablation-2x2.md)。
- **框架 RAG**：当前统一 Gen / RAG v8 的受控在线 A/B 尚未执行，暂不能声称有效或更省 token；保存回答回归仅检查流程。

历史计分使用 DUT-only 口径：无 FSM 为四指标，有 FSM 为五指标。当前 cycle replay 显式采集
line / cond / toggle / branch 四指标，由 [`urg_score.py`](../../experiments/urg_score.py) 校验与计分。
不同指标、基线和回放契约不能直接混比。当前回放检查输入、采样时刻及 VCD 可见输出已知位，
不宣称全状态等价或算术正确性；历史 scoreboard 的局限仍见原报告。

## 当前维护的 prompt / RAG 入口

当前契约为 `runtime-ut-v2`：[`sequence_experiment.py`](../../experiments/sequence_experiment.py)
从当前 URG 残余、RTL 窗口及 IO manifest 构造任务；LLM 返回 JSON 中的实际 `Gen.Expr` 表达式，
框架为本轮每条意图生成 UT，并共用一个外部 VerilogWrapper。活动模块不依赖 stdlib。
旧 `cases` 列表和完整 Scala response 仅属于历史流程，不能混入当前消融。
架构、支持范围和无需 LLM 的复现命令见 [`experiments/README.md`](../../experiments/README.md)，
输出契约见 [`PROMPT.md`](../../experiments/PROMPT.md)。

RAG 只提供框架信息：[`framework_api.json`](../../experiments/rag/framework_api.json) 的源路径白名单、
源码原文校验和哈希记录，加上 [`src/rag/`](../../experiments/src/rag/) 的三个可编译、参数化写法示例
（JSON 数据封装、统一目标写法、求解结果处理及独立 UVM 导出，语料版本 8）。示例不提供 DUT 操作数、设计谓词或历史答案。
来源检查不能代替内容审查；元数据和新增框架文档仍须人工检查。

旧 ALU 答案语料已退出检索入口并归档；其离线测量及 20 次在线调用的收益结论已撤回。
[`alu-rag-evaluation.md`](alu-rag-evaluation.md) 与 `data/alu-rag-*.json` 仅供污染实验审计，
不得当作干净的 RAG 结果，也不能把旧无 RAG 样本与新 prompt 样本拼成对照。

新对照通过 [`rag_ablation.py`](../../experiments/rag_ablation.py) 一次执行：新 baseline → 单轮生成 / 修正 →
校验回放 → 配对统计。两组仅检索上下文不同，使用同一 VCS build、seed 和初始残余，显式统计失败及缺失样本。
默认一个固定残余任务、每组 5 次、每次一次请求，共 10 次请求；可显式增加两组等量修正预算。
这不是自适应闭环或跨设计迁移实验；自适应覆盖闭环单独使用 `coverage_flow.py`。
具体命令见 [实验说明](../../experiments/README.md)。

## 验证与运行前提

在仓库根目录执行不需要模型凭据的回归检查：

```sh
python3 -m unittest discover -s experiments -p 'test_*.py' -v
nix develop . -c mill --no-server experiments.compile
nix develop . -c mill --no-server experiments.tests.testForked
nix develop . -c mill --no-server utlib.tests.testForked
```

Python 检查检索边界、动态生成契约、IO / 输入一致性和对照统计；Scala 示例测试使用合成数据；`utlib` 测试包含
circt-bmc 的小规模实际求解。这些检查不等于 JasperGold / VCS 全量复现或模型效果评测。

结构重构用独立外部小 RTL 检查统一 Gen、历史有效性、时序组合、JasperGold 求解和逐周期回放。
测试还要求错误类型、非法历史深度、不可达目标和被故意破坏的输出检查不能被记为成功。
这不是重新测量历史 ALU 覆盖，也不等于在线模型 / RAG 对照；原始数据不改写。

Prompt-only 和本地检索不需要模型凭据，但仍需提供当前任务的 URG 报告。
在线生成需要配置模型服务；JasperGold / VCS / URG 回放需要本机工具和许可证，bench 从 manifest 自动生成。
[`eda-shell`](../../experiments/eda-shell) 的默认路径针对原实验主机，迁移时须配置
对应路径；`out/` 中的 bench、覆盖数据库、波形和日志未纳入 Git，保留在原运行目录。

## 资料索引

- [HAVEN / DeepSeek 复现](haven-deepseek-reproduction.md)、[rvprobe 对比汇总](rvprobe-vs-haven.md)
- [同 testbench 逐步实验记录](rvprobe-in-haven-testbench.md)、[2×2 消融](ablation-2x2.md)
- [circt-bmc assumption 问题与复现](circt-bmc-assume.md)、[RAG 污染更正与审计](alu-rag-evaluation.md)
- 历史设计资料：[实验计划](experiment-plan.md)、[rvprobe arm 设计](rvprobe-arm-design.md)、[论文提纲](paper-outline-v2.md)；
  其中计划性描述不代表已经完成，当前状态以本文及对应测量记录为准。
