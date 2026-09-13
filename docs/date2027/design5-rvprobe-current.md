# 设计 5：RVProbe 完成（2026-09-09）

设计为 **ue_timer**，RTL 顶层为 **timer**。本次只运行 RVProbe，结束原因是三轮预算用尽。
结果目录：`out/experiments/design5-rvprobe-current-20260909`。

## 结果

| 阶段 | 新 intent / sequence | 综合覆盖 | 本轮 tokens |
| --- | ---: | ---: | ---: |
| 共享 Stage-1 基线 | 6 条原始序列 | 81.7085% | 复用，不重复收费 |
| RVProbe 第 1 轮 | 6 / 24 | 96.3117% | 75,908 |
| RVProbe 第 2 轮 | 6 / 24 | 98.5965% | 67,572 |
| RVProbe 第 3 轮 | 4 / 16 | 99.8355% | 82,727 |

三个 UT 均首次编译、求解通过，无语法修复、无空输出、无模型重试。
共 16 个 intent、64 条新 sequence，全部通过独立 witness 回放。加基线共 70 条序列。
每个 intent 实际得到 4 条不同样本，来自求解器采样，不是四次模型生成。

最终 DUT 本体覆盖：

| 指标 | 已覆盖 / 总 bins | 百分比 |
| --- | ---: | ---: |
| Line | 118 / 118 | 100% |
| Condition | 120 / 120 | 100% |
| Toggle | 1208 / 1216 | 99.3421% |
| Branch | 81 / 81 | 100% |

剩余 8 个 toggle bins 属于 cfg_bresp_o、cfg_rresp_o 的两个方向；没有做覆盖排除，
也没有把预算停止当作不可达证明或将结果标为 100%。

## 时间和 token

- UTC：14:04:28—14:40:10；UTC+8：22:04:28—22:40:10。
- 含准备和基线的本次墙钟：2142.366 秒，约 **35 分 42 秒**。
- RVProbe arm：2131.887 秒，约 35 分 32 秒。
- 模型：deepseek-v4-flash-vision-exp，temperature=0.3；6 次 HTTP 请求（3 次 skill 读取＋3 次生成）。
- 总用量：**226,207 tokens**，全部有供应商 usage。
  输入 77,358；completion 148,849，其中 reasoning 144,650，其他 completion 4,199。
  reasoning 是 completion 的子集，不重复相加。

该数字是本次 RVProbe 新增成本，不包含已报告的 HAVEN Stage-1、原生闭环、失败与调试成本；
共享准备通过 checkpoint-provenance.json 引用，不计为免费生成，也不重复计入当前 arm。

## 共享组件与比较边界

复用 HAVEN 原始 Phase-5 编译检查点的 6 条基线序列；没有引入 HAVEN 后续 15 条序列或 Stage-2 答案。
共享回放入口现接受一个主动 agent 和附加被动观察者：唯一主动 driver 安装逐拍通道，
被动 agent/monitor 保持原文；拒绝附加 driver/sequencer 或非 driver 写接口引脚。
此次真实基线及 64 条 witness 均验证了该路径。

共享上下文使用 common-evidence-v2，prompt 使用 skill-owned-batch-v2；RAG 和 read_skill 均实际使用。
Stage-1 中的设计信息只作为本次任务证据，不进入 framework-only RAG 或通用 skill。

此前 HAVEN 跑的是原生闭环，本次 RVProbe 跑的是固定共享组件、独立 witness 回放和 DUT-only 计分。
二者虽然复用同一个 Stage-1，执行/计分策略仍不同，**不能标为严格配对比较**。
本次没有自动再跑 HAVEN。

## 核对

`verification.json` 审计通过：1395 个产物哈希、64 条 witness、268 个纯 witness 周期、2412 次已知输出检查。
纯 witness 周期不含复位/排空；输出一致性检查不等于独立功能正确性证明。
回归测试共 178 项：160 通过，18 项环境相关测试跳过。

可复核：

```sh
python3 out/diagnostics/audit-design5-rvprobe-current.py
python3 experiments/analyze_token_cost.py out/experiments/design5-rvprobe-current-20260909/paired \
  --out out/diagnostics/design5-rvprobe-token-analysis-new.json
```
