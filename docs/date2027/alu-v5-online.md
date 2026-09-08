# ALU v5 在线实验（2026-09-06）

本次使用当前 `runtime-ut-v5` 框架完整运行在线覆盖闭环。实验模型为
`deepseek-v4-flash-vision-exp`，temperature 0.3，framework-only RAG 版本 11。
最多 3 轮、每轮最多 3 次源码尝试、传输重试预算 3 次、请求超时 600 秒、JG 每个 Gen 120 秒，patience=1。
初始化只有 2 拍复位和 1 拍空闲，固定排空 16 拍，无随机前置输入。

## 结果

UTC 起止：2026-09-06 14:38:58.209 → 14:53:21.388。
总耗时 863.179 秒（14 分 23 秒），活动会话 863.149 秒；本次没有恢复间隔。
2 次模型请求，输入 52,040 token、输出 53,859 token，合计 105,899 token。
全部请求都返回了用量与预期模型名，无传输重试、无源码修正、无失败目标。
服务价格未提供，不估算货币成本。

| 轮次 | 模型输出 | 请求耗时 | 输入 token | 输出 token | 总 token | 行覆盖 |
|---|---|---:|---:|---:|---:|---:|
| 1 | 1 个 UT，29 个 Gen | 415.009 s | 16,995 | 48,136 | 65,131 | 41/179 → 177/179 |
| 2 | 显式 stop，0 个 UT | 49.588 s | 35,045 | 5,723 | 40,768 | 保持 177/179 |

29 个目标全部独立求解并通过回放；累计回放包含 180 拍 witness、705 拍采样、540 次已知输出检查。
新增的 136 行都能在至少一条独立 witness 中观察到；独立完整回放的新增行并集同样为 136，
没有仅由 drain 覆盖的新行。各目标收益仍有重叠，不能把逐目标计数直接相加。
208、209 行在 `fp_sub_inf_inf` 的 witness 内覆盖，不依赖排空。

剩余 336、401 两行。第二轮模型返回 stop，并给出两项不可达解释；框架将其保留为待证元数据，
没有独立证明、没有从分母中剔除，因此 `coverage_closed=false`。
停止原因是 `model_stop`，没有为了满足非空目标列表而补造 UT/Gen。

## 与上一次 v4 在线实验的描述性比较

历史来源：[单 UT / 无模型 Assume 实验](single-ut-alu.md)。两次使用相同 RTL、最小初始化和模型，
但框架、完整 RTL 上下文、RAG 语料、逐目标反馈和停止机制均发生变化；不是只改变 RAG 的受控 A/B。

| 指标 | 上次 v4 | 本次 v5 |
|---|---:|---:|
| 模型请求 | 3 | 2 |
| 实际采用 UT / Gen | 3 / 32 | 1 / 29 |
| 报告 token | 160,703 | 105,899 |
| 总耗时 | 1,465.74 s | 863.179 s |
| 行覆盖 | 177/179（98.88%） | 177/179（98.88%） |
| 条件覆盖 | 184/212（86.79%） | 177/212（83.49%） |
| 翻转覆盖 | 1732/1916（90.40%） | 1071/1916（55.90%） |
| 分支覆盖 | 62/65（95.38%） | 62/65（95.38%） |
| 综合分 | 92.86 | 83.41 |

本次 token 减少约 34.1%，耗时减少约 41.1%，且第一轮就达到相同的行覆盖。
但综合分下降，主要差异是翻转覆盖。不能据此宣称总体覆盖更好，或把变化单独归因于 RAG。
当前闭环以未覆盖执行行为目标，不以综合分最大化作为停止准则；停止并不代表其他覆盖指标饱和。

## 工具成本与证据

| 阶段 | 次数 | 合计耗时 |
|---|---:|---:|
| 模型请求 | 2 | 464.597 s |
| Scala 编译 | 1 | 3.839 s |
| UT lower | 1 | 1.490 s |
| DUT 接线检查 | 1 | 0.006 s |
| 逐目标 JG | 29 | 192.524 s |
| VCS 编译 | 1 | 8.210 s |
| VCS 回放 | 60 | 96.301 s |
| URG | 60 | 82.235 s |

60 次回放分别是 1 次 baseline、29×2 次 witness/full 独立测量和 1 次累计回放。
完整阶段账本还包含工具链准备、CIRCT preflight 和嵌套编排阶段，不能直接全部相加当作墙钟时间。

运行目录：`out/experiments/alu-v5-live-20260906T143755Z/`。
控制与审计目录：`out/experiments/alu-v5-live-20260906T143755Z-control/`。

- `summary.json` / `comparison.json` / `events.jsonl`：总结果、比较信息、含起止的阶段账本。
- `round-1/generation/attempt-1/sources/ModelUT.scala`：模型原始完整 UT。
- `round-1/goal-coverage.json`：逐 Gen 的 witness/drain 覆盖与回放检查。
- `round-2/generation/attempt-1/response.txt`：模型原始 stop 回答。
- 控制目录 `audit.json`：运行前后框架/PROMPT/RAG/RTL 哈希一致，模型源码逐字节一致，
  只有一个原始 DUT、零模型环境假设，29 份完整设计 VCD 与 stimulus 哈希验证通过。

运行期间没有人工改写 UT、约束、PROMPT 或 RAG；模型源码也未加入 stdlib。
这些结果是覆盖与已知输出回放证据，不是全状态等价或算术正确性证明。

## 复现

```sh
python3 experiments/coverage_flow.py \
  --replay-config experiments/designs/alu_replay.json \
  --rounds 3 --attempts 3 --patience 1 \
  --model deepseek-v4-flash-vision-exp --temperature 0.3 \
  --timeout 600 --request-retries 3 --jg-time-limit 120s --rag local \
  --env-file /path/to/provider.env \
  --out out/experiments/my-alu-v5-live
```

这会重新请求在线模型，输出不保证逐字相同。只验证本次保存回答时，去掉 `--env-file`，
依次传入两轮的 `--response-file`；这是离线流程回归，不能当作新的模型样本。
