# 补充实验：SV Constraint 与反馈消融

## 最新范围调整：取消 SV Constraint

用户已取消 Direct SV Constraint，不再执行其 16 设计实验。
已启动的 ALU Constraint 进程已单独终止，产物已归档，不能视作自然失败或纳入方法比较；
中断请求的未返回费用保持未知，已返回费用保留作取消成本审计。
下文 64 项方案仅保留为历史启动记录。

有效计划为 16 个设计 ×（RVProbe full / no_diagnostics / no_coverage）= 48 项。
原服务停止接纳新任务，但让两项在途 RVProbe 完成并归档。
接续服务 `rvprobe-feedback48-20260916-v1.service` 等待原服务退出，再复用已归档的
RVProbe 单元，继续未启动单元；不重发在途请求、不重复计算费用。
新正式结果目录为 `/var/storage/workspaces/clo91eaf/rvprobe-feedback48-20260916-v1`。
实验条件不变，仅修改调度和范围；保留原单元的框架哈希与来源。

## 范围与共同配置

16 个 HAVEN IP，各运行 Direct SV Constraint、RVProbe full、RVProbe no_diagnostics、
RVProbe no_coverage，共 64 个独立单元，最多 3 项并发。不运行新的 HAVEN 实验，不开展缓存专项实验。

所有单元使用 `fixed16-environment-20260912-v3/stage1-map.json` 指定的固定 Stage-1。
它是共同实验环境，不计入本次 Stage-2 模型成本。原有 7 个设计含人工诊断适配来源，
不能表述为全自动生成的环境。DUT、基线刺激、reset/static policy 不变。

| 参数 | 值 |
|---|---|
| 请求模型 | deepseek-v4-flash-vision-exp |
| temperature / reasoning_effort | 0.3 / max |
| 每请求输出上限（含推理）/ HTTP timeout | 393216 tokens / 3600 s |
| 交互策略 | staged；三个 RVProbe 组完全相同，不以缓存为实验变量 |
| 覆盖轮机会 | 固定 3 轮；不因覆盖达标、停滞或空候选提前结束 |
| 每轮 intent / 每 intent sequence | 至多 4 / 至多 4 |
| 源码尝试 / 每轮额外 runtime repair | 3（含首次）/ 1 |
| JG 单目标 / 再采样时间 | 120 s / 30 s |
| SV Constraint 编译 / randomize 时间 | 300 s / 120 s |
| 种子 | 20260906 |

Provider、基础设施失败和修复次数耗尽仍终止该单元，保存失败并继续其余单元。
固定 3 轮是机会预算，不保证失败单元完成 3 轮。单元外层安全超时为 13 小时，
RVProbe 内部 flow 外层超时为 12 小时；这不是模型的单请求预算。

## 对照与消融定义

- Direct SV Constraint：模型只输出带 label、intent、cycles、constraints 的 JSON。
  每个可驱动输入声明为长度 cycles 的 `rand bit` 数组，一次 randomize 联合求解全部拍，
  支持跨拍关系。固定可信 wrapper 采样四条，通过相同 raw IO 通路回放并收集覆盖率。
  输出及 DUT 内部状态不可引用，DUT 转移关系不进入随机约束求解器。
  因而它是输入约束表示/求解工作流的对照，不是仅删除类型检查的单因素消融。
- RVProbe full：现有 LTL API、检查器、求解、回放、诊断和覆盖率反馈全部保留。
- RVProbe no_diagnostics：仍执行全部检查，保留相同修复机会，但只向模型提供验证失败
  的泛化提示和自己的旧候选，不提供具体编译/类型/求解/回放错误。真实错误独立归档。
  它不是“删除类型系统”，也不能独立归因各类诊断的作用。
- RVProbe no_coverage：仍收集覆盖率用于评估，但 prompt、初始证据和工具均不向模型提供
  覆盖率值、缺口或报告。保留规格、IO、RTL 按需访问、已接受 LTL 历史和错误诊断。

比较反馈效果时使用本批 full 组，不能直接使用历史提前停止批次充当受控消融对照。
本次单一种子，尚不支持显著性结论。历史 HAVEN 数字仅作已冻结方法的参考。

## 验收、记录与存储

离线真实 VCS 测试已验证跨拍关系、array reduction、矛盾约束拒绝和 ALU 四条序列完整回放；
这些是人工诊断输入，不计入模型实验。信息边界及三轮机会由自动测试覆盖。

每单元记录命令、框架哈希、固定环境哈希、请求及工具调用、全部已报告 token、错误、
尝试/接受轮数、分阶段时间和墙钟时间。未知费用不记为零。
覆盖率采用最后有效累计原生仿真结果；没有接受任何新增刺激则用本次 baseline。
不将 baseline-only 或最后有效覆盖率标记为成功实验。结果保存为 JSON、CSV 和 Markdown。

执行器为 `experiments/supplement_campaign.py`，由 `batch_service.py` 启动持久一次性服务。
运行工作区在 `/dev/shm`；完成即校验归档至 `/var/storage/workspaces/clo91eaf`，
再把对应 scratch 目录替换为归档软链接。归档失败保留本地文件并停止接纳新任务。
框架源码在批次中改变也会停止接纳，避免把不同实现混成同一受控组。

## 本次启动记录

- 一次性服务：`rvprobe-supplement64-20260916-v1.service`。
- 工作区：`/dev/shm/rvprobe-supplement64-20260916-v1`。
- 归档与进度：`/var/storage/workspaces/clo91eaf/rvprobe-supplement64-20260916-v1`，
  `progress.json` 和 `results.{json,csv,md}` 随单元完成更新。
- Python 回归：559 项，HAVEN 环境下 21 项条件跳过，其余通过。
- VCS 离线通路：`rvprobe-sv-constraint-smoke-20260916-v1/summary.json`，通过。
- 消融离线通路：`rvprobe-feedback-smoke-20260916-v2/summary.json`，三模式均通过；
  每模式注入一次格式错误，再由同一个诊断 LTL 通过真实编译与 JG 生成 witness。
  v1 仅因 smoke 脚本误认状态名称（covered 与 generated）失败，原 JG 已生成 witness；
  v1 失败记录也保留，没有覆盖。
- 上述离线产物均已校验归档至 `/var/storage/workspaces/clo91eaf`，原内存盘路径为软链接。
  仅释放已校验的 scratch 副本和可重建编译缓存，日志、源码、witness 与覆盖数据可恢复访问。
