# 设计 6–10 配对实验记录

更新时间：2026-09-10T05:21:58.914+00:00。成功配对 1/5；双侧已终止 4/5（包含失败）。

覆盖率按行／条件／翻转／分支排列，均为顶层百分比。失败侧只列已接受状态；0 轮表示没有接受新增候选。

| 设计 | 侧 | 状态 | 接受轮数 | 覆盖率（%） | 已报告 tokens | 侧耗时（秒） |
| --- | --- | --- | ---: | --- | ---: | ---: |
| 6 UART | haven | completed | 2 | 97.62 / 88.46 / 94.39 / 96.43 | 153206 | 353.37 |
| 6 UART | rvprobe | failed | 0 | 96.43 / 84.62 / 88.05 / 94.64 | 257987 | 1592.56 |
| 7 CAN | haven | failed | 1 | 100.00 / 91.67 / 93.89 / 100.00 | 519072 | 675.01 |
| 7 CAN | rvprobe | failed | 0 | 100.00 / 91.67 / 90.35 / 100.00 | 327668 | 1145.21 |
| 8 Ethernet | haven | not_started | 0 | — / — / — / — | 0 | 未结束／未启动 |
| 8 Ethernet | rvprobe | not_started | 0 | — / — / — / — | 0 | 未结束／未启动 |
| 9 I2C | haven | completed | 2 | 83.64 / 94.44 / 93.93 / 82.76 | 123398 | 298.14 |
| 9 I2C | rvprobe | failed | 1 | 83.64 / 100.00 / 95.33 / 82.76 | 275805 | 1001.83 |
| 10 GPIO | haven | completed | 2 | 100.00 / 69.83 / 70.43 / 74.07 | 109950 | 209.69 |
| 10 GPIO | rvprobe | completed | 3 | 100.00 / 100.00 / 92.00 / 100.00 | 183144 | 1845.89 |

本日期全部配对尝试及共享 setup 已知费用合计：3,717,956 tokens。包含失败与被替代尝试；不包含之前日期的费用。未结束请求可能增加费用。

多设计曾并行运行。耗时包含资源竞争及不同的序列数量，不应直接解释为隔离条件下的工具速度比。

## 运行目录

- 设计 6：`out/experiments/design6-uart-complete-pair-20260910-v1`；failed
  - rvprobe：shared simulation failed or timed out: UVM_FATAL uart_top_wb_agent__driver.sv(145) @ 325000: uvm_test_top.m_env.m_wb_agent.m_driver [WITNESS] wb_dat_o event mismatch; UVM_FATAL :    1; missing completion marker
- 设计 7：`out/experiments/design7-can-complete-pair-20260910-v2`；failed
  - haven：Command '['/tmp/claude-0/-root-yjh-workspace/491fc915-de96-4db8-85de-375362ef0be6/scratchpad/haven/.venv/bin/python', '/root/yjh-workspace/rvprobe-workspace/zaozi/experiments/coverage_flow.py', 'request', '--directory', '/root/yjh-workspace/rvprobe-workspace/zaozi/out/experiments/design7-can-complete-pair-20260910-v2/paired/haven/round-2-repair-1/attempt-1', '--options', '/root/yjh-workspace/rvprobe-workspace/zaozi/out/experiments/design7-can-complete-pair-20260910-v2/paired/haven/round-2-repair-1/request-options.json', '--env-file', '/tmp/claude-0/-root-yjh-workspace/491fc915-de96-4db8-85de-375362ef0be6/scratchpad/haven/.env']' returned non-zero exit status 1.
  - rvprobe：shared simulation failed or timed out: UVM_FATAL can_top_wb_agent__driver.sv(133) @ 315000: uvm_test_top.m_env.m_wb_agent.m_driver [WITNESS] wb_dat_o event mismatch; UVM_FATAL :    1; missing completion marker
- 设计 8：`out/experiments/design8-ethmac-complete-pair-20260910-v2`；shared simulation failed or timed out: UVM_ERROR sequence_1.sv(278) @ 5029115000: uvm_test_top.m_env.m_wb_master_agent.m_sequencer@@seq_1 [wait_rx_done] Poll timeout; UVM_ERROR :    1
- 设计 9：`out/experiments/design9-i2c-complete-pair-20260910-v2`；failed
  - rvprobe：shared simulation failed or timed out: UVM_FATAL i2c_master_top_wb_agent__driver.sv(120) @ 305000: uvm_test_top.m_env.m_wb_agent.m_driver [ENVIRONMENT_WITNESS] sda_pad_i live response differs from symbolic response; UVM_FATAL :    1; missing completion marker
- 设计 10：`out/experiments/design10-gpio-complete-pair-20260910-v2`；completed

全部原始尝试、共享 setup、费用修正和错误保存在同名 JSON 中。未用成功重跑覆盖失败记录。

解释和已知限制见 [本批实验边界](design6-10-current-limitations.md)。
