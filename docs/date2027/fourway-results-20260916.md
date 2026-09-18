# 四方法 × 16 设计：四项 HTTP 402 续跑后的结果

截至 2026-09-16 00:28:50 UTC（UTC+8 08:28:50），指定的四项续跑全部正常结束。
本次两并发续跑耗时约 34 分钟，新增已报告用量 1,706,243 tokens。
64 个预指定设计/方法组合均已结束尝试：48 正常结束、16 失败；失败保留，不挑选重跑结果。

## 完整结果

文件在 `/var/storage/workspaces/clo91eaf/rvprobe-fourway-402-continuation-20260916-v1/report/`：

- `results.csv`：64 项的覆盖率、token、模型请求数、轮次、时间、状态和失败原因。
- `results.json`：另含覆盖 bins、原始结果路径、续跑增量/旧费用、失败证据和统计口径。
- `results.md`：方法汇总和逐项表格。

覆盖率是每个设计的有效 line/cond/toggle/branch/fsm 百分比平均；下表再对 16 个
设计等权平均。包括失败项的最后有效测量，不是只计算成功子集，不是跨设计 bin 合并。
轮次列是覆盖闭环尝试轮次之和，含失败及 STOP 的轮次；不是 HTTP 请求数。
耗时是各设计 Stage-2 时间之和，不是并发批次的墙钟时间。

| 方法 | 正常结束/失败 | 平均综合覆盖率 | 已报告 tokens | 尝试轮次 | Stage-2 总小时 |
|---|---:|---:|---:|---:|---:|
| Directed stimulus | 10/6 | 94.09% | 5,829,327 | 37 | 2.90 |
| Directed SVA | 12/4 | 90.53% | 6,302,089 | 38 | 4.77 |
| HAVEN Stage-2 | 14/2 | 92.68% | 4,158,207 | 40 | 1.69 |
| RVProbe | 12/4 | 93.53% | 7,572,035 | 39 | 4.16 |

已报告合计 23,861,658 tokens。Direct stimulus / Direct SVA / RVProbe 分别有
4 / 2 / 1 个单元格包含未返回 usage 的请求，因此相应用量是已知小计，不是完整精确费用。
HAVEN 16 项 usage 完整。HTTP 402 历史调用的缺失 usage 不在续跑成功后抹去或擅自算零。
Stage-1 是固定环境，不计其生成开销；模型请求名仍为 deepseek-v4-flash-vision-exp。

本批不能声称 RVProbe 的 token 少于 HAVEN：已报告 token 为其约 1.82 倍，平均覆盖率
高约 0.85 个百分点。只有单次实验，且含失败、不同输出接口与上下文策略，不能据此
下普遍鲁棒性或统计显著性结论。直接激励没有 property 验收，覆盖率不等于意图满足率。

## 本次四项

下表累计数据包含 402 前的已接受轮次和此次续跑。等待充值的停机时间不计入累计耗时。

| 方法/设计 | 最终覆盖率 | 累计 tokens | 本次新增 tokens | 已记录/尝试轮次 | 累计 Stage-2 秒 | 结束原因 |
|---|---:|---:|---:|---:|---:|---|
| Directed stimulus / UE_UART | 89.45% | 422,321 | 100,526 | 3/3 | 1,014.69 | 原三轮预算耗尽 |
| Directed SVA / UE_UART | 89.45% | 284,824 | 45,800 | 3/3 | 1,360.18 | 原三轮预算耗尽 |
| Directed stimulus / SDRAM | 93.34% | 1,291,073 | 810,431 | 2/3 | 1,225.19 | 第三轮模型 STOP，无新增序列 |
| Directed SVA / SDRAM | 89.85% | 749,486 | 749,486 | 2/2 | 1,586.92 | 第二轮覆盖率停滞 |

正常结束仅代表实验流程正常终止，不保证每个 intent 都可解或生成了新激励。
Direct SVA / SDRAM 第一轮从 72.27% 提升至 89.85%，第二轮未再提高；不可达和
限时未判定的 intent 在原始记录中保留。

## 审计

- 四项旧 summary 的 SHA-256 与续跑启动记录一致，旧轮次逐项保持。
- 四项首次请求在离线预检和正式发送前均核对原被拒请求的 payload 一致性。
- 增量用量只扫描新记录；累计 token、时间和旧/新分账核验通过，没有重复计旧轮次。
- HAVEN/RVProbe 全部覆盖率、状态、token、轮次、时间与原报告一致，未重跑或改结果。
- 64 项覆盖率分母无不一致；16 设计四方法基线 bins 无不一致。
- 四项均归档并校验后迁出内存盘，原 scratch 路径保留为归档软链接。
- 原总报告不覆盖；通过显式 `continuations.json` 映射导出此版本。

重新导出：

```bash
python3 experiments/fourway_report.py \
  --root /var/storage/workspaces/clo91eaf/rvprobe-fourway-20260915-v1 \
  --ethmac /var/storage/workspaces/clo91eaf/rvprobe-ethmac-output-bits-20260915-v1 \
  --continuations /var/storage/workspaces/clo91eaf/rvprobe-fourway-402-continuation-20260916-v1/continuations.json \
  --output /var/storage/workspaces/clo91eaf/rvprobe-fourway-402-continuation-20260916-v1/report
```
