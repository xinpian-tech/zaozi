# 原始 LTL 回放与原生外设修复（2026-09-10）

本次由作者代替 DeepSeek 编写 LTL，验证真实框架路径；不是模型质量实验。
远程模型请求为 0，作者 token usage 为 null，耗时含人工等待。
旧实验和失败日志保留，不把人工结果合入正式 HAVEN/RVProbe 配对表。

已完成：本次阻塞的 I2C、CAN、Ethernet 均完成两轮完整闭环，合计接受 28 条新增 sequence。
完整路径为共享基线 → read_skill → 作者 UT → 编译/lowering → JG → 每 intent 4 条采样 →
真实 IO 上原始 LTL 验收 → URG 合并 → 下一轮实际反馈。没有用编译成功或部分目标成功代替最终验收。

## 验收口径的明确变化

旧事件回放要求形式波形的所有已知输出位、外部响应逐拍相等。
这会把未初始化存储的任意二态取值，以及目标开始前任意的外设响应，也当成验收目标。

新策略为 `native-io-ltl-replay-v1`：

- 从原始已校验 UT 的选中 Cover 机械提取观察器；校验 prepared job 和源码哈希，不重新编写目标。
- 移除观察器内的 DUT 实例，将原输出别名连接到共享 testbench 的真实 IO；仍只有一个 DUT。
- 使用 VCS 四态 SVA 验证原始时序表达式，只计 witness 窗口内的命中，保留原生 `$past`。
- BFM 的响应、开漏网络、PHY 的无效载荷/环回解析继续真实运行，不覆盖外设响应。
- 时序、复位、直接驱动的输入必须符合事件计划；全波形差异单独记录。
- 未命中原始目标、目标输出为未知且不能满足表达式，均拒绝；没有原始 LTL 元数据的旧回放继续严格逐位检查。

这证明该输入在共享环境里实现了原始验证意图，**不证明整条形式波形等价、所有内部状态等价或 DUT 功能正确**。
这是新的验收口径，不能把旧失败直接重新标成成功，也不能与旧口径结果直接拼表。

## 修复的公共组件

1. 原生 Wishbone 内存的 `memory_write` 改用 config_db 的虚接口，不从 package 非法跨层引用顶层。
2. 寄存器读、轮询及原有延迟读操作补齐字节使能；全字使能用 `'1`，不固定为 4 位。
3. 接口保留 RTL 的 `[high:low]` 范围，驱动只换算一次；事务地址位宽与物理端口位宽分开，避免 12 位字节地址被截成 10 位。
4. 原生 BFM 可选输入使用模板已声明的默认值；参数位宽解析支持 `DATA_WIDTH/8-1`，不执行任意表达式。
5. MII 事件适配器替换可选端口的默认连接，不重复连接；目标检查使用真实解析后的 RX IO。
6. 明确的输入/时钟/复位传输错误不再触发模型改写意图。
7. 离线共享环境可显式增加原生 BFM，完全被替代的 reactive driver 转为 passive observer，两边使用同一环境。

HAVEN 修改已导出到 `experiments/patches/haven-axi-transaction-contract.patch`；36 个文件的正向、反向补丁检查通过。

## 已完成的验收

| 验证 | 结果 |
|---|---|
| CAN 完整两轮 | 10 条共享基线；新增 8 条 sequence 全部通过 |
| I2C 完整两轮 | 13 条共享基线；新增 8 条 sequence 全部通过 |
| Ethernet 完整两轮 | 9 条共享基线；3 个 intent、新增 12 条 sequence 全部通过 |
| GPIO 原生 past 旧样本复验 | 4 条全部通过 |
| UART 两轮旧样本复验 | 16 条全部通过；此项不重新调用生成端 |
| 原生 SVA 语义 | 命中、未命中、X、窗口外、past 共 5 项符合预期 |
| CAN 真实故障注入 | 原目标不变、撤掉 strobe，4 条均被拒绝 |
| MII 原生适配回归 | 默认连接、共享时钟、原始刺激、环回检查通过 |

CAN 条件/翻转覆盖率从 91.67/90.35% 到 97.22/92.26%；line/branch 保持 100%。
I2C line/condition/toggle/branch 从 81.82/94.44/93.93/79.31% 到 83.64/100/95.33/82.76%。
两次完整运行分别耗时 395.75、394.83 秒，包含人工等待，不是模型响应速度。

Ethernet 最终运行 `manual-native-ethmac-20260910-v7` 耗时 420.17 秒：
第一轮 4 条，第二轮 8 条；全部 3 个目标均为 `generated`，12 个原生 Cover 检查全部命中。
条件/翻转覆盖率从 56.82/80.19% 到 68.94/81.02%，line/branch 保持 100%。
最终运行没有运行时拒绝或修复，按两轮预算正常结束；覆盖率没有宣称闭合。

回归：Python 246 项，219 通过、27 项环境条件跳过；HAVEN 依赖环境中的公共组件回归
39 项通过，另有 5 个 subtest 通过；`git diff --check` 通过。

Ethernet 使用单独的人工诊断基线：保留原来的 9 个用例及已有检查，
根据 RTL 修正寄存器映射、描述符访问类别/字段及软件初始化顺序。
描述符通过普通 Wishbone 写初始化，未改 RTL、未 force 内部存储。
原生外部 RAM 与 MII PHY 同时接入，两边共享；这份人工基线不属于正式模型成绩。
此前 v6 中，作者把寄存器读 ACK 固定在错误拍数，JG 判为不可行；保留该失败记录。
v7 保持写后读回意图和数据检查不变，改为有界等待 ACK，再实际求解和仿真通过，未删除目标。
人工基线来源标记会传入共享 bundle，provider-model 实验入口拒绝把它作为正式模型基线。

## 产物与复现

- CAN：`out/experiments/manual-native-can-20260910-v1/`
- I2C：`out/experiments/manual-native-i2c-20260910-v1/`
- Ethernet：`out/experiments/manual-native-ethmac-20260910-v7/`
- Ethernet 新共享环境：`out/experiments/manual-ethmac-setup-v7/stage1/20260910_093120_ethmac/`
- SVA 反向检查：`out/experiments/native-ltl-semantics-v1/`、`native-ltl-can-negative-v1/`
- 语法/工具响应、UT、JG trace、每条仿真日志、覆盖报告和费用事件均在对应运行目录。
- 机器可读汇总：`out/experiments/native-ltl-validation-summary.json`。

```sh
nix develop -c experiments/haven-python HAVEN_ROOT experiments/manual_flow.py \
  --stage1-run SAVED_STAGE1 --haven-root HAVEN_ROOT \
  --out NEW_DIAGNOSTIC --arm rvprobe --rounds 2
```

人工入口固定 `manual-author-debug`，先执行 `read_skill`，再向请求 mailbox 返回完整 UT；
回复绑定请求 SHA-256，远程模型入口被禁用。UT 和设计特定修正仅在诊断目录，不加入 skill/RAG。
框架单测、EDA 语义回归与人工闭环只能检查已走过的路径；不能保证任意新意图都可达或任意模型回答都可编译。
