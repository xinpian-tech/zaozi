# JG liveness Cover 能力错误反馈与 UE_SPI 重跑

## 修复范围

旧 UE_SPI 的 `spi_loopback_transfer_interrupt` 使用 unbounded `eventually`，
lower 成 `s_eventually`，被当前 JG Cover 后端以 EOBS012 拒绝。原框架统一将
solve error 判为不可由模型修复的基础设施错误，导致尚有 attempt 预算也提前退出。

现在只有准确归因到当前生成 SV 路径的 EOBS012 `Liveness cover`（以及对应
ENL008/脚本退出级联错误）会被分类为 `unsupported_liveness_cover`。混合错误、
RTL 路径错误、许可证错误、编译超时与未知错误仍不开放模型修复。

harness 将标签和原始错误反馈为 `model_goal_unsupported`。模型在原 attempt
预算内修复，费用照常记录。修复提示包含 DUT spec；不得删除目标、增加 Assume、
任意设定上界或弱化输出条件。框架检查标签集合保持不变，并拒绝 STOP。
skill 仅新增通用 API/后端能力说明，不含 UE_SPI 答案。

标签保护不是语义等价证明；有限改写须有规格/配置依据，后端接受也不等于
已通过原 intent 的语义审查或仿真回放。修复审计以 `backend-accepted` 区分这些状态。
框架没有自动改写原 LTL，没有改变 DUT、固定 Stage-1 或 HAVEN。

## 验证

- Python 两批测试共 146 项：130 通过、16 个原有条件性跳过。
- Scala `JasperGoldTest`：6/6 通过。
- `git diff --check` 通过。
- 真实离线旧样本：不调用 LLM、不修改原 source，JG 重新生成 3 个目标，
  另 1 个复现 EOBS012。harness exit 2，`model_repair_allowed=true`，
  正确反馈唯一失败标签。诊断求解时限 30 秒，不计正式覆盖实验。
- 离线记录已校验归档至
  `/var/storage/workspaces/clo91eaf/rvprobe-liveness-offline-20260916-v1`，
  内存盘原目录已替换为该归档软链接。

## 新实验

- 单设计 UE_SPI、单并发，仅 RVProbe；从固定 Stage-1 开始新跑。
- `deepseek-v4-flash-vision-exp`，请求 `reasoning_effort=max`、
  `max_tokens=393216`、单请求 timeout 3600 秒。实际服务行为仍以响应记录为准。
- 最多 3 轮，原有 repair attempt 预算、每 intent 最多 4 条 sequence。
- 服务：`rvprobe-ue-spi-liveness-repair-max-20260916-v1.service`。
- 工作：`/dev/shm/rvprobe-ue-spi-liveness-repair-max-20260916-v1`。
- 自动归档：`/var/storage/workspaces/clo91eaf/rvprobe-ue-spi-liveness-repair-max-20260916-v1`。
- 固定环境：`/var/storage/workspaces/clo91eaf/fixed16-environment-20260912-v3/stage1-map.json`。

此批独立保存，不覆盖旧四方法结果或上一次大预算重跑。相比上次 UE_SPI，
除框架/skill 修复外还显式切换了 max effort，模型输出也可能不同，不能把
覆盖率变化全部归因于错误反馈修复。终态、费用和覆盖率以归档 summary 为准。
