# 设计 6–10 续跑记录

后续口径更新：用户已确认采用“不同激励生成方式、同一外部环境、实际响应一致性验收”。不再要求完整形式化所有 BFM，也不再保留旧的 native/pin-level-variant 开关。实现与后续实测见 [共享环境与 sequence 验收](shared-environment-conformance.md)；以下 18:05 UTC 的失败记录及当时限制仍保留作为历史审计。

2026-09-09，更新至 18:05 UTC（UTC+8 为 9 月 10 日 02:05）。本轮运行均已退出；设计 6–10 新增完整配对结果为 0。下列失败不代表有效比较结果。

## 比较边界

固定 `deepseek-v4-flash-vision-exp`、temperature 0.3、最多 3 个覆盖率闭环轮次、RVProbe 每 intent 最多 4 条去重 sequence。
两侧共享 RTL、组件、初始 sequence、覆盖目标及种子；新增 sequence 使用独立仿真进程合并覆盖数据库。

原生 BFM 自主行为尚未完整进入形式环境。未获得改用引脚级变体的明确选择前，不运行带 BFM 的 RVProbe 并将其算作原生 BFM 配对结果。
CAN 当前模型架构没有 BFM，因此可尝试现有多时钟配对通路；但其共享基线仍须先通过运行验收。

## 已确认记录

| 项目 | 结果 | Tokens | 耗时 |
| --- | --- | ---: | ---: |
| UART 第一次恢复组件 | 引脚所有权拒绝 | 22,513 | 128.46 秒 |
| UART 第二次恢复组件 | 19 个组件、11 条初始 sequence，编译通过 | 133,051 | 716.23 秒 |
| UART 第一批 HAVEN 闭环 | 第 1 轮有效，第 2 轮调用不存在的 BFM 方法，编译失败 | 110,377 | 单侧 510.60 秒 |
| CAN 原 Stage-1 准备 | 组件编译通过，但两次 DSL JSON 解析失败，得到 0 条 sequence，不能作为有效基线 | 276,585 | 原生记录约 1,381 秒 |
| CAN 同轮 DSL 恢复 | 从原模型响应恢复 10 条 sequence，一次编译通过 | 0 新 tokens | 12.68 秒 |
| CAN 首次配对尝试 | 基线有 10 次 poll timeout；两侧模型均未启动 | 0 新 tokens | 64.42 秒 |
| UART 第二批 HAVEN 闭环 | 前两轮通过，第 3 轮 `wait_frame_err_latch` 超时 | 165,144 | 单侧 782.33 秒 |
| Ethernet Stage-1 | 9 条初始 sequence，编译通过 | 375,076 | 1,983.66 秒 |
| Ethernet HAVEN 基线 | TX/RX 完成及 MII busy 共 6 次轮询超时，闭环未开始 | 0 新 tokens | 58.47 秒 |
| CAN 仅驱动器修复尝试 | 未接受修复；模型指出冻结的基线也有错误，停止该范围的重试 | 至少 190,167 | 见独立事件记录 |
| CAN 模型基线修复 | 同一原始规格和失败反馈交给模型，10 条 sequence 一次编译通过 | 79,703 | 284.28 秒 |
| CAN 修正基线后重跑 | 剩余 sequence 3/4 的 `wait_rbs` 超时；两侧模型未启动 | 0 新 tokens | 45.42 秒 |
| I2C 恢复 Stage-1 | 17 个组件、11 条 sequence，编译通过 | 52,199 | 233.13 秒 |
| I2C HAVEN 基线 | 地址/数据 ACK、读完成轮询超时，闭环未开始 | 0 新 tokens | 8.48 秒 |
| GPIO 第一次 Stage-1 | BFM 以 32 位规划，但 CIRCT 实际 IO 为 31 位，拒绝 | 46,140 | 238.02 秒 |
| GPIO IO 修正后 Stage-1 | 13 个组件、9 条 sequence，编译通过 | 80,586 | 457.56 秒 |
| GPIO HAVEN 基线 | sequence 4 的 `wait_ints_set`、sequence 7 的 `wait_input_sync` 超时 | 0 新 tokens | 8.03 秒 |

CAN 驱动修复的第 3 次请求在中断前已发出，但未取得 usage；190,167 仅为前两次已报告数量，不是该尝试的完整 token 总额。未将缺失 usage 记成 0。

GPIO 失败序列试图通过事务字段 `ext_pad_i` 改变输入，但该输入由 GPIO BFM 持有、Wishbone 驱动器不消费该字段；仅编译通过无法检查这种无效激励。CAN 原生成驱动器则对每次广播事务都发送帧，事务分派/协议语义仍需检查。均未通过删除轮询或放宽错误判定来绕过。

UART 的共享基线顶层覆盖率：line 96.43%、cond 85.90%、toggle 92.68%、branch 94.64%。
第一批 HAVEN 第 1 轮新增 2 条 sequence 后为 97.62%、89.74%、94.39%、96.43%；综合分数增加约 2.13 个百分点。
这些是顶层统计，不是完整层次的覆盖率，也不是最终成功配对结果。

之前失败批次中的 UART 89,771 tokens、I2C 60,278 tokens 继续保留，不因复用规划检查点而归零。
所有共享准备与重试成本必须与单侧生成成本分开列出；不同失败批次不能当作独立成功样本。

## 本轮修复

- Phase 0–2B 规划可复制到新目录恢复 Phase 3；不复制历史覆盖答案、UT 或已生成 stimulus 充当新模型输出。
- 驱动器越权写入反馈给模型，最多两次修复；不在本地删除驱动行为来绕过检查。
- JG/CIRCT 确认的副时钟/复位不再成为随机事务字段，真实 IO 保留。
- 修复续跑跳过 BFM 物化的问题：在 Phase 3 完成 BFM 参数归一化后重新渲染。
  UART 正在运行的旧续跑通过 `restore_stage1_bfms.py` 恢复了缺失文件；该次操作有独立源哈希记录。
  缺文件导致的无效模型修复费用没有丢弃。
- JSON 解码仅允许将字符串外裸写的十六进制整数、已知值且不溢出的无符号定宽 SV 整数转换为等值十进制，再严格解析/验证 schema；不改字符串、表达式或测试含义，不接受有符号/X/Z/溢出字面量。
  CAN 恢复使用的是同一轮保留日志中的完整模型响应，不是人工编写的新 sequence。
- 空 DSL/空初始 sequence 不再算 Stage-1 成功。
- 从 BFM 实际任务声明导出 API、检查调用方法及参数名；调用采用命名参数，避免 JSON 键顺序改变实参含义。
- 后续 Stage-1 worker 和配对运行持有独立 HAVEN 源码快照，不复制 `.env` 或凭据。
- CAN 运行修复仅允许一个未受模板保护的生成驱动器变化；原测试、检查、RTL、BFM、其他组件冻结。
  该范围经实际诊断不足以修好原错误基线，已停止；没有安装一个假定通过的驱动修复。后续基线修复由模型依据原始规格及保存的失败诊断生成，写入全新目录，不修改原失败记录。
- 在 Phase 2B 的 BFM 位宽检查之前导入 CIRCT 实际端口信息；可复用已保存的 protocol flows 完成规划，不重新调用模型规划。
- 按实际 IO 类型补全总线读响应字段；实际 BFM API 同时进入两侧公共上下文。

## 修复验证与未决边界

- HAVEN Python 测试：171 passed、1 skipped。
- RVProbe 实验 Python 测试：运行 200 项，182 passed、18 skipped；不含本轮未执行的 Scala/EDA 集成项。
- 26 文件可移植补丁已从 SHA-256 验证过的原文件重新生成；安装哈希检查及 `git apply --reverse --check` 通过。
- 尚未选择放宽 BFM 比较口径，UART/Ethernet/I2C/GPIO 的 RVProbe 未启动。原生 BFM 的自主行为需要进入形式环境才能称为严格原生配对；引脚级覆盖变体须明确单独标注，不能混入原生结果。
- 即便选择引脚级变体，共享基线的实际仿真错误仍须先解决；切换口径本身不会修好基线。

## 输出目录

路径均相对仓库根目录：

- `out/experiments/design6-10-native-resume-20260909`：主 Stage-1 队列（原 UART、CAN、Ethernet、I2C、GPIO）。
- `out/experiments/design6-uart-native-retry-20260909`：UART 编译通过的共享组件。
- `out/experiments/design6-uart-haven-native-20260909`：UART 第一批失败的 HAVEN 闭环，完整成本/响应/编译日志。
- `out/experiments/design6-uart-haven-api-20260909`：修正 BFM API 后的新 HAVEN 批次。
- `out/experiments/design7-can-dsl-recovery-20260909`：CAN DSL 恢复、组件及原响应来源哈希。
- `out/experiments/design7-can-paired-native-20260909`：CAN 首次基线运行失败记录。
- `out/experiments/design7-can-shared-driver-repair-20260909`：CAN 冻结基线的模型驱动修复。
- `out/experiments/design7-can-baseline-repair-20260909`：CAN 模型修正后的新基线。
- `out/experiments/design7-can-paired-baseline-v2-20260909`：CAN 新基线的两处接收超时。
- `out/experiments/design8-ethmac-haven-native-20260909`：Ethernet 基线失败。
- `out/experiments/design9-i2c-native-resume-20260909`、`out/experiments/design9-i2c-haven-native-20260909`：I2C 共享准备和基线失败。
- `out/experiments/design10-gpio-native-resume-20260909`、`out/experiments/design10-gpio-io-retry-20260909`：GPIO 两次共享准备。
- `out/experiments/design10-gpio-haven-native-20260909`：GPIO 基线失败。

查看各目录的 `progress.json`、`summary.json`、`stage1-costs.json` 获取实际最新状态；本页中的数字只列已结束尝试。
