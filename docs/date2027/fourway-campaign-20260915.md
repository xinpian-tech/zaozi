# 四方法 × 16 设计实验

目标：所有预先指定的设计/方法均完成一次尝试。单项截断、超时、不可解或框架失败都
保留并继续其他项；终态不等于成功，不用历史成功数据替换失败。

## 方法定义

| 方法 | LLM 输出 | 执行路径 |
|---|---|---|
| directed stimulus | JSON 输入赋值与保持周期；每 intent 显式给出最多 4 条序列 | 固定多时钟 raw IO 回放，无求解器 |
| directed SVA | JSON 中的命名 SVA property 表达式 | 确定性单 DUT wrapper → JG → 与 RVProbe 共用采样、四态候选修正和原生 Cover 验收 |
| HAVEN Stage‑2 | 原生 HAVEN DSL | 固定驱动/BFM 模板；未修改 HAVEN 的提示词或实现 |
| RVProbe | zaozi LTL fragment | 现有完整后端，包括输出位别名修复 |

两个 direct 方法是本次新增基线，不是某个外部论文工具的原样复现。
directed stimulus 的 JSON 是数据接口，不是随机生成程序或 UVM 模板。
step 在下一个主时钟上升沿前更新输入，其余输入保持，所有时钟按固定周期运行。
固定 clock/reset/static/secondary reset 不由该方法覆写；每条序列最多 10000 个主时钟周期、
4096 个 step。SVA 只能表达 IO 上的纯 property，不能注入模块、层级访问、assume 或任意程序。

## 共同控制与已知差异

- 16 设计以 `experiments/design_inventory.py` 为准，总计 64 单元格。
- 模型固定 `deepseek-v4-flash-vision-exp`，temperature 0.3，种子 20260906。
- 固定 Stage‑1：`/var/storage/workspaces/clo91eaf/fixed16-environment-20260912-v3/stage1-map.json`。
  Stage‑1 是实验环境，不调用模型重新生成，不计入 Stage‑2 token。
- 最多 3 轮；综合覆盖率增益不足 0.1 个百分点停止，目标 100%。每轮至多 4 intents。
  RVProbe / direct SVA 每 intent 最多 4 条实际通过原生验收的 witness；direct stimulus
  每 intent 由模型显式生成 1..4 条序列；HAVEN 保持其原生 DSL 批次方式。
- 每次模型 HTTP 请求 600 秒硬限时；候选源码最多 3 次尝试；仿真失败最多 1 次运行期修复。
  只对对话尚未完成任何步骤时的明确 transient HTTP 状态最多重试 3 次，未知投递和截断不重发。
- direct 两方法与 RVProbe 均从 spec/IO 开始，RTL 按需读取，24 次模型请求/64 次工具条目的
  对话预算相同。后续请求传入实际读取结果，不传入思维链。接口教程按方法不同；没有 DUT 答案。
- HAVEN 保持此前固定的 full-inline/native-DSL 上下文，不宣称四方法 prompt 完全相同。
- 所有方法共享固定基础序列、DUT、复位、覆盖指标和独立序列仿真方式。
  directed stimulus 没有 property，不能声称其激励满足自然语言验证意图；只报告实测覆盖率。
- 直接 SVA 不依赖 Scala/zaozi 类型检查。表达式先结构校验，再由 JG 编译。
  初始 JG prove 之后复用现有采样与原生验收；需要四态修正时使用同一 Python 后端。

## 批次与归档

总目录：`/var/storage/workspaces/clo91eaf/rvprobe-fourway-20260915-v1`。
预先保留已在运行的 RVProbe ETHMAC：
`/var/storage/workspaces/clo91eaf/rvprobe-ethmac-output-bits-20260915-v1`。
它是同次输出别名修复后的独立新实验，不按结果选择是否纳入，不重新重复付费运行。

服务（one-shot，无定时器）：

- `rvprobe-fourway-haven-20260915-v1`：16 个 HAVEN 单元格，顺序运行。
- `rvprobe-fourway-rvprobe-20260915-v1`：除 ETHMAC 的 15 个 RVProbe 单元格，顺序运行。
- `rvprobe-ethmac-output-bits-20260915-v1`：先前启动的 RVProbe ETHMAC。
- `rvprobe-fourway-direct-20260915-v1`：32 个 direct 单元格，动态使用以上服务释放的名额。

总计最多 3 个正式设计任务并发。每个单元格终态后归档、哈希校验、迁出 `/dev/shm`；
临时编译目录也归入对应单元格。源码、响应、工具观察、用量、求解、回放、覆盖率和失败日志保留。
源码版本按每个任务实际启动时的 manifest 记录；新增前端实现期间未修改现有 HAVEN/RVProbe 实现。

`results.json`、`results.csv`、`results.md` 每 30 秒左右更新，包含 64 项，
覆盖率为最后有效测量；缺失用量是 unknown 而不是 0；部分已报告 token 用 † 标注。
Stage‑2 wall time 与含环境准备/基础仿真的总时间分列，不能相加嵌套阶段时间。
运行中单元格的完整费用和覆盖率需等终态；保留请求级原始记录可用于实时诊断。

## 实现与测试

- `directed_baselines.py`：只读模型对话、输入序列校验/时序映射、SVA wrapper、共享后端适配。
- `directed_experiment.py`：固定环境准备与单方法覆盖率闭环。
- `direct_baseline_batch.py`：动态并发、单项失败隔离、归档、等待全部方法和导出。
- `fourway_report.py`：固定 64 项的 JSON/CSV/Markdown 汇总与覆盖分母检查。
- ALU 离线 smoke：directed stimulus 1 条序列完整仿真通过；directed SVA 4 条 witness
  全部通过原生验收。使用手写测试输入，0 模型 token，不进入正式结果。

重新导出：

```bash
python3 experiments/fourway_report.py \
  --root /var/storage/workspaces/clo91eaf/rvprobe-fourway-20260915-v1 \
  --ethmac /var/storage/workspaces/clo91eaf/rvprobe-ethmac-output-bits-20260915-v1
```

## HTTP 402 四项续跑（2026-09-16 批次名，UTC 09-15 23:54 启动）

用户明确要求继续以下四项：directed stimulus / UE_UART、directed stimulus /
SDRAM、directed SVA / UE_UART、directed SVA / SDRAM。前三项保留已接受的两轮，
仅续第三轮；最后一项从第一轮开始。最多三轮的原始预算不变。

- 原始目录和报告不覆盖。续跑目录为
  `/var/storage/workspaces/clo91eaf/rvprobe-fourway-402-continuation-20260916-v1`。
- 仅允许失败轮首次 HTTP 请求明确返回 402，且未产生响应、工具结果或已完成对话的检查点。
  固定 Stage-1、基线、已接受仿真文件和缓存经哈希核验；恢复已接受序列及证据历史。
- 每项首次续跑请求的 JSON payload 必须与原来被拒绝的请求完全相等，否则调用模型前拒绝。
  四项均已用 fake sender 通过离线完整 payload 比对，没有在预检中调用模型。
- 每项独立进程，最多两项并发。完成即校验归档并以归档软链接替换内存盘副本。
  若再次遇到 402，不再启动排队项；不反复探测余额。
- `summary.json` 是本次增量；`cumulative-summary.json` 是旧轮次加本次的累计
  token 和活跃运行耗时，不包含等待充值的间隔。未知用量仍标未知，不能算零。
- `fourway_report.py --continuations MAP.json --output NEW_REPORT_DIRECTORY` 接受
  显式的 `method/design` → `cumulative-summary.json` 映射，并核验其原始结果身份。
  不从多个重跑中挑选最好结果，不修改 HAVEN 或 RVProbe 已有结果。

续跑实现：`continue_direct_402.py`、`continue_direct_batch.py`。
验收覆盖断点、原轮次预算、未知 token 累加、独立报告导出和原始结果保留。
