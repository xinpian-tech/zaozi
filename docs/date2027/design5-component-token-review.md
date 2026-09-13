# 设计 5：组件契约修复与 token 复盘

本轮只修改组件生成/验证契约并进行离线与 VCS 回归，没有重新调用模型跑实验。
历史设计 5 的 HAVEN 失败和 RVProbe 成绩、token 均保持原样。

## 组件问题的根因

Stage-1 的 seq_item 提示词说握手由 driver 管理，但 RTL 输入补全将握手字段设为 rand。
字段检查还按名字猜角色，没认出 cfg_wvalid_i；自动修补又可能重新加入 rand。
模型随后在 item 中生成 WRITE 时 wvalid=1 的约束；Stage-2 试图生成 wvalid=0 的分拍场景，随机化失败。
与此同时，原生 AXI driver 不读取 item 的 wvalid，而是自己同时拉起 AWVALID/WVALID。
因此仅删除 c_handshake 或将其改成 soft 约束，并不能让这些字段控制实际时序。

## 修复

外部 HAVEN 修改通过 `experiments/patches/haven-axi-transaction-contract.patch` 保存，
修改前/后 SHA-256 在同名 JSON 中。补丁针对本地使用的 checkout，不声称对应上游最新版本。

- Phase 2B 在明确 bus mapping 后生成 `axi4lite-transaction-v1` 字段归属，Phase 3/恢复路径再次核对。
- 五个真正被 driver 消费的字段可随机化：写操作选择、写地址、写数据、字节使能、读地址。
  字段名和位宽来自映射及 IO，不含 benchmark 操作数或覆盖答案。
- driver 管理的 VALID/READY/PROT 字段不可随机化；移除未被消费的额外随机化别名。
  原有 pin 字段可保留为非随机成员，但不是 sequence 的驱动参数。
- item 不添加场景约束或随机化回调；具体 payload 选择属于 sequence。
- 校验器和后处理使用明确字段归属，不再从 valid/ready/enable 等名字猜测 rand。
  Direct-IO 的真实可控输入仍保持可随机化，没有全局禁止名为 valid 的输入。
- DSL 若试图约束 driver 管理的字段，明确返回不支持的参数错误，不静默删除该约束。
- 新配对入口拒绝没有该契约的 AXI Stage-1；不能重新包装旧的错误 item 冒充已修复产物。

当前范围是单 agent、固定事务模式的 AXI-Lite driver。未添加逐通道延迟、背压控制或任意逐拍能力。
多 agent、未映射额外输入、与模板不匹配的位宽明确拒绝，不能假定已经支持。
所以本次修复解决接口不一致，不等于消除了 HAVEN 与 RVProbe 的表达能力差异。

校验/应用：

```sh
python3 experiments/check_haven_component_patch.py /path/to/haven
# 仅 status=base 时应用；installed 表示已应用；different/mixed 不要强行覆盖。
git -C /path/to/haven apply --check /absolute/path/to/zaozi/experiments/patches/haven-axi-transaction-contract.patch
git -C /path/to/haven apply /absolute/path/to/zaozi/experiments/patches/haven-axi-transaction-contract.patch
python3 experiments/check_haven_component_patch.py /path/to/haven
```

下一次配对实验需在新目录重新生成 Stage-1，双方使用修复后的同一组件。
不要改写已有 bundle、模型回答或覆盖结果。本轮没有启动该付费重跑。

## RVProbe token 为什么大

来源：`out/experiments/design5-ue-timer-paired-20260909/paired/summary.json` 和逐请求 events.jsonl。
新增只读分析命令：

```sh
python3 experiments/analyze_token_cost.py out/experiments/design5-ue-timer-paired-20260909/paired \
  --out out/diagnostics/design5-token-analysis-new.json
```

| RVProbe 成本组成 | 服务端报告 token | 占总数 |
| --- | ---: | ---: |
| 输入 | 191,055 | 51.53% |
| 推理（completion 的子集） | 175,833 | 47.42% |
| 非推理 completion（含工具调用） | 3,877 | 1.05% |
| 合计 | 370,765 | 100% |

两份有效 UT 正文分别只有 3530、4525 个字符，均在首次实际编译中通过。
第一份生成用了 60,936 推理 token；第二轮首次生成全部 65,536 completion token 都用于推理，
返回空正文，整个尝试（含读取 skill）消耗 113,414 token，占总数 30.59%。
有效第二份 UT 消耗 97,075 token；第三轮仅返回停止决定仍花了 46,597 token，
其中绝大多数是再次发送任务上下文。失败、停止和读取 skill 的成本没有排除。

首轮任务 prompt 为 148,444 字符（以下是字符数，不是供应商 token 估算）：

- 共享 Stage-1 上下文：66,026 字符，占 44.48%；其中初始 UVM sequences 为 44,572 字符。
- 整个覆盖反馈区：89,271 字符，含上述共享上下文，不可重复相加。
- RAG 文档区：11,441 字符，占 7.71%；另有已加载的 skill 内容。
- RTL、原始 spec、structured_spec、protocol_flows、blueprint 的信息存在交叠。

### 反思与后续优先级

1. 不能把“公平”简单实现成给两边堆入相同的全部 UVM 源码。需要一致的设计证据、基线与预算，
   同时明确各后端真正消费的接口；RVProbe 不应为不参与其逐拍求解的 item 约束额外付出理解成本。
   后续应先定义可审计的共同最小证据，再删除重复材料，而非只对一边裁掉困难信息。
2. prompt 的目标是“关闭所有可达覆盖残余”，还要求完整 UT、接线、全部目标和待证义务，
   远比“用 LTL 表达一个有限意图”宽。模型推理成本很高与任务范围有关；不能仅靠缩短 Scala 语法解决。
   这是根据请求形态的推断，需要受控实验验证，不能声称已证明因果关系。
3. 空正文不是编译错误；需要单独记录供应商 finish_reason / 推理耗尽状态，避免把长时间空生成
   与语法修复混为一谈。当前账本保留了 usage 和正文长度，但没有完整记录 finish_reason。
4. RAG 确实使用了，但调用 skill 不等于整个上下文足够精简。应检查 skill/RAG/绑定示例之间的重复，
   只保留通用 API 示例，不能填入 DUT 答案。此次没有改写 skill 或 RAG。
5. 56 条 sequence 是求解器扩展得到的，不是 56 次 LLM 请求；减少每 intent 的采样数不能直接消除上述模型 token。

这次 HAVEN 第一轮即失败，只消耗 80,667 token；RVProbe 完成两轮并在第三轮停止。
不能用总 token 比值推导相同覆盖目标下的成本胜负，也不能保证 RVProbe 理论上必然比 HAVEN 少。
先修正组件、缩小并统一任务契约，再以相同停止标准重复测量；离线字符数减少不等于已测得 token 节省。

## 验证

- HAVEN 全套本地测试：143 通过，1 项 EDA 测试默认跳过。
- 显式启用 VCS 的组件测试：11/11 通过，包括实际 UVM 随机化、driver payload 驱动及读数据回传。
- 使用通用 synthetic 接口，包含无语义编号的 pin 名，验证字段归属不依赖名字猜测。
- 新增用例验证端口再次补全不改变角色、别名/握手约束拒绝、Direct-IO 不回归。
- 补丁在修改前快照上 `git apply --check` 通过；外部 checkout 的已安装内容按 SHA-256 核对。
- 未提交、推送或重新运行付费实验。历史成绩未重新标记为修复后结果。
