# ETHMAC：寄存器采入 Z 的求解/回放差异（2026-09-11）

结论：本轮定位并独立复现了具体语义差异，**没有修通 ETHMAC 或新增正式配对**。
没有调用 DeepSeek、手工修改实验 LTL、重建 Stage-1 或改动 DUT。
以下小电路是工具语义测试，不作为模型生成结果计分。

后续已确认 JG **XPROP 应用原生能够追踪该不确定性传播**，但其假设不作用于
当前普通 FPV Cover。本文的语义差异限定于已测 FPV 路径，不表示所有 JG 应用
都不能建模。详见 [XPROP 参数与作用范围核对](jg-xprop-settings-20260911.md)。

## 真实 RTL 与波形

本地 HAVEN ETHMAC 源码的 `eth_spram_256x32.v:265`：
`assign dato = (oe & ce) ? q : {32{1'bz}};`。
`eth_wishbone.v:719–724` 根据 RAM 仲裁状态生成输出使能。
`ethmac_defines.v:317` 启用 `ETH_REGISTERED_OUTPUTS`；`ethmac.v:560`
将 `temp_wb_dat_o` 采入 `temp_wb_dat_o_reg`，再由后者驱动外部输出。

用 batch-j / ETHMAC / round-1 中原始 `bd_word_write_readback_value` 的
第 12 号候选导出完整 DUT 波形。输入/复位/事件回放检查通过，原始 Cover 仍失败。
四个主时钟事件发生于 315001、325001、335001、345001 ps。
第一次事件锁存写请求；第二次执行写入；最后一次才锁存读请求，
`BDRead` 从 0 变成 1，但 `WbEn_q=0`，`ram_oe` 尚未打开。
输出寄存器因此仍含 `Z`，不能作为全 1 的有效读回。
这不证明原始 LTL 不可达，只证明该形式 witness 不能原样实现其目标。

## 独立工具复现

仓库夹具：[tristate_cover_probe.sv](../../experiments/tests/fixtures/tristate_cover_probe.sv)。
其中组合输出 `bus = enable ? stored : 4'bzzzz`，另一个寄存器每拍采入 bus。
测试不引入任何 benchmark 的寄存器地址、协议流程或答案。

| Cover 条件 | JG 默认 | JG `-triple_equal` | VCS 本次仿真 |
|---|---|---|---|
| 禁用输出时，直接 case 比较 bus 等于全 1 | covered | unreachable | 0 match |
| 禁用输出时，归约 AND 的结果 case 比较为 1 | covered | covered | 0 match |
| 禁用输出后一拍，输出寄存器 case 比较等于全 1 | covered | covered | 0 match |
| 禁用输出后一拍，输出寄存器 case 比较等于全 Z | unreachable | unreachable | 7 match |

加入 `-show_internal_flops` 后，本机 JG 2021.03p002 仍给出相同结果。
VCS 明确打印出禁用时 `bus=zzzz registered=zzzz`，启用时为 `1111`。
VCS 的有限仿真次数不冒充不可达证明；关键证据是同一夹具实际寄存器保留了 Z，
而形式端仍能为该寄存器选择满足全 1 比较的值。

因此，仅检查组合端口是否为 Z，不足以消除 Z 经寄存器传播后的乐观取值。
先归约/比较再对结果做 case 判断也不等价于完整的四态确定性跟踪。
不能把 `-triple_equal` 或已有 XPROP 开关直接当作修复。

## 被否决的原型与成本

保持原始 Cover，诊断采样长度从 4 扩为 16；尝试两种自动生成的非悬空输出软偏好：

- Visualize 直接表达式：1 个原始候选 + 4 个新候选，0/5 原生通过，67.396 秒。
- 在辅助 HDL wire 中编译 case 判断后再引用：同样 0/5，66.207 秒。

第二种原型的形式波形甚至在实际输出寄存器含 Z 的对应阶段，将输出确定性
辅助 wire 判为 1。这使下一步应针对的范围从输出端检查收敛到时序确定性传播。
两种原型均未进入正式流程；新增选项及其专属单元测试已从工作框架撤下，
原型代码留在归档的 `rejected-prototype/`。原始 LTL 与采样前目标均保留。
更早的路径错误、标量下标错误、编译夹具 timescale 错误也保留，不算模型失败。

## 存储与回归

归档根：`/var/storage/workspaces/clo91eaf/rvprobe-tristate-diagnosis-20260911-v1/`。

- `probe-v2.sv`、`probe-v2.tcl`、`probe-v2.log`：三组 JG 开关与逐属性状态。
- `native-probe.sv`、`native-probe-v2.log`：VCS 激励、寄存器取值和 Cover 命中数。
- `offline/rvprobe-ethmac-waveform-20260911-v2/`：原始真实候选与完整 DUT VCD。
- `offline/rvprobe-ethmac-driven-20260911-v{1,2,3,4}/`：所有原型尝试。
- `final-tests.log`：334 项常规回归，321 通过、13 项跳过。

首次直接在该 NFS 挂载运行 VCS 时发生 `VFS_SDB_ERROR`；
`ethmac-waveform/summary.json` 保留这一基础设施失败，不归因于模型。
后续 VCS 使用本地临时工作目录，材料归档到上述用户目录；
归档排除 `csrc`、`simv`、`simv.daidir`、`__pycache__` 可重建缓存。
JG 小电路测试可直接在该 NFS 目录执行。

下一步需要验证自动确定性状态建模，涵盖寄存器采样、复位、组合传播及未初始化存储，
并继续使用原始 Cover 做原生验收；不得改模型 LTL、替换 Z 为常数、取消检查，
也不得据此假定 CAN/SDRAM 的全部失败都有相同根因。正式配对仍为 11/16。
