# 第二轮人工 LTL 流程验收（2026-09-10）

目的：由作者代替 DeepSeek，使用同一份当前 prompt、skill、框架 RAG、IO 绑定和真实 EDA 工具验证流程。
这是框架诊断，不是模型质量或方法优劣比较；所有诊断均标记 `manual-author-debug`，不进入正式配对统计。
本轮没有修改 RTL、BFM 行为、输出检查掩码、采样目标或验收标准，也没有向 RAG/skill 写入设计答案。

本轮诊断已全部结束：UART/GPIO 各两轮通过，合计接受 24 条新增 sequence；
I2C/CAN 在严格回放检查处失败，Ethernet 在基线准入处失败。不能据此宣布五个设计全部就绪。
机器可读汇总：`out/experiments/manual-followup-20260910-summary.json`。

## 与正式任务的隔离

正式队列 `deepseek-paired-launch-20260910-v1` 已停止：GPIO 已完成结果保留，
UART 标记 interrupted 并保留已完成 HAVEN 侧和全部请求费用事件，I2C 在启动前取消。
这里的“零 DeepSeek 请求”只指新的人工诊断，不抹去正式任务此前已发生的费用。

所有本轮产物使用 `manual-flow-*-20260910-v2` 独立目录。
Stage-1 继续使用保存的原模型基线和此前离线刷新的通用共享组件；未重新生成或模拟 Stage-1 模型规划。
作者读取实际请求，返回一份完整 UT；每份回复绑定请求 SHA-256。UT 源码仅存放在诊断输出目录。
人工 completion 的 token usage 不可取得，记为 null；远程 DeepSeek 请求为 0。
耗时包含人工写作和等待，不可与正式模型耗时直接比较。

## 已完成的 UART 两轮

`out/experiments/manual-flow-uart-20260910-v2`：全部 11 条共同基线重新执行；两轮 4 个 intent、16 条新增 sequence 全部通过。

- 第一轮：TX FIFO 写入后观察串口起始位；长低电平停止位后读取 LSR，观察 framing-error 标志。
- 第二轮：真实串口接收完成后读取 RX FIFO；无有效 strobe 时使用原生 `past` 检查读数据保持。
- 第一轮测量结果作为第二轮实际反馈；每轮一份 UT、每 intent 4 条独立采样。
- Scala 编译、lowering、单 DUT/忠实 IO 检查、JG Cover、采样、完整前缀回放和 URG 合并均实际运行。
- 本轮读取 FIFO 前先接收数据，不证明空 FIFO 的未初始化输出可以被当前契约接受。

| 覆盖率 | 共同基线 | 第二轮最终 |
|---|---:|---:|
| line | 96.43% | 97.62% |
| condition | 84.62% | 92.31% |
| toggle | 88.05% | 90.24% |
| branch | 94.64% | 96.43% |

UART 此次使用升级前的进程内人工入口；它的完整 EDA 链真实执行，但不能单独作为新 worker 子进程入口的验收证据。

## 新入口：只替换 completion，保留 worker 子进程

`experiments/manual_flow.py` 升级到 `manual-mailbox-subprocess-v2`：

- 生成请求和退出状态经过真实子进程；不再在父进程内直接调用 worker main。
- 保留正常命令参数、日志、退出码、进程组超时处理；只允许两个已有生成入口。
- 子进程去掉 provider key/base 环境变量，禁止 `--env-file`，固定使用快照内的 HAVEN。
- completion 只接受 `manual-author-debug`；未回答、请求哈希不匹配或未知 worker 均失败，不能回退远程模型。
- 每个 worker 独立 mailbox，并记录真实 worker PID 与 supervisor PID。
- 默认 JG 120s、每 intent 4 条、运行时修复预算 1、种子 20260906，与正式入口对应设置一致。
  传输重试固定 1 次，人工等待不模拟真实 HTTP 服务重试。

GPIO 新入口诊断特意加入一次 `Bool.asUInt` 类型错误，验证 compiler → 错误摘要 → skill → 修复请求的链路。
错误反馈包含源文件、行列、硬件类型与正确 API 建议；修复仅移除注入的错误表达式，保持原 Gen 不变。
故障注入记录在该运行的 `injected-fault.json`；不能把这次故意失败统计为模型生成质量。

`manual-flow-gpio-20260910-v2` 已完成两轮：第一轮修复后 4 条 sequence 通过，
第二轮原生 `past(data, 2)` 的输出寄存器写入/读回目标再通过 4 条。
两个生成 worker 的 PID 分别为 2770651、2786306，均不同于 supervisor 2750943；
第二轮读取了第一轮实际覆盖反馈。最终 line/condition/toggle/branch 为
100 / 71.51 / 88.76 / 78.70%，本轮总耗时 550.77 秒（含作者等待）。
这补测了旧进程内入口没有覆盖的跨进程请求、反馈、退出码和成本汇总路径。

完整 Python 回归 235 项：212 通过、23 因环境条件跳过；在 HAVEN 依赖环境内，
manual-flow 的 6 项测试全部通过。另用真实 VCS 验证了 I2C BFM 协议回归、
真实响应与 witness 冲突时拒绝、受检输出为 X 时拒绝。`git diff --check` 通过。

## 已重新复现的阻塞

| 设计 | 诊断到达阶段 | 失败证据 |
|---|---|---|
| I2C | LTL 编译、JG、4 条采样成功，首条回放失败 | `ENVIRONMENT_WITNESS`，row 20，SDA 实测 1、形式期望 0 |
| CAN | 带 ACK 的寄存器读时序目标编译、JG、采样成功，首条回放失败 | `WITNESS_X`，row 56，`wb_dat_o=xx`，期望 00、mask ff |
| Ethernet | 共享基线准入检查 | 拒绝旧 `memory_write` fallback，在任何 completion 之前退出 |

I2C 目标确实包含预分频/使能/发送寄存器配置和 START 输出，并在目标序列内表达了 pad 关系。
失败发生在目标开始前：22 拍 formal trace 的第一拍允许外部 SDA 拉低，但真实从设备仍释放总线。
后续拍才进入作者要求的配置序列。不能通过增加目标内条件就宣称任意前缀已符合真实外设模型。
通用 I2C BFM 独立协议回归通过；这不证明形式环境对它的过近似已经精确。

CAN 新目标不是空闲占位，而是“模式寄存器读并观察 ACK”，仍在首次读响应赋值前遇到未初始化输出。
不能靠给 LTL 换写法、把 X 补零或删除检查解决该契约问题。

因此不能宣称所有五个设计已全流程通过。I2C 仍需形式端的外设初始状态/响应一致性支持；
CAN 仍需明确且可验证的初始状态/有效观测契约；Ethernet 仍需完整接入原生外部内存环境并重新验证共享基线。
这些都不应通过人工挑选通过的样本、修改模型回答或放宽验收来伪装完成。

## 复现

```sh
nix develop -c experiments/haven-python HAVEN_ROOT experiments/manual_flow.py \
  --stage1-run SAVED_STAGE1 --haven-root HAVEN_ROOT \
  --out NEW_DIAGNOSTIC --arm rvprobe --rounds 2
```

读取 `NEW_DIAGNOSTIC/mailbox/worker-NNNN/MMMM/request.json`，在同目录用原请求 SHA-256 写入 `response.json`：

```json
{"request_sha256":"原请求的 sha256","message":{"role":"assistant","content":"任务要求的完整 JSON 字符串"}}
```

skill bootstrap 必须先返回 `read_skill({})` tool call；任务将在工具执行后到达。
不要附带 API key，也不要把诊断中的 UT/输入答案加入公共 skill 或 RAG。
