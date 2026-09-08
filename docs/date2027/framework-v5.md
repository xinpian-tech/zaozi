# rvprobe v5：执行隔离、覆盖归因、恢复与成本记录

日期：2026-09-06。生成契约 `runtime-ut-v5`，回放仍为 `cycle-replay-v1`，framework-only RAG 语料版本 11。
本次实现与回归没有调用在线模型，没有引入 benchmark 答案到 RAG，也没有改动原始 RTL 或新增 stdlib 模块。

## 已落实的四项改动

1. 模型源码在 Linux bubblewrap 中断网编译/lower，只读挂载工具链和本轮源码；固定 runner/binding 与模型原文均核验。
   可信侧检查唯一原始 DUT、边界端口、直接/别名连接和复位极性、额外驱动及环境假设，再用不加载模型 class 的独立 JVM 求解。
2. 每个 Gen 独立统计固定 baseline + witness 和 baseline + witness + drain；`witness_closed_lines` 与
   `drain_added_lines` 分开回馈模型。不同目标的独立收益可能重叠，不能相加冒充累计收益。
3. Prompt 注入 manifest 中完整 RTL / include 内容、文件名、行号和哈希，不再仅给残余附近窗口。
   超过 1,000,000 字符明确失败，不能悄悄截断。任务证据不进入 RAG。
4. 各目标独立保存 `generated/infeasible/unknown/error` 和 checkpoint；失败目标不会丢弃其他成功 witness。
   模型可以明确返回 `stop`，无需补凑 Gen。传输重试与源码修正预算分离，支持相同输入的 `--resume`。

接线检查不是任意 SV 的语义等价证明；隔离不是内核漏洞防御或多租户资源配额系统。
`Infeasible` 只针对所编码目标，`stop` 和待证元数据都不是 DUT 死代码证明。单时钟等适用边界见 experiments/README.md。

## 实验记录

三个实验入口统一生成 `events.jsonl`、`summary.json`、`comparison.json`：UTC 起止、跨恢复墙钟时间、
活动会话时间、请求次数、服务端输入/输出/总 token、报告模型名、逐阶段/逐目标耗时、版本/配置/预算与覆盖。
失败、重试、中断请求仍保留在分母；用量未返回则记为未知，不当作零成本。未提供价格时不估算货币金额。
总墙钟包含两次恢复之间的空档；活动会话不含空档。嵌套阶段时间不能相加当作墙钟时间。

## 本次实际回归

ALU：`out/experiments/alu-v5-hardening-20260906-final/`。

- 第一轮使用仓库保存的完整 completion UT：1 个 UT、1 个 Gen，12 次已知输出检查通过。
- 初始化行覆盖 41/179；witness 新增 35 行，固定 drain 再新增 21 行，最终 97/179。
- 第二轮使用显式 stop 回答：0 个 UT，停止原因 `model_stop`，不声称覆盖闭合。
- 首次完成约 44.2 秒，模型请求 0 次、在线 token 0。实际起止和恢复后的耗时以 comparison.json 为准。
- 加 `--resume` 再运行后，JG 目标求解仍为 1 次、VCS 编译仍为 1 次、回放/URG 仍各 4 次，没有重复工作或虚增 token。

离线双入口：`out/experiments/alu-v5-ablation-20260906-final/`。

- 两组共用一个新 baseline 和同一 VCS build；各消费同一个保存回答，均通过检查并新增 56 行。
- 首次完成约 66.9 秒；两组总模型请求 0 次，恢复不重新生成或回放已完成样本。
- 这是编排一致性回归，不能用来判断 RAG 比原始 prompt 更好。

测试还覆盖实际 Scala 编译诊断、JG 时序/Property/past、互斥目标独立求解、不可行与成功目标并存、
成功 checkpoint 恢复，以及故意破坏目标未引用输出位时的回放失败；API 示例和后端 Scala 单元测试通过。
普通测试包含隔离文件/网络、错误接线、请求失败/重试账本、恢复拒绝改参、停止以及子进程超时清理。

## 复现

使用新的输出目录；运行环境需要 Nix、bubblewrap、JG/VCS/URG 和许可证，但这些离线命令不需要模型凭据。

```sh
python3 experiments/coverage_flow.py \
  --replay-config experiments/designs/alu_replay.json \
  --response-file experiments/tests/fixtures/completion_intent.json \
  --response-file experiments/tests/fixtures/stop_response.json \
  --out out/experiments/my-v5-regression

python3 experiments/rag_ablation.py \
  --replay-config experiments/designs/alu_replay.json --samples 1 \
  --response-file experiments/tests/fixtures/completion_intent.json \
  --out out/experiments/my-v5-ablation-regression

python3 experiments/compare_runs.py out/experiments/run-a out/experiments/run-b
```

恢复时在原命令末尾加 `--resume`，输入/框架哈希或预算改变则需新目录。
不同任务、基线、版本和预算的实验不能把收益差异单独归因于 RAG；对比输出会标识比较条件是否相同。
