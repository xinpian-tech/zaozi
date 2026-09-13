# 设计 6–10 后续离线修复（2026-09-10）

本轮不调用 LLM，不读取模型凭据文件，不生成新的验证意图，不手改保存的模型答案。
只修改通用共享组件、诊断和准入规则。新 LLM 请求 / tokens 均为 0；旧实验记录未改写。

## 已修复

- DSL `memory_write` 只能调用已安装的外部内存 BFM。没有 BFM 时立即报错，禁止降级为寄存器写、禁止跳过操作。提示词同步说明两个地址空间的区别，不添加设计专用答案。
- 配对入口拒绝含旧 `bus-write fallback, not backdoor` / `Skipped memory_write` 标记的已编译基线，发生在模型生成和仿真之前。保留历史文件供审计，不自动重写旧 sequence。
- Wishbone master 真实传递 item 的 `SEL`，不再总是全字节使能。非零地址下标转换保留；缺省/null 地址范围元数据统一处理，非法元数据报明确错误。
- 原生 Wishbone memory BFM 统一总线/后门地址单位：默认字节地址，`addr_lsb=0` 显式选择字地址。检查边界、对齐和 X，禁止低位截断产生地址别名；支持字节写使能、取消等待中的请求，不因复位清空外部 RAM。后门地址参数扩至 64 位，避免在检查前截断。内部服务函数不作为 LLM 可调用 BFM task 暴露。
- 事件回放仍检查原有全部已知位与真实环境响应；增加 `WITNESS_X` 分类和失败行号、实际值、期望值、掩码。记录失败行后再退出，没有填零或放宽比较。
- 新策略 `explicit-transport-failure-no-model-repair-v1` 对明确的 X 输出、环境响应冲突、适配器错误停止自动模型修复。候选仍失败，覆盖率仍保持上次接受值；普通候选失败仍使用原有等额修复预算。该规则对两侧一致，不从旧错误日志猜测新分类。
- `--reuse-dsl` 不再要求或加载 `--env-file`；离线恢复和保存产物诊断会阻止 HAVEN `call`/`call_structured` 及 RVProbe `send_completion`。该保护不是断网沙箱，EDA 许可证连接仍可使用。

共享 HAVEN 修改已写入 `experiments/patches/haven-axi-transaction-contract.patch`，35 个文件的安装哈希一致，正向和反向 `git apply --check` 均通过。补丁更新脚本要求校验原始基线哈希，不修改 HAVEN checkout。

## 实际离线验证

产物位于 `out/experiments/`；均为通用测试，不是设计配对实验成绩。

| 产物 | 检查 | 结果 |
|---|---|---|
| `offline-event-response-20260910-v1` | 实际 UVM/VCS 事件回放 51 行；真实响应矛盾的负例 | 正例通过，负例拒绝 |
| `offline-event-unknown-20260910-v1` | 合成 RTL 输出刻意不初始化，要求严格拒绝已知位上的 X 并停止模型修复 | 负例正确拒绝 |
| `offline-wb-select-20260910-v1` | master 非零地址下标、读写 SEL、ACK/ERR/RTY、超时负例 | 通过，5.34 秒 |
| `offline-wb-memory0-20260910-v2` | 零等待 memory，读写一致性、字节使能、地址负例 | 通过，11.48 秒 |
| `offline-wb-memory2-20260910-v2` | 两拍等待 memory，取消请求、复位保留、地址负例；内部函数不暴露 | 通过，12.29 秒 |

框架测试 228 项：206 通过、22 因环境条件跳过；其中 4 项新增 Wishbone 测试另在 HAVEN Python 环境中全部通过。HAVEN DSL/driver/BFM/API 的 47 项测试全部通过。`git diff --check` 通过。

复现组件测试，不需要模型凭据：

```sh
nix develop -c experiments/haven-python HAVEN_ROOT experiments/smoke_protocol_components.py \
  --haven-root HAVEN_ROOT --eda-shell experiments/eda-shell \
  --protocol wishbone_slave --wait-states 2 --out NEW_OUTPUT_DIRECTORY
nix develop -c experiments/haven-python HAVEN_ROOT experiments/smoke_event_transport.py \
  --haven-root HAVEN_ROOT --uninitialized-output --out ANOTHER_NEW_OUTPUT_DIRECTORY
```

`HAVEN_ROOT` 和输出目录是需替换的路径；输出必须为新目录。

## 未宣称解决的部分

- UART/CAN：明确拒绝不可重放的输出不等于解决形式二态与仿真四态初始状态的一致性；没有屏蔽 X 或人为初始化 DUT。
- I2C：真实 SDA 从机与形式环境的过近似仍需对齐；没有覆盖真实 SDA 驱动，未将冲突 witness 算作通过。
- Ethernet：通用 memory BFM 已修，但旧共享 agent/基线尚未接入并通过该接口。旧基线的错误内存写不能自动推断成正确寄存器写；没有据此重报完整配对结果。
- CAN 原生 agent 仅环回、不消费独立帧字段的能力限制仍在。

因此本轮是可复现的离线修复，不是五个设计重新完成实验，也不产生新的 token 优劣结论。
