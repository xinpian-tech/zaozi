# 设计 5：HAVEN 原生流程跑通（2026-09-09）

最终结果：**15 条导出序列全部重新编译、仿真通过；UVM_ERROR=0、UVM_FATAL=0。**
本次只运行 HAVEN，不是 HAVEN/RVProbe 配对实验，也没有运行 RVProbe。

## 为什么多了被动 agent

Stage-1 模型把 AXI 主动驱动和中断输出观察拆成 cfg_agent、intr_agent。
后者是只观察 intr_o 的 passive agent，不驱动 DUT。HAVEN 原生模板本就支持这种组织。
新增 AXI 契约错误地用总 agent 数拒绝它；现在改为只允许一个主动发起者，允许附加被动观察者。
模型生成的拓扑保留，没有手工删除 agent、改写 DUT 或补入设计专用验证意图。
配对适配器目前仍单独限制总共一个 agent，尚未扩展；不能把原生跑通等同于配对入口跑通。

## 此次修复

1. 修正主动/被动 agent 的契约检查。
2. 将 Phase-2B 的确定性后处理拆为可复用函数，允许在新目录继续已保存的模型提取结果，不重复付费。
3. VCS 返回码为 0 不再代表仿真成功：检测实际 UVM_ERROR/FATAL，并要求零错误/零 fatal 的结束汇总。
   忽略 `UVM_ERROR : 0` 等正常摘要；修复后日志仍有错误时不接受覆盖。
4. DSL 中独立的 C 风格十六进制数字转成 SV 字面量；不修改字符串和注释中的文本。
5. 原生 IR 恢复优先加载 Phase-5 已编译的完整组件/序列，不再漏掉 subscriber；显式 final 产物仍优先。

这些更改保存在 `experiments/patches/haven-axi-transaction-contract.patch` 和对应哈希清单。
HAVEN 回归 147 通过、1 项 EDA 默认跳过；实验框架回归 159 通过、18 项环境相关跳过。

## 成功运行与覆盖

运行目录：`out/experiments/design5-haven-strict-v2-20260909`。
模型：deepseek-v4-flash-vision-exp，temperature=0.3；原生覆盖改善预算 3 轮，BO/VC Formal 关闭。
复用完整、已经编译通过的 Stage-1 六条初始序列，不复用此前失败的 Stage-2 答案。

原生闭环实测序列数依次为 6、9、12，原生顶层覆盖汇总为 80.455%、81.905%、82.6725%。
第三轮生成后达到预算，导出 15 条，但原生循环尚未仿真最后新增的 3 条。
因此另在 `final-verification/` 中独立编译、仿真全部导出产物并运行 URG，没有模型调用。
该验证保存源码哈希、编译日志、仿真日志、覆盖报告和耗时，不改写原生历史指标。

最终 **DUT timer 本体** 覆盖（不是混入 interface/testbench 的顶层汇总）：

| 指标 | 覆盖率 |
| --- | ---: |
| Line | 94.07% |
| Condition | 73.33% |
| Toggle | 75.58% |
| Branch | 88.89% |

这代表流程与最终仿真通过，不代表 100% 覆盖或独立功能正确性证明。

## 耗时与成本：失败也保留

| 阶段 | 已报告 tokens | 耗时 |
| --- | ---: | ---: |
| 首次 Stage-0～2B，拓扑检查拒绝 | 51,290 | 约 4 分钟 |
| 首次原生续跑，事后发现 Poll timeout | 121,530 | 610.263 秒 |
| 错误恢复检查点的中止运行 | 未知，至少 1 次已发请求 | 112.930 秒 |
| 最终严格 Stage-2，3 次模型请求 | 58,432 | 417.650 秒 |
| 全部 15 条独立最终验证 | 0 | 8.903 秒 |

累计已报告 **231,252 tokens，另有一次中止请求用量未知**，不声称总成本恰为这个数字。
最终严格 Stage-2 输入 17,659、completion 40,773；不将其单独冒充含共享准备和调试的完整实验成本。

首次原生续跑曾报告 completed/sim_ok，但日志实际 UVM_ERROR=1，审计已明确标为不可接受。
相关原始 summary 保留，并在该目录的 AUDIT.md 说明；错误恢复运行的 tracker=0 也已注明不代表免费。

复核入口：

- `out/experiments/design5-haven-strict-v2-20260909/summary.json`：原生闭环时间、成本。
- 同目录 `final-verification/summary.json`：全部 15 条最终严格验收。
- 同目录 `final-verification/sim.log`：UVM 零错误/零 fatal 证据。
- `out/diagnostics/retry-design5-haven-stage2-v2.py`：从完整 Stage-1 恢复的运行脚本。
- `out/diagnostics/verify-design5-haven-final.py`：无模型的最终产物验证脚本。
