# 保存的真实模型响应：离线修复与验证（2026-09-11）

本轮遵守“不调用 DeepSeek”：不手工代写实验 LTL，不修改历史候选，不重建
Stage-1，不把离线诊断记为新的正式配对。使用各设计上轮复测所对应的最后一个
完整模型响应，重新走编译、JG 求解、采样、原生 Cover 检查与覆盖率汇总流程。

## 已修复

- 原生 Cover 失败时，之前成功完成的输入、复位、时序校验及输出波形差异会随
  异常丢失。现在 `coverage_flow.py` 在判定 Cover 前保存 `replay-checks.json`，
  异常和 `selection.json` 引用该文件。原始 Cover 仍是唯一接纳标准。
- 有界原生搜索耗尽不再统一误报为共享环境故障；使用独立的
  `native_witness_search_exhausted` 分类，汇总保留失败诊断、尝试数和证据路径。
  这不放宽接纳条件，也不会自动请求模型重写 LTL。此前已启动的诊断保留其
  原始分类，未回改历史失败报告。
- 增加 `offline_saved_flow.py`，只接受有完整 provider 记录且与落盘 UT 逐字一致
  的原始响应。模型名、RTL/IO/spec、Stage-1 和响应哈希均核验；最多一个离线
  generation worker，禁止修复请求和任何远端回退。历史 token/time 单独保留，
  新调用数为零。支持生产解析器本来就接受的 Markdown JSON fence。

失败证据回归：`/tmp/rvprobe-ethmac-failure-evidence-20260911-v1`。
第 12 条候选仍正确失败，传输校验通过且记录 7 处波形差异；其中采样行
26、28、30 的 `wb_dat_o` 在 JG 中为 `0xffffffff`，实际仿真为全 `Z`。
`known=0` 不是数值零的证据；原始四态输出仍保存在 `sim.log`。

## 完整离线重新求解的结果

| 设计 | 原始模型响应来源 | 当前结果 |
|---|---|---|
| CAN | batch-j / round-1-repair-1 / attempt-2 | 四个目标均能求解；第一个目标 4/4 原生通过，发送目标 0/16，候选整体拒绝，后两个目标没有进入本次原生采样 |
| ETHMAC | batch-j / round-1 / attempt-1 | 前三个目标各 4/4；描述符写后读回 0/16，候选整体拒绝 |
| SDRAM | batch-j / round-3-repair-1 / attempt-1 | 两个写目标各 4/4；写后读回 0/16；双写后双读目标在 120 秒求解预算内为 unknown，候选整体拒绝 |

CAN 耗时 368.541 秒；ETHMAC 耗时 400.557 秒；SDRAM 耗时 736.107 秒，
于 08:38:47 UTC 结束。这里的 16 是包含初始候选在内的
有界搜索尝试数，不是宣称生成了 16 条合格 sequence。失败样本不丢弃、不计成功。

工作目录：

- `/tmp/rvprobe-saved-can-fix-20260911-v1`
- `/dev/shm/rvprobe-saved-ethmac-fix-20260911-v1`
- `/dev/shm/rvprobe-saved-sdram-fix-20260911-v1`

## 尚未解决，以及已排除的捷径

### HAVEN ETHMAC 超时是候选自身的地址不匹配

保存的 `ethmac_gap_dma_master_sel_addr_seq` 写 MAC_ADDR0=`0x00112233`、
MAC_ADDR1=`0x4455`，随后发送目的 MAC=`00:11:22:33:44:55`。
`eth_registers.v:939–940` 将两个寄存器拼为 `44:55:00:11:22:33`；
MODER=`0xA403` 的 bit 5 为零，`eth_rxaddrcheck.v:126,178–193` 因此按实际地址
过滤这个帧。等待 RXB 的超时不能通过修改 BFM 或增加轮询次数来修正。
本轮不手工修改模型序列；HAVEN prompt 和共享 Stage-1 保持不变。

### 单纯扩大轨迹长度没有恢复失败

增加了明确的诊断选项 `search_native_witness.py --horizon-multiplier {1,2,4}`。
采样函数默认仍保持原始长度，正式闭环没有启用延长策略。选项保留原始 Cover、
RTL、复位与假设，仅改变诊断求解时间范围，所有样本仍需原生验证。

- ETHMAC 原始 4 拍扩为 16 拍：1 个原始样本和 4 个重采样均失败，58.602 秒。
- CAN 发送目标扩为 2 倍长度：1 个原始样本和 4 个重采样均失败，65.719 秒。

这两个结果没有证明目标不可达，也没有证明扩大任意范围必然无效；仅说明本次
有限搜索没有修通，因此不把这种策略接入正式流程或算作修复成功。

### 不能简单在 Cover 中加入 `$isunknown`

检查了本机 JG 2021.03 的 X-Propagation 文档，并用独立的小型工具测试实际验证。
`elaborate -enable_sva_isunknown` 下，`cover property (out && !$isunknown(out))`
仍产生 WNL038/WPM022，属性被禁用，状态为 `unprocessed`。没有把该选项当成
已经可用的四态 Cover 解决方案；正式后端没有采用它。测试不是模型实验 LTL。

## 检查与归档

常规回归：334 项，321 通过、13 项环境开关测试跳过；本轮没有把跳过计作通过。
新增保存响应检查、禁止二次请求、原始源码核验与有界诊断参数测试均通过。
上一轮的 13 项真实工具检查结果另见 `pending-offline-20260911.md`。

持久归档：`/var/storage/workspaces/rvprobe-saved-flow-fix-20260911-v1`。
目前已核验 CAN 2519、ETHMAC 3342、CAN 延长诊断 371、ETHMAC 延长诊断 411、
失败证据回归 81、SDRAM 2013 个材料文件。三个完整离线流程的 Stage-1 文件
和重新编译的 ModelUT 均与原始哈希一致。仅不复制可重新生成的编译缓存。

旧 `/tmp/rvprobe-pending-offline-20260911-v1` 已重新核验 6747 个材料文件与归档
一致，清理 285 个可重建缓存项，释放 2360746962 字节；源码、输入、波形、
覆盖率和日志不删除。正式完整配对仍未增加，不能把本轮诊断算作 16/16。
