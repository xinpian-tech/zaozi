# ETHMAC 输出位别名四态修复（离线）

## 原因与修复

原现场：`/var/storage/workspaces/clo91eaf/rvprobe-retry2-20260915-v2/ethmac/flow/paired/rvprobe/round-3`。
`bd_bit0_0to1` 的第五个布尔原子直接引用 `wb_dat_o[0]`。
在 Yosys 输入 JSON 中，32 位 `wb_dat_o` 的最低位和单比特
`rvp_encoded_atom_4` 都是 bit 98。原有去重只合并整个 SigSpec 相同的输出，
没有处理总线与切片重叠。

Yosys 0.67 xprop 输出：

```text
rvp_encoded_atom_4_d = wb_dat_o_x[0] ? X : wb_dat_o_d[0]
rvp_encoded_atom_4_x = 0
```

这不是 LLM 语法错误，也不是检查器误报：未知数据经解码后再次成为“已知”值。
后端的 X/Z 检查正确阻止了该候选。

新增 `pack_output_bits`：编码前把所有输出按底层 bit ID 去重，统一导出为
`rvp_encoded_outputs`。`bind_packed_expression` 将目标、环境条件和总线冲突条件
绑定到该总线的值/未知标记，保持 JSON 的低位优先顺序。
原 netnames、初始化属性、单元连接、DUT、复位和原始 Cover 不变。
逐位、切片、重排、重复位因而共用同一对编码，不经过未知值解码再编码。

保留原有 rail audit 和原生 LTL 验收；没有把 X 填零，没有修改模型输出或 HAVEN。
`output-bindings.json` 保存位映射及最终编码表达式。

## 回归

- Python 全量：506 项，467 通过、39 跳过。
- `smoke_encoded_aliases.py`：原生 VCS 对照 RAM 未初始化、部分复位、完整别名、
  位切片、重叠总线及重复位；对已知位比较数值，对所有位比较未知标记。
  旧编码作为负对照，完整别名和单比特别名各产生 9 次错误，新编码无误差（13 次采样）。
- JG 微型测试：未写 RAM 的伪目标 `unreachable`；写入后的合法目标 `covered`。
- 真实 ETHMAC：复用第三轮已保存 LTL，不重新生成或手改意图；每个意图只检查
  一条候选，不代表重新完成 4 条/intent 的完整闭环，不更新旧实验覆盖率与状态。

| 保存的意图 | 编码求解 | 原生 LTL 验收 |
|---|---|---|
| `bd_bit0_0to1` | covered | passed |
| `bd_bit31_0to1` | covered | passed |
| `bd_bit1_1to0` | covered | passed |
| `tx_dma_adr7_0to1` | covered | passed |

编码结果位于 `/var/storage/workspaces/clo91eaf/ethmac-output-bits-20260915-v1`
和同目录 `ethmac-output-bits-<label>-20260915-v1`。
原生验收最终归档到
`/var/storage/workspaces/clo91eaf/ethmac-output-bits-check-20260915-v1`。

## 环境插曲（保留失败现场）

首次回放误把 `stage1-costs.json` 当成 Stage1 目录，纠正为固定环境的 `ethmac/stage1`。
随后直接在网络存储执行遇到 VCS `VFS_SDB_ERROR`，另一个 JG 进程在 SQLite 原生库崩溃。
原生 EDA 重试改在 `/dev/shm` 执行，再验证归档并迁出临时文件；这些不是 LTL 错误，
没有触发付费修复。总模型请求数和新增模型 token 数均为 0。

## 复现入口

```bash
PYTHONPATH=experiments <python> -m unittest test_encoded_witness_probe -q
<python> experiments/smoke_encoded_aliases.py --out <新建临时目录> --yosys <yosys>
<python> experiments/encoded_witness_probe.py \
  --source-solve <round-3>/generation/attempt-1/solve \
  --replay-config <flow>/manifest/replay.json \
  --out <新建编码目录> --yosys <yosys> --eda-shell <repo>/experiments/eda-shell \
  --label bd_bit0_0to1 --noncontending-tristates
<python> experiments/replay_encoded_witness.py \
  --source-solve <round-3>/generation/attempt-1/solve \
  --replay-config <flow>/manifest/replay.json --encoded <新建编码目录> \
  --stage1 <固定环境>/ethmac/stage1 --haven-root <haven> \
  --out <新建回放目录> --label bd_bit0_0to1
```
