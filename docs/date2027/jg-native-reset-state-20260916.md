# AXIL_RAM 原失败 LTL：JG 原生复位状态导入修复

2026-09-16，离线诊断，零模型请求。没有修改 LTL、DUT、Stage-1、HAVEN 或既有论文结果。

## 结果

冻结原四方法实验中的 `read_data_toggle_after_two_writes`。此前原 JG 候选的
16 次回放均未命中原性质，进入 Yosys 辅助编码后，`memory_map` 报
`Non-constant enable ... in memory initialization`。

修复后，真实 harness 编译原完整 LTL 并求解，原失败目标经原采样器生成
4 条不同输入 sequence，全部通过原 RTL、原 Cover 回放。LTL 原件与新副本
SHA-256 均为 `6d3c3b0b9dacb3a188899b50a7efba62b1d50d56a1d2d047ab607ab70fde2da8`。
最终正例诊断耗时 75.67 秒。没有调用 Yosys 或 DeepSeek。

负例仅把候选 witness 的写数据输入改为全零；原 Cover 验收按预期拒绝，
`formal_replay_semantics_mismatch`。负例诊断耗时 49.22 秒。

这修复了此 case 的原生求解初始化路径，不是修复了 Yosys 本身的 memory_map。
也不是新的付费三轮实验，不能把这些诊断覆盖率替换论文结果。

## 根因证据与实际配置

原 JG 日志明确报告 `initial construct ignored`。AXIL_RAM 原 RTL 的循环
把 RAM 初值清零，原 formal reset sequence 没有传入这些值。

现在框架自动发现原 DUT 时序存储，由 VCS 执行原 `initial` 和固定 reset/idle
前缀，取得复位后的状态；JG 求解及采样均使用同一份有哈希溯源的快照：

```tcl
set_cumulative_reset on
reset -init_state {reset-snapshot.state}
reset -sequence {reset.seq}
```

这里传的是**复位后状态**，不是上电初值。`set_cumulative_reset` 合并配置，
不保证把第一份状态当作第二次 reset 仿真的起点。回归发现：初值 5、复位
期间应递增到 7 的计数器，若误传上电快照会仍为 5；改传自动仿真的复位后
快照后为 7，复位清零标志为 0，非零初始化 RAM 的值也与 VCS 一致。

原 reset sequence、时钟和环境关系保持不变，没有增加模型编写的 Assume，
没有增加 `stopat` 或关闭 EFL058 检查。原 LTL（含 past）不重写，最终仍以
原 RTL 回放验收。不声称任意四态电路或任意 reset-history 已完成等价证明。

## 支持边界与防护

- 自动路径目前限独立单时钟、单模块、可发现的有限整型时序存储。
- 只接受支持子集内确定、无延时的初始化表达式／循环；涉及 IO、随机数、
  非支持操作或跨 initial 块写竞争时不启用这条路径。
- 捕获整个已发现 DUT 时序状态，排除过程循环变量；不是只填一块 RAM。
- X 位保留为未知，全部未知的项不填零；不采用全局 `non_resettable_regs 0`。
- 不支持的设计明确记录原因并保留原路径；工具、许可证和文件错误仍报错。
- prepared job 保存快照与来源哈希，后续采样检查原设计、reset/idle 配置、
  实现、探针、日志和状态文件，拒绝状态或来源篡改。
- 旧已归档 job 不被自动重写；正式实验指标保持原样。

## 验证与实现

- Python 全量回归：535 项，514 通过、21 项条件性跳过。
- 单独启用 EDA 的初始化测试通过，涵盖非零初值、真实复位推进、RAM 内容。
- Scala JasperGold 测试 6/6 通过，harness 实际编译了新的求解器接口。
- `git diff --check` 通过。

主要实现：`experiments/rtl_initial_state.py`、`experiments/ut_harness.py`、
`experiments/src/TrustedSolver.scala`、`utlib/src/JasperGold.scala` 和
`experiments/witness_sampling.py`。不改变模型 LTL API 或 prompt。

复现入口：`experiments/smoke_native_initial_state.py --framework`，提供原
`--source-solve`、`--replay-config`、`--bundle`、`--haven-root`、`--label`
及新的 `--out`；添加 `--negative-drive s_axil_wdata=0` 运行负例。
原来源均记录在旧四方法 AXIL_RAM 与诊断目录中。

## 归档与失败尝试审计

最终正例：`/var/storage/workspaces/clo91eaf/rvprobe-native-init-axil-20260916-v6`。

最终负例：`/var/storage/workspaces/clo91eaf/rvprobe-native-init-axil-20260916-negative-v2`。

v1–v5 及 negative-v1 是前期诊断，也保留；v3 记录直接在 reset sequence
首部放所有初值触发的 EFL058。v4/v5 的 AXIL_RAM 通过并未证明上电快照方式
普遍正确，已由上述非零计数器回归否定其通用性，最终使用 v6 策略。

v2 曾因 Tcl 中保留旧 scratch 别名，导出覆盖了旧 witness 的日期行。
已修复路径重定向并恢复旧文件；完整 SHA-256 恢复为原记录的
`fabc1890a8ba4e1744d0b8815b274f80b16bfb0df2af1a1ed03e605cd9d2e7f1`，
不是修改旧记录来接受新文件。旧原始波形和正式结果最终保持不变。

这些诊断目录均校验归档后用软链接替换 `/dev/shm` 副本，释放内存盘空间。
