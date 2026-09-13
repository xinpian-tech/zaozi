# 保存的 DeepSeek 输出：框架修复与离线复核

本次只修框架并复用已付费输出，新增模型调用和 provider token 均为 0。
没有修改模型 UT、验证意图或 RAG/skill，也没有将人工答案放入提示词。

## 为什么人工验收通过、自动批次却失败

两次并不是只替换了 LTL：此前 `manual_stage1.py` 使用人工指定的协议映射、共享环境和诊断基线；
新批次用 DeepSeek 重新生成 Stage-1、组件及基线。前者证明人工配置下的 UT/JG/回放路径可执行，
不能证明后者的自动规划、解析和环境装配也完整。两类结果不能当作同条件模型比较。

按后续明确要求，批次入口必须为所有设计提供 `--stage1-map`，缺失时在任何模型请求前失败。
已删除先前保留的 `--prepare-stage1` 及规划 checkpoint 选项，也删除旧四设计入口的自动生成/等待路径。
实验流程只消费固定 Stage-1，`shared_setup_mode=frozen`、`stage1_model_calls=0`；不存在选择性重建入口。
Stage-1 来源不再绑定 DeepSeek 型号，历史准备成本单独保留；前后校验共享组件、基线、规划、RTL 及实现哈希。
替换作者时不重建组件或基线；人工诊断基线仍不能直接改标签冒充正式基线。

## 修复

- `simple_spi`：回放使用了 Scala 类名，而 CIRCT 输出模块带哈希后缀。现在使用 prepared job 的实际 `top`，
  同时校验作者类名、原始 SV 哈希和选中的 Cover；不放松单 DUT 结构检查。
  已通过生成检查的 UT 如果在回放监视器构造阶段失败，标记为基础设施错误，不再让模型重写 UT。
- `spi`：模型返回 `{type: json_object, content: {...}}`。JSON 层解开精确匹配的信封，
  任意业务 `content` 不变；架构和模板缺少模块名时立即拒绝，不能退化成 `DUT_interface.sv`。
  `monitor`/`observer` 模式明确归一化为被动观察者。
- AXI 三个设计：先进行 BFM 引脚归属、agent 模式归一化，再校验单主动 AXI 发起端。
  不删除 agent，不给额外端口补常量，不允许双重驱动。
- SDRAM：在共同使用的 `hdl/sdram/haven.json` 补充原生 SDRAM BFM 的真实端口映射和设备配置。
  双向口仍由 CIRCT 导入后接入纯接线 wrapper，原 DUT 不变；不复制存储控制器实现、不加入测试数据。
- VCS `SFCOR` 缺文件错误直接报告文件/编译清单问题，不再反复调用模型修改无关 `seq_item`。
- 以上 HAVEN 改动进入 40 文件可移植补丁，正向/反向应用检查通过。

## 实测及边界

`recheck_saved_planning.py` 原样读取五个设计保存的规划/协议提取结果，只解包错误信封并应用显式共享接线配置。
`spi`、`ue_gpio`、`ue_spi`、`ue_uart`、`sdram` **5/5 通过真实 CIRCT/JG 环境预检**及模板生成。
这不是五个完整实验：它们尚未重新进行模型组件生成、基线仿真及覆盖闭环。

`replay_saved_candidate.py --generation ...` 仅修复已保存 candidate 的模块名元数据，重新核对原始 solver SV；
输入、80 条候选 sequence、LTL 本体均不变。实际回放完成 12 条原基线和第 1 条候选；
第 2 条候选（同一 intent 的重采样 witness）未命中原始 LTL，按既有策略停止，不能写成 80/80 成功。
本次回放耗时 126.464 秒。

失败根因有直接证据：`sample-0.vcd` 从初始时刻就给 `dut/rfifo/mem[0]` 选了 `0x30`；
原 RTL 的 FIFO 只复位指针、不初始化存储内容。该 trace 中间一拍将地址切到 FIFO，随后
`dat_o` 在形式轨迹中等于 `0x30`，真实四态仿真对应拍为 X，故 Cover 不命中。
这是一个利用未初始化存储取值的形式候选，不是模块名误报，也不是可以接受的真实 witness。
没有补零、force 内部状态、屏蔽比较，亦没有手工改写 LTL。后续须由正常闭环基于测量反馈生成更完整的意图。

因此，“修复已发现的框架错误”不等于“所有模型生成的 LTL/witness 都必然通过”。
原始失败批次仍记录为 0/6 完整成功、341,309 新增 tokens，不改写历史成绩。

最终回归：实验 Python 测试 278 项，262 通过、16 跳过；HAVEN 针对性测试 35 通过、1 跳过。
模块名来源、失效元数据拒绝、基础设施错误禁止重写 UT、冻结 Stage-1 入口和缺文件分类均有回归覆盖。

移除全部实验入口 Stage-1 生成路径后的回归：283 项，267 通过、16 跳过；
额外验证了缺失固定环境时不启动子进程、旧生成选项被拒绝、独立 Stage-1 作者可用、
组件/RTL/实现/记录的修改会被拒绝，批处理只派发覆盖实验。验证期间无模型调用。

工作证据：`/dev/shm/rvprobe-deepseek-offline-fix-20260910/`（易失）。
精简归档位于工作区 `out/experiments/deepseek-six-framework-fixes-20260910/`，保留源码、日志、
coverage 数据库、规划及失败记录，省略可重建的 `simv`、`simv.daidir`、`csrc` 和 Python 缓存。
记录中的原始绝对路径不改写；复现需要恢复相应布局。

复核入口均通过 `nix develop -c experiments/haven-python HAVEN_ROOT ...` 执行：

- `experiments/recheck_saved_planning.py --previous OLD_BATCH --haven-root HAVEN_ROOT --out NEW_DIR --designs spi ue_gpio ue_spi ue_uart sdram`
- `experiments/replay_saved_candidate.py --bundle BUNDLE --candidate CANDIDATE --generation GENERATION --haven-root FROZEN_HAVEN --out NEW_DIR --eda-config experiments/designs/haven_eda.json --eda-shell experiments/eda-shell`

第二个入口会如实重现剩余的无效采样失败，不承诺全通过。两者都在代码中禁止模型调用。
