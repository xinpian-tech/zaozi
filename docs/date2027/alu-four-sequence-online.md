# ALU 在线闭环：每 intent 四条 sequence（2026-09-06）

本次从复位/空闲基线重新请求 `deepseek-v4-flash-vision-exp` 生成 UT，不复用上次 ALU 的模型答案。
普通闭环与 RAG 对照均默认请求每个 intent 四条不同 sequence。

执行分成三个阶段：在线生成了新 UT 后，首次运行因采样校验缺陷在 sampling 阶段暂停；
修复后复用同一份模型原文和原始求解结果，重新运行固定 UT 的 1/4 条采样与回放。
没有修改模型 UT，也没有为修复重复调用模型。最终采样阶段是保存产物复用，不冒充第二次在线生成；
随后通过现有恢复机制复用已校验的 UT / 原始 witness / 四条样本池，继续自动覆盖反馈闭环。
下一轮才重新调用模型，首轮 prompt 必须与缓存逐字一致，否则拒绝复用。

缺陷是 JG 导出单拍配置时会省略默认 `min_length 1`，校验器误判缺失为长度变化。
现在接受这个默认值，但仍要求显式 `max_length 1`，且实际 VCD 必须恰为一拍，不延长 witness。

## 配置与流程

- 框架：`runtime-ut-v5` / `cycle-replay-v1`，framework-only RAG v11。
- 模型：`deepseek-v4-flash-vision-exp`，temperature 0.3；最多三轮、每轮三次源码尝试，
  请求超时 600 秒、传输重试预算三次、原始 Gen 求解限时 120 秒，patience=1。
- 采样：`--sequences-per-intent 4 --sampling-seed 20260906 --sampling-time-limit 30s`。
- 每轮由模型输出一个完整 UT，框架不人工补写 Gen 或修改模型源码。
- 原始 witness 加最多三个额外解；额外解通过软输入偏好生成，保持原 cover 和 witness 长度，
  不添加环境 Assume。按 intent 内完整输入序列去重，数量不足或失败明确记录，不重复凑数。
- 每条 sequence 单独做 witness-only / full-with-drain 回放，失败不丢弃其他成功序列。
  `intent_label` / `sample_index` 区分模型目标与样本数量。
- 同时记录累计完整覆盖和累计去掉 drain 的覆盖，初始只有两拍复位和一拍空闲，没有随机前置输入。
- 实际模型提示词明确写出四条预算，要求一个语义 intent 只声明一次 Gen，并保留必要场景约束。

本次还修正了残余提取只接受 `0/N` 的问题：`1/2` 等部分覆盖行现在仍列入反馈，避免提前判断覆盖闭合。
这不改变 URG 计数或 RTL，只修正残余与停止逻辑。

## 固定首轮 UT 的 1 / 4 条对照

新 UT 为 `AluTopCoverageUT`，29 个 Gen，源码 SHA-256 为
`3de13ff7fb020f2e4867e799a029c89c2a3d1b1c633d8b841705ec968cbe2e00`。
29 个原始 witness 共 160 拍；每个 intent 均成功得到四条不同、长度不变的序列，共 116 条、640 个 witness 拍。
这是同一新 UT 的嵌套采样前缀对照，不是两次独立生成或多个随机种子的统计结论。

| 每 intent 条数 | 实际序列 | 行覆盖 | 条件覆盖 | 翻转覆盖 | 分支覆盖 | 综合分 |
|---|---:|---:|---:|---:|---:|---:|
| 1 | 29 | 97.77% | 83.49% | 75.63% | 92.31% | 87.30 |
| 4 | 116 | 97.77% | 85.38% | 89.09% | 92.31% | 91.14 |

去掉 drain 后，翻转覆盖分别为 **75.63% / 86.69%**，条件覆盖为 82.55% / 84.43%。
增加序列带来的完整回放翻转增益为 **13.47 个百分点**，去掉 drain 后仍有 **11.06 个百分点**；
行覆盖两档均为 175/179，剩余 316、317、336、401 行。多采样不能保证补齐语义场景缺口。
此前另一份 UT 的四条结果为 93.42%，因模型 UT 不同，不能把差异单独归因于采样。

原始在线阶段一次请求消耗 64,021 token（输入 17,136，输出 46,885），请求耗时 359.053 秒。
在线阶段因上述校验缺陷中断，耗时 736.198 秒；修复后固定 UT 对照耗时 231.548 秒、没有新增模型 token。
这两个阶段及失败记录均保留，后续闭环的新增成本另计；不能将它们描述为一次无故障运行。

## 回归

单元回归 106 项：95 项通过，11 项按配置跳过。新增测试覆盖默认四条、提示词预算、
采样失败保留原解、不同目标隔离、同目标单条回放失败隔离、样本不足不填充，以及部分覆盖行提取。

小设计的真实工具回归得到 1/4/4/4 条（一个目标补充采样返回 undetermined，保留原解），13 条均通过回放。
ALU 保存回答的一目标回归得到四条，全部通过回放；恢复时没有重复求解或采样。
RAG 两侧的离线回归均得到四条且回放通过。这些回归没有调用在线模型，不算新的模型样本。

## 自动闭环最终结果

两轮均由模型输出一个 UT，首次源码尝试即通过；30 个 intent 均得到四条不同 sequence，
共 120 条全部通过独立回放，没有采样不足或回放失败。

| 轮次 | UT / intent / sequence | 行覆盖 | 条件覆盖 | 翻转覆盖 | 分支覆盖 | 综合分 |
|---|---|---:|---:|---:|---:|---:|
| 1（复用已审计首轮产物） | 1 / 29 / 116 | 175/179 | 85.38% | 89.09% | 92.31% | 91.14 |
| 2（自动残余反馈） | 1 / 1 / 4 | 175/179 | 85.85% | 89.20% | 92.31% | 91.28 |

最终去掉 drain 的覆盖为 line 97.77%、cond 84.91%、toggle 89.04%、branch 92.31%，综合分 91.00。
完整累计回放共 2,831 拍，其中 witness 668 拍，2,004 次可见输出已知位检查通过。
这不等于独立算术正确性证明或全状态等价证明。

第二轮模型生成 `fp_add_rounding_carry_observed`，但其四条合法 witness 并未覆盖 316、317 行。
目标标签不能替代实际覆盖证据。两轮后仍剩 316、317、336、401 行，
按 `patience=1` 以 **`no_line_progress`** 停止，而不是覆盖闭合或模型主动 stop。
模型对 336、401 行的不可达说明仅保留为 pending proof；本次没有执行独立证明，也没有排除这些行。

恢复后的闭环耗时 942.174 秒，新增一次模型请求 / 143,739 token：输入 107,821、输出 35,918，
请求耗时 274.812 秒。逐条反馈使第二轮输入明显增大，这是当前实现的实际成本；
本次没有在运行中压缩反馈或改变提示词。

| 阶段 | UTC 开始 → 结束 | 墙钟秒 | 新请求 | 新 token |
|---|---|---:|---:|---:|
| 新 UT 在线生成及首次中断 | 23:05:05 → 23:17:21 | 736.198 | 1 | 64,021 |
| 同 UT 的 1/4 条对照 | 23:18:59 → 23:22:51 | 231.548 | 0 | 0 |
| 复用后继续闭环 | 23:27:18 → 23:43:00 | 942.174 | 1 | 143,739 |

三个阶段合计 **2 次请求、207,760 token**（输入 124,957，输出 82,803）；
活动运行时间 **1,909.820 秒，约 31 分 50 秒**。首次启动到最终完成的墙钟间隔为
**2,275.235 秒，约 37 分 55 秒**，包含修复及审计间隔，不是干净单次运行的耗时。
没有提供服务商价格，故不推算货币费用。离线开发回归另计，不混入上述实验成本。

最终审计通过：缓存来源和 SHA-256、模型源码原文、冻结框架/提示词/RTL、每 intent 去重、
原始 horizon、仅软偏好的采样约束、逐条及累计回放产物、成本账本均一致。
机器可读汇总见 [data/alu-four-sequence.json](data/alu-four-sequence.json)。

## 复现

```sh
python3 experiments/coverage_flow.py \
  --replay-config experiments/designs/alu_replay.json \
  --out out/experiments/my-alu-four-sequence-live \
  --model deepseek-v4-flash-vision-exp --temperature 0.3 \
  --rounds 3 --attempts 3 --patience 1 \
  --sequences-per-intent 4 --sampling-seed 20260906 --sampling-time-limit 30s \
  --jg-time-limit 120s --timeout 600 --request-retries 3 --rag local \
  --env-file /path/to/provider.env
```

在线命令会重新调用模型，结果不保证相同。相同输入、配置与源码哈希下可加 `--resume` 恢复。
恢复验证包含采样数量、seed、时限与原始 witness；不接受换配置后偷偷复用旧样本池。

在线生成与中断记录：`out/experiments/alu-four-sequence-live-20260906`（保留 failed / sampling 状态，不改写为成功）。
最终固定 UT 实验：`out/experiments/alu-four-sequence-20260906-final`。
继续闭环：`out/experiments/alu-four-sequence-flow-20260906`；`reuse.json` 列出缓存来源、目标与 SHA-256。
继续闭环的请求/token 账本只计新请求；复用的首轮 64,021 token 单独列出，合计时只计算一次。
控制目录：`out/experiments/alu-four-sequence-live-20260906-control`。
UT / prompt / response 在 `round-N/generation/attempt-M/`；每个目标的采样池在
`round-N/sampling/<label>/pool.json`，采样不足及失败在 `round-N/sampling/sampling.json`，
逐条回放证据在 `round-N/goal-coverage.json`。

`summary.json`、`comparison.json`、`events.jsonl` 记录 UTC、墙钟时间、工具阶段、模型请求与 token 用量。
固定 UT 的两档使用相同的新 UT，只有采样前缀数量变化；与此前不同 UT 的结果只能作描述性比较。此前四档对照见
[此前多序列实验](alu-multi-sequence.md)。

精确复用本次新 UT 的测量命令（不再调用模型）：

```sh
python3 experiments/witness_sampling.py \
  --source-solve out/experiments/alu-four-sequence-live-20260906/round-1/generation/attempt-1/solve \
  --config experiments/designs/alu_replay.json \
  --out out/experiments/alu-four-sequence-20260906-final \
  --counts 1 4
```
