# RVProbe raw / HAVEN native 接口分离

当前策略：`rvprobe-raw-haven-native-v1`。RVProbe 在 DUT IO 上表达 LTL，求解后由框架直接回放输入事件，绕过 HAVEN 原生事务调度与响应 BFM。HAVEN 保留原生 DSL、事务 driver 和 BFM API。

共享的是原始 RTL、固定物理条件、初始覆盖基线、覆盖统计和预算，不是激励模板。RVProbe 不能驱动 DUT 输出；固定时钟、复位、静态连接等条件仍必须满足。回放仍检查实际输入及原始 LTL Cover，不能把形式求解成功当作仿真验证成功。

## 实现

- `prepare` 在加入回放适配器前冻结 `native_seq_item`。HAVEN 提示词与 DSLCodegen 只使用该原生声明及原生字段。
- `rvp_*` 是框架内部非随机存储，不再加入公共字段列表，没有 soft 默认约束。原生 item 默认 `rvp_raw=0`；witness renderer 显式填入事件并选取 raw 分支。
- HAVEN DSL 显式引用私有 raw 字段时报错，不静默过滤。原生接口校验在首次模型请求前执行。
- RVProbe `spec-io-on-demand-rtl-v3` 去掉原生基线 DSL、事务契约、driver 调度及 BFM API，只提供规格、IO、物理条件、覆盖和自身历史；RTL 按需读取。
- 旧 independent bundle 不自动复用，必须重新 prepare。历史结果不改写；HAVEN 自身提示词模板未修改。

## 零模型验证

- 锁定 HAVEN Python 环境下：391 项单元测试，375 通过、16 跳过。
- AXIL RAM、UE_TIMER、UE_GPIO、UE_SPI、UE_UART：重新 prepare 后，原生及扩展运行时 item 校验均无错误；保存的原生回答均通过 DSLCodegen，每个生成 4 条序列，无约束过滤。这只是代码生成回归，不是完整配对成功。
- UE_TIMER 使用原 attempt-2；attempt-1 含私有 raw 引用，不能作为原生接口样本。其余使用原 attempt-1，回答未改写。
- VCS 合成多时钟 raw 回放：51 个采样事件、102 项输出比较通过；私有字段不受 randomize 影响；违反静态输入条件的负例在 DUT 采样前被拒绝。该检查使用合成事件，不是新 JG 求解或付费实验。

产物：

- `/var/storage/workspaces/clo91eaf/method-separated-offline-20260913-v2/summary.json`
- `/var/storage/workspaces/clo91eaf/rvprobe-private-raw-20260913-v1/summary.json`

最初离线检查器误在同一进程预加载另一份 HAVEN，触发安装身份保护；失败记录保留在 `method-separated-offline-20260913-v1`。v2 按冻结快照逐设计启动独立进程后通过。所有检查零模型调用。VCS 产物已校验归档，释放对应 `/dev/shm` 副本及可重建缓存，旧路径保留为归档链接。
