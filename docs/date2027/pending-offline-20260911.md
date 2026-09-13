# 未完成设计离线复测（2026-09-11）

用户要求先自行验证、不调用 DeepSeek。本轮未新增或修改模型 LTL，未重建
Stage-1，未补齐截断响应。采用当前 `independent-dut-v1` 共享输入边界，逐条
回放 batch-j 中各侧最后一个完整候选；不补采样，不选择性删除失败样本。
这是定位失败的诊断，不是新的完整闭环配对实验。

## 结果

| 设计 | HAVEN 通过/检查 | RVProbe 通过/检查 | 未通过项 |
|---|---:|---:|---|
| I2C | 4/4 | 16/16 | 本次保存候选全部通过 |
| CAN | 4/4 | 4/16 | 发送 dominant 位、acceptance code 写后读回、dominant RX 错误标志/恢复，各 4 条未命中原始 Cover |
| SDRAM | 4/4 | 8/12 | `write_then_readback_matching_word` 的 4 条未命中原始 Cover |
| ETHMAC | 3/4 | 12/16 | HAVEN `ethmac_gap_dma_master_sel_addr_seq` 等待 `wait_rxb` 超时；RVProbe `bd_word_write_readback_value` 的 4 条未命中原始 Cover |
| simple_spi | 3/3 | 16/16 | 可用候选通过，但它来自上一轮；第二轮截断输出仍无完整候选可回放 |

合计 95 条候选，74 条通过、21 条失败。双任务并发，整体耗时 472.603 秒，
远端模型请求为零。每个任务有独立的实际时间记录；这里不虚构作者 token 成本。
全部通过候选的产物哈希已核验，失败日志和候选源码均保留。

本轮没有修改旧 witness。因此，CAN/SDRAM 的旧失败仍然失败，并不与先前
人工诊断 UT 的通过结果矛盾。先前的 8/8 只证明那份人工 UT 可由原生验证及
补采样跑通，不能据此宣称真实模型的其他 UT 或整个设计闭环已经完成。

## 实际复测来源

| 设计 | HAVEN 候选目录 | RVProbe 候选目录 |
|---|---|---|
| I2C | round-2-repair-1 | round-2-repair-1 |
| CAN | round-2 | round-1-repair-1 |
| SDRAM | round-3 | round-3-repair-1 |
| ETHMAC | round-2-repair-1 | round-1 |
| simple_spi | round-3 | round-1-repair-1 |

来源根：`/dev/shm/rvprobe-fixed16-20260910-30xOp6/batch-j/<design>/flow/paired/<arm>`。
ETHMAC 后续修复对话耗尽预算、simple_spi 第二轮响应截断，没有完整候选，
本轮不假装重现不存在的最终输出。每个候选 SHA256、共享 Stage-1 路径、原始失败
与本轮序列级失败均记录在诊断汇总中。正式完整配对计数仍为历史的 11/16。

## 框架检查

常规检查原有 16 项默认跳过的测试，本轮显式开启检查：

- 13 项真实 CIRCT/scalac/JG/VCS 测试全部通过，包括原生 past、不可达目标、
  独立目标、部分成功/恢复、完整输出位重建、初始化与正反向回放。
- 3 项 HAVEN 模板/代码生成测试发现一个旧夹具错误：直接调用后端时，未提供
  正常入口注入的 `intent_batch_limit`。补充真实框架的 `MAX_INTENTS` 后，3/3 通过。
- 修正后常规测试重跑：315 项通过，13 项真实工具检查在上述独立运行中已通过。
  即 328 个唯一测试均有通过证据（分批验证），不是把跳过当作通过。

第一次启动指定工具测试时漏设 Python 模块搜索路径，4 个模块未能导入，未执行
实际测试；修正 `PYTHONPATH` 后才得到上述真实工具检查结果。首次真实 16 项运行
保留了夹具错误，修正后的相关类与常规套件另行复验，没有覆盖原失败记录。

## 产物与复现

工作目录：`/tmp/rvprobe-pending-offline-20260911-v1`。
持久归档：`/var/storage/workspaces/rvprobe-pending-offline-20260911-v1`。
`summary.json` 含全部 10 个设计/侧任务，`<design>/<arm>/summary.json` 含逐条结果。
归档保留原始 LTL 监视器、实际 sequence、波形采样、编译/运行日志、覆盖率产物、
测试日志与框架源码快照；仅不复制可重新编译的缓存和二进制。

复现入口为 `experiments/offline_pending.py`，参数 `--tracking` 指定
`out/experiments/fixed16-tracking-20260910.json`，`--designs i2c can sdram ethmac simple_spi`，
另提供 `--haven-root` 与新的 `--out`。从 flake 环境下通过 `experiments/haven-python`
运行。子进程禁止模型调用，使用固定 Stage-1；不以此替代后续正式闭环实验。
