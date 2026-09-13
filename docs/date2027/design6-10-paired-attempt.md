# 设计 6–10 配对尝试：环境缺口阻塞（2026-09-09）

本轮已结束，**0/5 个设计完成配对闭环**，不是五个实验成功结束。
没有启动 HAVEN/RVProbe Stage-2，没有新的覆盖率、UT 或 witness 结果。
记录目录：`out/experiments/design6-10-paired-20260909`。

## 各设计状态

| 编号 | 设计 | 停止位置与原因 | 已报告 tokens | 模型工作进程耗时 |
| --- | --- | --- | ---: | ---: |
| 6 | uart | Phase 0–3 已生成；两个主动 agent 和串口 BFM 超出当前配对支持范围，在本地 seq_item 编译时中止 | 89,771 | 499.140 秒 |
| 7 | can | 静态预检：默认 RTL 同时使用 wb_clk_i 和 clk_i | 0 | 未发起模型工作 |
| 8 | ethmac | 静态预检：Wishbone、发送 PHY、接收 PHY 三个时钟 | 0 | 未发起模型工作 |
| 9 | i2c | Phase 0–2B 已生成；BFM、多 agent，以及被标为 inout 的开漏接口，在 Phase 3 前拦截 | 60,278 | 349.794 秒 |
| 10 | gpio | 原始 haven.json 要求 GPIO BFM，当前形式环境未表示这个外部引脚驱动源 | 0 | 未发起模型工作 |

这些是框架/环境适配阻塞，不能计为 DUT 功能失败或零覆盖率。
CAN 与 Ethernet 没有被绑成同一个时钟；GPIO 没有被移除 BFM。
UART/I²C 没有被手工合并 agent、删除生成组件或修改 IO 方向。

## 成本与时间

- 模型：`deepseek-v4-flash-vision-exp`，temperature=0.3。
- 10 次已返回用量的模型请求，总计 **150,049 tokens**：输入 50,827，completion 99,222。
- UART 的 6 次请求均已返回用量，中止发生在本地编译期间，不是未返回用量的模型请求。
- I²C 的 4 次请求均已返回用量，在组件生成前正常触发范围检查。
- 模型工作进程累计 848.934 秒，约 14 分 9 秒。
- 批次 UTC 15:02:53–15:18:48（UTC+8 23:02:53–23:18:48），墙钟 955.391 秒，含检查与切换间隔。
- 这些都是共享准备/失败尝试成本，不能冒充成功方法的 Stage-2 成本。
- 若后续复用检查点，应保留本轮成本来源，不能把检查点当作免费生成。

## 具体缺口

当前 `coverage_flow.prepare` 只接受一个主动 driver、一个时钟、无外部 BFM/inout 的环境，允许附加纯被动 agent。

UART Phase-2B 同时保留两个主动 agent 并增加 uart_serial BFM；Phase-3 的串口 driver 与 BFM 连接到相同串口引脚，还需要检查重复驱动归属。

I²C 原始 RTL 的 scl_pad_i、sda_pad_i 是输入，输出值与输出使能另有端口；HAVEN 的 RTL reconciliation 自动把这两个输入提升成 inout。
这是适配器行为，不是原始 RTL 自带 inout 的证据。生成的 agent mode 还是 master/slave，当前配对入口只接受 active/active_master 加 passive。

要继续完整配对，需要共同定义多时钟时序、BFM 与 sequence 的引脚归属、开漏连接及复位关系，并在形式求解和两侧回放中采用一致定义。
本轮没有擅自通过删组件或绑定时钟扩展实验条件；等待确定后续是否扩展共同环境。

## 产物与复核

- `summary.json`、`progress.json`：五个设计的终态。
- `uart/stage1-costs.json`、`i2c/stage1-costs.json`：供应商用量与时间。
- `uart/scope-audit.json`：中止原因及检查点记录。
- `i2c/stage1/*/paired-scope.json`：提前检查结果。
- `verification.json`：只读审计，核对框架哈希、请求数、token 加总与未启动 Stage-2 的事实。
- `out/diagnostics/run-design6-10-paired.py`：初始批次入口。
- `out/diagnostics/continue-design9-scoped-paired.py` 与 `paired-stage1-scope-worker.py`：I²C 提前检查入口；只拦截、不修改模型内容。

框架回归：178 项，162 通过、16 项环境相关跳过。本轮未修改框架源码、prompt、skill、RTL 或外部 HAVEN 实现。

审计复核：`python3 out/diagnostics/audit-design6-10.py`。
