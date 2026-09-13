# 本批配对结果的边界

对应 2026-09-10 的新尝试；数字汇总见 [完整记录](design6-10-complete-pairs.md)。
这里只记录实际暴露的问题，不把设计专用答案写入 skill。

后续不调用 LLM 的组件修复、实测范围与仍未解决的问题见 [离线修复记录](design6-10-offline-fixes.md)。以下旧实验数据保持不变。

## 已结束的失败与有效数据

- UART：HAVEN 正常停止；RVProbe 两个候选均被回放拒绝。具体输出为未初始化 FIFO 数据的 X，而形式 witness 中该输出是确定值。没有填零、屏蔽检查或把 rejected candidate 算入覆盖。
- I2C：SCL 被动开漏连接的形式约束已根据 ownership 自动补全。RVProbe 第一轮的修复候选通过（12 条 sequence）；第二轮及修复仍因真实 SDA 从机响应不匹配而失败。最后接受的覆盖率不是完整成功闭环的最终成绩。
- CAN：两侧均已终止。HAVEN 接受第一轮，第二轮接收轮询超时，修复请求又触及 65,536 completion tokens 上限；累计 519,072 tokens。RVProbe 原候选及修复候选均在 `wb_dat_o` 回放检查失败，0 个接受轮次，累计 327,668 tokens。编译、求解及四序列采样通过均未被当成回放成功。
- GPIO：`design10-gpio-complete-pair-20260910-v2` 两侧正常结束。HAVEN 7 条新增 sequence，RVProbe 152 条新增 sequence，均有 9 条共同基线。相同停止规则允许两侧停止在不同轮次：HAVEN 两轮后增益不足，RVProbe 三轮预算用尽。

## Ethernet 尚不具备完整配对结果

通用 MII 发帧/环回/事件时钟、Wishbone ACK/ERR/RTY、非零地址下标转换均有实际 VCS 测试。
修正 Wishbone `[11:2]` 物理地址转换后，`design8-ethmac-complete-pair-20260910-v2` 前六条基线通过，第七条 `loopback_seq` 的 `wait_rx_done` 超时。双侧 Stage-2 尚未启动，不能当成零 token 的有效配对。

另一个已确认的基线语义问题：保存的 sequence 将外部内存初始化降级为寄存器总线写入（`bus-write fallback, not backdoor`）；地址 `0x1000` 赋给 12-bit item 字段会截断并落到错误寄存器。
必须提供真实、共享的外部内存初始化接口，并由模型重新修复基线。不能只加长轮询、删除初始化、靠地址别名产生的偶然副作用或单侧组件修改来宣称完成。

两次大上下文联合修复均因模型输出上限失败。其中第二次错误地再次携带了完整旧反馈内容，造成额外输入费用；原请求、usage 和失败全部保留。框架现会拒绝把整个旧 prompt bundle 作为 `--feedback-log`，并可复用保存的编译后 sequence，避免重新展开已修过的 DSL。
随后单个存储器 driver 修复消耗 60,565 tokens，但没有解决完整基线超时，不能报告为成功修复。

## 比较范围

- 所有覆盖率是指定顶层的 line/cond/toggle/branch，不是整个 RTL 层次的完整覆盖；不证明功能正确。
- CAN 的该次原生 agent 采用 TX→RX 环回，生成 driver 没有消费独立外部 CAN 帧字段。结果须连同该组件限制披露，不能声称覆盖了完整原生 CAN BFM 能力。
- 多个设计并行运行，耗时包含资源竞争、工具启动和不同 sequence 数量，不能直接作为隔离条件下的速度对比。
- 共享 setup、失败尝试和最终选定尝试分开记录；不得挑选一次重跑的覆盖率再搭配另一次更低的 token 数。
- 本批处于框架修复迭代，不声称是固定单一源码版本、重复多随机种子的论文最终实验。运行及生成子步骤都保留各自源码哈希。
- 最终统计：9 次配对尝试、11 条共享 setup/修复记录，共 3,717,956 个已报告 tokens，其中共享 setup/修复 1,184,073。4 个设计执行了双侧，只有 GPIO 双侧正常结束；Ethernet 的两次配对入口均在基线阶段终止。当前无后台实验继续运行。
