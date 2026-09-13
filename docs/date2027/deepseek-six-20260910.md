# DeepSeek：设计 11–16，2026-09-10

状态：2026-09-10 12:59:06 UTC 已结束，6 个设计全部尝试、0 个完整成功。本次耗时 1251.427 秒，新增 341,309 tokens（包含失败调用，不含历史尝试）。此前 `simple_spi` 第一轮因 SemanticDB 输出路径错误失败。只运行 RVProbe 闭环，不是新一轮 HAVEN + RVProbe 完整配对。

随后进行了零模型调用的[框架修复与离线检查](deepseek-six-framework-fixes-20260910.md)，不改写本次失败结果。

- 模型：`deepseek-v4-flash-vision-exp`。
- 设计：`simple_spi`、`spi`、`ue_gpio`、`ue_spi`、`ue_uart`、`sdram`。
- 每设计最多 3 轮，每 intent 最多 4 条 sequence；保留既有闭环停止条件。
- 共享 Stage 1、基线 sequence 和 RVProbe UT 均由远端模型生成。
- 不复用人工诊断基线，不注入人工 SDRAM 意图、操作数或历史答案。
- 框架知识仍通过原有 skill / RAG 通道提供。

## 进度与证据

当前工作目录（本机 tmpfs，重启会丢失）：

`/dev/shm/rvprobe-deepseek-continue-VZ16jg/run`

当前进度：上述目录的 `progress.json`。每个完成设计的源文件、日志、覆盖数据库、witness 和成本记录归档至：

`/var/storage/workspaces/rvprobe-deepseek-six-20260910-kezx02/semanticdb-continued-archive`

归档不包含可重新构建的 `csrc`、`simv`、`simv.daidir`。归档记录保留原始路径，复现时应恢复工作目录布局；不要重写带指纹的清单。

## 前置失败与成本

完整尝试记录位于持久化父目录：

`/var/storage/workspaces/rvprobe-deepseek-six-20260910-kezx02`

其中 `run`、`retry-spi` 保存初次失败，`recovered-run` 保存续跑。挂载存储上出现目录创建冲突与 VCS `VFS_SDB_ERROR`；没有把这些尝试删除或记作零成本。

`simple_spi` 在 `recovered-run` 完成 Stage 1，后续直接复用该模型生成环境。其他已保存的模型 Phase 0–2A 通过 `checkpoints.json` 续跑。Stage 2B 模型结果现在在环境检查之前保存，避免编译失败丢失已付费规划。

成本分析须合并所有尝试的实际 provider token、调用数和耗时，并把共享 Stage 1 与 RVProbe 闭环分别报告；不能仅统计最后一次成功尝试。

## 本次入口修补

- CIRCT 导入到的双向端口自动接入已验收的 IO-only wrapper；只支持明确原生 BFM 所有权，不重写 DUT。
- 导出文件名使用适配后的顶层名称；协议端口校验包含 inout。
- 新批处理入口支持模型规划续跑、已有合格 Stage 1，以及逐设计持久化归档。
- 新目录采用独占 ownership 文件，不覆盖已有运行结果。
- VCS 数据库存储错误直接中止，不再转成付费的源代码修复。

验证：262 项 Python 回归通过（16 项跳过）；另加 2 项 VCS 基础设施错误分类测试通过。同一份模型生成 testbench 在 tmpfs 上一次完成带覆盖率的 VCS 编译。

## SemanticDB 输出路径修复（离线验收）

沙箱编译现在移除继承自仓库的 `-sourceroot` / `-semanticdb-target` 路径值，显式指定本轮源码目录和 `sandbox-*/semanticdb` 可写输出目录。保留 `-Xsemanticdb`、zaozi 插件以及源码只读挂载；不关闭 LSP 支持。

使用第一轮已经生成的 3 份 DeepSeek UT 原样运行 `ut_harness.py --compile-only`，全部通过。9 个非空 SemanticDB 文件生成于产物目录，3 份 ModelUT 的原始校验和保持一致。实际参数保存在各次运行的 `compile-options.json`。

验收记录：`out/experiments/semanticdb-path-check-20260910-44Yl8N/`。本次没有调用模型、没有执行 JG 求解或覆盖率实验，也没有自动重启付费批次。

## 用户授权续跑

随后用户要求继续实验，已以独立会话、忽略挂断信号的方式启动批处理；启动 PID 为 3143857，启动记录为持久化父目录的 `semanticdb-continue-launch.json`，总控日志为 `semanticdb-continue-supervisor.log`。

`simple_spi` 第一轮复用此前最后一次完整的 DeepSeek 响应（attempt-3），校验模型来源、原始源码、RTL/IO/规格及共享环境一致性，不挑选最优答案、不手改 UT。后续轮次仍允许正常模型调用。此前生成阶段的 268,180 tokens 通过 `continued_generation` 保留，续跑报告另列 `cumulative_usage_reported`，不把复用当成零成本的新实验。

总控在归档前先写设计终态，归档复制异常单独记录，不覆盖仿真实验结果。续跑补丁验证：270 项测试中通过 254 项、跳过 16 项；真实历史响应的续跑来源校验通过。
