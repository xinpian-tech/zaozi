# 作者代替模型的流程验收（2026-09-10）

结论：GPIO 的完整双侧两轮闭环通过；UART、I2C 的单轮 RVProbe 流程通过。
CAN 和 Ethernet 仍有阻塞，因此不能声称五个设计都可以直接开启正式实验。

## 执行方式与隔离

入口为 `experiments/manual_flow.py`。模型名固定为 `manual-author-debug`，不能伪装成 DeepSeek。
请求原样写入 mailbox，作者读取实际 prompt / RTL / 测量反馈后，以请求哈希绑定的 JSON 回复。
RVProbe 先由作者回复 `read_skill({})`，框架执行真实的 skill 白名单调用，再提供完整任务。
本次 5 份 UT 都启用了 local RAG，每次检索 6 条框架材料，均首次编译通过。

只替换 completion 传输和请求 worker 的启动方式（诊断中原入口在受保护进程内执行）。
实际运行了：保存的 Stage-1 重编译 → 全部共同基线 → prompt/skill/RAG →
DSL/UT 解析 → sandbox 编译/lowering → JG → 每 intent 4 条采样 →
真实共享 BFM/VCS 回放 → URG 合并 → 第二轮反馈/停止规则。
没有放宽原输出掩码、屏蔽 X、接管响应 BFM、修改 RTL 或模型答案来绕过检查。

未测试 DeepSeek HTTP 服务、实际模型的 tool-call 遵循率、线上输出截断或 provider worker 的进程隔离。
Stage-1 的模型生成内容沿用保存产物，未重新模拟完整 Stage-1 生成过程。

本轮远程 LLM 请求和 DeepSeek tokens 均为 **0**。12 次本地 completion 交接不是 API 请求。
作者自身 token 数不可取得，记录为 unknown/null；耗时包括等待作者回复，不能用于速度/成本对比。
所有产物在 `out/experiments/manual-flow-*`，不属于正式配对成绩，也不进入 RAG/skill。

## 测量结果

每个设计均重新执行保存的全部共同基线，不复用历史覆盖数字。

| 设计 | 本次范围 | 实际结果 | 接受的新增 sequence |
|---|---|---|---|
| GPIO | 9 条基线，HAVEN/RVProbe 各两轮 | 两侧完成；RVProbe 第二轮包含原生 `past` 和跨拍关系 | HAVEN 2；RVProbe 8 |
| UART | 11 条基线，RVProbe 一轮 | 完成 | 4 |
| CAN | 10 条基线，RVProbe 一轮 | 编译、JG、4 条采样成功；首条回放失败，整批拒绝 | 0 |
| I2C | 13 条基线，RVProbe 一轮 | 空闲总线意图完成 | 4 |
| Ethernet | 旧共享基线准入检查 | 生成前拒绝错误 memory_write fallback | 0 |

覆盖率顺序为 line / condition / toggle / branch（%）：

| 设计/侧 | 本轮共同基线 | 最后接受的覆盖 |
|---|---|---|
| GPIO HAVEN | 100 / 63.69 / 70.09 / 78.70 | 100 / 76.54 / 70.09 / 100 |
| GPIO RVProbe | 同上 | 100 / 75.42 / 88.20 / 82.41 |
| UART RVProbe | 96.43 / 84.62 / 88.05 / 94.64 | 96.43 / 85.90 / 88.29 / 94.64 |
| CAN RVProbe | 100 / 91.67 / 90.35 / 100 | 保持基线，未接受候选 |
| I2C RVProbe | 81.82 / 94.44 / 93.93 / 79.31 | 81.82 / 97.22 / 95.33 / 79.31 |

不能据此判断哪个模型/方法更好：作者选择了不同后端可表达的诊断候选，未执行预注册的受控模型对照。
GPIO / UART / CAN / I2C 的诊断总墙钟时间分别为 579.04 / 226.65 / 198.43 / 324.71 秒，包含人工等待和并发资源竞争。

## 本次发现并修复

1. HAVEN `node_compile_check` 在编译前无条件初始化模型客户端，导致离线也需要 API key。
   现改成延迟初始化，仅确有模型修复时创建；离线 `model_repairs=False` 保存编译错误并退出。
   测试覆盖“编译成功”和“编译失败且禁止模型修复”，两者均不构造客户端。
2. Stage-1 恢复入口未统一设置 EDA wrapper，默认寻找宿主 `tcsh` 而失败。
   新增 `--eda-shell`，默认使用 flake 配套的 `experiments/eda-shell`。
   修复后 GPIO/UART/CAN/I2C 的共享组件均完成无凭据重编译。
3. 本地模拟响应没有 provider usage，暴露了“全部缺失 token 被汇总成 0”的记账歧义。
   汇总已改为：无请求为 0；有请求但全部缺 usage 为 null；部分已知仅记录已报告小计并标记不完整。
   此修正在本轮诊断完成后加入；旧 summary 中的零小计不改写，权威诊断记录中的 `author_token_usage` 为 null。

前两次 GPIO setup 失败记录也保留在 `manual-flow-gpio-setup-20260910-v1/v2`；成功共享 setup 为 v3。
通用 HAVEN 补丁已同步，未提交或推送。

## 仍需解决，不能靠换模型解决

- CAN：`manual-flow-can-20260910-v1` 的第 56 行是复位后的第一个 witness 点，
  `wb_dat_o` 实测 `xx`，JG 期望 `00`、掩码 `ff`。RTL 的该数据输出尚未被有效读事务赋值。
  当前全输出一致性检查会拒绝这个仅约束输入的目标。
  改善提示词不足以解决这类首拍不可重放问题；需要明确、验证初始状态/有效观测契约，
  或在真实 IO 上验证原始 LTL 目标后再考虑调整检查范围，不能直接屏蔽未知位。
- Ethernet：旧基线包含把外部内存写降级为寄存器写的产物；新准入规则在调用作者/模型前拒绝。
  原生内存 BFM 虽已通过通用测试，但完整共享环境与基线尚未接入并验证。
- I2C：本次只验证空闲状态下的总线条件，不证明此前的主动 SDA 交互不一致已解决。
- UART：本次没有读取未初始化 FIFO，不能撤销此前 FIFO 输出为 X 的限制。

## 复现入口

本页记录的是第一轮、进程内替代 completion 的历史诊断。
后续入口已升级为真实子进程 mailbox，见 [第二轮流程验收](manual-flow-followup-20260910.md)。
下方命令仍可使用，但新请求的位置为 `mailbox/worker-NNNN/MMMM/request.json`；
同目录写入 `response.json`，不得继续使用旧的单层 mailbox 路径。

```sh
nix develop -c experiments/haven-python HAVEN_ROOT experiments/manual_flow.py \
  --stage1-run SAVED_STAGE1 --haven-root HAVEN_ROOT --out NEW_DIAGNOSTIC --rounds 2
```

在 `NEW_DIAGNOSTIC/mailbox/NNNN/request.json` 出现后，读取请求并写入同目录 `response.json`：

```json
{"request_sha256":"请求中的 sha256","message":{"role":"assistant","content":"该次协议要求的 JSON 文本"}}
```

skill bootstrap 应返回 assistant 的 `tool_calls`，不能跳过。仅用于调试；该模式无远程模型回退。
