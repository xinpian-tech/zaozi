# DATE / rvprobe 分支工作总览

更新于 2026-09-05。本文汇总 `date` 分支相对 `ut` 基线 `7f1a276` 的工作；下列覆盖数据引用
已有实验记录，不代表整理提交时重新执行了在线模型实验或完整商业 EDA 回放。

## 代码分层

| 层次 | 入口 | 已完成工作 |
|---|---|---|
| 框架与求解后端 | [`utlib/src/`](../../utlib/src/) | 四类 `Sem` 意图、`Txn` 历史窗口、外部 SV 导入、circt-bmc / JasperGold、trace → ABI → stimulus / UVM；配套 CIRCT 版本与接口调整 |
| 外部设计接口 | [`experiments/designs/`](../../experiments/designs/)、[`fixtures/`](../../experiments/fixtures/) | 原始 RTL 与 IO manifest；运行时生成共享 wrapper，不在 stdlib 复刻 DUT |
| 历史基准回归 | [`experiments/legacy/`](../../experiments/legacy/) | 旧手写 UT、driver 和测试，独立模块，不参与当前 LLM / RAG |
| 实验流程 | [`experiments/`](../../experiments/) | 编译反馈、同 testbench 回放、统一 URG 计分、2×2 消融、ALU 残余闭合及 RAG 对照工具 |
| 结果与审计 | 本目录及 [`data/`](data/) | 复现结果、原始 prompt / response、模型成本、覆盖测量、失败记录与结论更正 |

框架区分 `Generated`、`Infeasible`、`Unknown`；circt-bmc 的有界不可行不能解释为无界不可达。
模型候选、合法求解 witness、仿真覆盖增量、独立不可达证明是不同证据，不相互替代。

## 当前实验结论

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
- **框架 RAG**：检索与回归检查已实现，纠正后的在线 A/B 尚未执行，暂不能声称有效或更省 token。

计分使用 [`urg_score.py`](../../experiments/urg_score.py) 的 DUT-only 固定口径：无 FSM 为四指标，
有 FSM 为五指标。旧报告中的不同层级、不同基线和 bench 修复版本不能直接混比；原始记录保留
这些差异。现有 ALU scoreboard 只计数，不验证算术结果正确性。

## 当前维护的 prompt / RAG 入口

当前契约为 `runtime-ut-v1`：[`sequence_experiment.py`](../../experiments/sequence_experiment.py)
从当前 URG 残余、RTL 窗口及 IO manifest 构造任务；LLM 返回 JSON 中的实际 `Sem.Intent` 表达式，
框架为本轮每条意图生成 UT，并共用一个外部 VerilogWrapper。活动模块不依赖 stdlib。
旧 `cases` 列表和完整 Scala response 仅属于历史流程，不能混入当前消融。
架构、支持范围和无需 LLM 的复现命令见 [`experiments/README.md`](../../experiments/README.md)，
输出契约见 [`PROMPT.md`](../../experiments/PROMPT.md)。

RAG 只提供框架信息：[`framework_api.json`](../../experiments/rag/framework_api.json) 的源路径白名单、
源码原文校验和哈希记录，加上 [`src/rag/`](../../experiments/src/rag/) 的三个可编译、参数化写法示例
（JSON 数据封装、四类语义组合、求解结果处理及 UVM 导出，语料版本 4）。示例不提供 DUT 操作数、设计谓词或历史答案。
来源检查不能代替内容审查；元数据和新增框架文档仍须人工检查。

旧 ALU 答案语料已退出检索入口并归档；其离线测量及 20 次在线调用的收益结论已撤回。
[`alu-rag-evaluation.md`](alu-rag-evaluation.md) 与 `data/alu-rag-*.json` 仅供污染实验审计，
不得当作干净的 RAG 结果，也不能把旧无 RAG 样本与新 prompt 样本拼成对照。

新对照通过 [`alu_rag_ablation.py`](../../experiments/alu_rag_ablation.py) 的
`generate → solve → replay → summarize` 四阶段执行；两组仅检索上下文不同，使用同 build / seed
回放并显式统计失败和缺失样本。默认是两个固定残余任务、每任务每组 5 次，共 20 次调用，
不是自适应闭环，也不是跨设计迁移实验。具体命令见 [ALU 运行说明](../../experiments/haven_tb/alu/README.md)。

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

结构重构另用独立外部小 RTL 验证了四类动态 UT 的 JasperGold 求解和 sequence 导出。
这不是重新测量历史 ALU 覆盖，也没有重新进行在线模型 / RAG 对照。历史资料中的旧路径映射见
[`legacy/README.md`](../../experiments/legacy/README.md)；原始数据不改写。

Prompt-only 和本地检索不需要模型凭据，但仍需提供当前任务的 URG 报告。
在线生成需要配置模型服务；JasperGold / VCS / URG 回放需要本机工具、许可证与保存的 HAVEN bench。
[`eda-shell`](../../experiments/haven_tb/eda-shell) 及若干脚本默认路径针对原实验主机，迁移时须配置
对应路径；`out/` 中的 bench、覆盖数据库、波形和日志未纳入 Git，保留在原运行目录。

## 资料索引

- [HAVEN / DeepSeek 复现](haven-deepseek-reproduction.md)、[rvprobe 对比汇总](rvprobe-vs-haven.md)
- [同 testbench 逐步实验记录](rvprobe-in-haven-testbench.md)、[2×2 消融](ablation-2x2.md)
- [circt-bmc assumption 问题与复现](circt-bmc-assume.md)、[RAG 污染更正与审计](alu-rag-evaluation.md)
- 历史设计资料：[实验计划](experiment-plan.md)、[rvprobe arm 设计](rvprobe-arm-design.md)、[论文提纲](paper-outline-v2.md)；
  其中计划性描述不代表已经完成，当前状态以本文及对应测量记录为准。
