# 固定 Stage-1 环境与三设计并发配对

本轮将已有 v1 → v4 环境映射按顺序合并（后者覆盖前者），复制各设计的
Stage-1 `ir`、`final`、原始溯源记录和 HAVEN 实现快照，逐文件校验内容不变。
初次按字节打包后，Stage-2 预检发现四个历史环境需要离线迁移，详见下文。
HAVEN/RVProbe 共用同一个设计环境，Stage-1 不计作被比较的方法开销。
本次打包、迁移和 baseline 验证调用模型次数为 0。

统一入口：`/var/storage/workspaces/clo91eaf/fixed16-environment-20260912-v3/stage1-map.json`。
同目录 `manifest.json` 保存原始及打包后身份。RTL/spec 仍引用原始数据集路径，
运行前后校验 SHA256；因此这是固定环境包，不是脱离现有数据集的独立发行包。

## 一条命令运行全部 16 个设计

在仓库根目录运行，设置已有 HAVEN Python 环境路径。每次须换新的工作及归档目录。

```bash
nix develop -c env PYTHONPATH="$PWD/experiments" \
  experiments/haven-python "$RVPROBE_HAVEN_ROOT" \
  experiments/haven_design_batch.py \
  --haven-root "$RVPROBE_HAVEN_ROOT" --env-file "$RVPROBE_HAVEN_ROOT/.env" \
  --stage1-map /var/storage/workspaces/clo91eaf/fixed16-environment-20260912-v3/stage1-map.json \
  --arm both --jobs 3 --relocate-completed \
  --encoded-witness-yosys /nix/store/ywdz7hiyhb7hkp0vyzqfdx3fmrf1v8pa-yosys-0.67/bin/yosys \
  --out /dev/shm/rvprobe-paired16-parallel-NEW_RUN \
  --archive-root /var/storage/workspaces/clo91eaf/rvprobe-paired16-parallel-NEW_RUN
```

不传 `--designs` 默认选择全部 16 个。`--jobs 3` 是同时最多 3 个设计，
不是同一设计的两侧并行。每侧最多 3 轮，每 intent 最多 4 条 sequence，
种子 20260906，请求模型 `deepseek-v4-flash-vision-exp`。
不复用历史回复；HAVEN prompt 和已有方法语义不变。截断、超时、预算内求解/采样失败
原样记录为实验终态，不据此自动重试整个设计。

每设计有独立 TMPDIR。子进程退出后先写终态、复制材料并校验 SHA256，
然后将本地设计目录换成指向归档的软链接，删除已验证的临时副本和可重建缓存。
归档也占据并发槽位；完成后才启动下一设计。归档失败则保留本地、停止接纳新设计、
等待其他已启动设计完成。空间不足时先等待已运行设计归档释放空间；仍不足则记录未启动。
这是容量保护，不保证任意规模设计都能放进固定大小的 tmpfs。

`progress.json` 在工作及归档根目录同步记录运行与存储状态，
`tracking.json` 仅列本轮尝试。可随时导出覆盖率、token、接受轮次、时间和失败原因：

```bash
python3 experiments/report_fixed_pairs.py \
  --manifest /var/storage/workspaces/clo91eaf/rvprobe-paired16-parallel-20260912-v1/tracking.json \
  --out /var/storage/workspaces/clo91eaf/rvprobe-paired16-parallel-20260912-v1/report
```

并发时的耗时含资源竞争，不能直接当作隔离运行的速度测量。

## 验证

最新 Python 全量回归：389 项，373 通过、16 条需额外环境/显式开启的测试跳过。
新增测试覆盖三并发、独立 TMPDIR、模型失败后继续、归档失败停止接纳、
空间不足不启动、成功/失败材料迁移和 Stage-1 打包字节一致性；不调用 LLM/EDA。

## 本轮启动记录及环境迁移

`rvprobe-paired16-parallel-20260912-v1` 于 2026-09-12 17:10:22 UTC 启动，
进程 4017317，最多 3 个设计并发。ALU、AES、SHA3、AXIL RAM 被当前 item
契约预检拒绝，尚未调用模型；这些失败原样保留并已迁移至归档。
之后队列自动推进至 UE Timer、UART、CAN，再继续剩余设计。

离线迁移细节：

- ALU、AES、SHA3：删除 item 中对 sequence-owned 输入字段的旧场景约束。
  原 baseline SystemVerilog 保持不变。分别为 12、6、6 条。
- AXIL RAM：旧 item 同时暴露事务别名和原始握手字段，不能仅删约束。
  先确定性刷新 AXI 字段所有权和模板，再依据原 item 的别名关系，把 baseline
  的 `kind/addr/data/strb` 约束迁移至当前事务字段。只转换字段与读写判别，
  不选择新的操作数，不删除任何检查。9 条 baseline 的源码因此有 API 级变化，
  不能称为逐字节保留；两侧使用同一套迁移后的 baseline。
- 原始记录、未通过的中间版本与迁移哈希都保留，不覆盖旧实验。
  `migrate_axi_baseline.py` 的输出强制标记未编译，不可直接登记为 passing Stage-1。
- `merge_stage1_environment.py` 现在必须逐设计通过实际的 `prepare` 接口检查，
  才发布总映射；不能仅依赖历史 `compile_passed`。v2 未通过 AXIL 检查，
  不发布 map；v3 的 16 个设计全部通过。
- `validate_fixed_baseline.py` 在禁止模型调用的保护下，用正式框架的逐序列独立
  VCS 回放及 URG 合并验证共享 baseline，诊断结果不算作正式模型实验。

四个零模型预检失败设计会在第一批父进程退出且正常归档结束后，进入
`rvprobe-paired16-parallel-20260912-v2` 补跑，仍为三并发。
这不是自动重试模型失败；只补已定位、已离线迁移的共享环境问题。
当前运行过程保留每次框架哈希；准备工具新增不改变已运行设计的 Stage-2 方法语义。

17:25 UTC 核验：四个迁移环境的共享 baseline 均通过逐序列独立回放：
ALU 12 条/77.336 秒，AES 6 条/58.869 秒，SHA3 6 条/47.014 秒，
AXIL RAM 9 条/62.211 秒，全部 0 模型调用。这些时间为离线环境验证，
不混入正式两侧时间/token。五个已结束的迁移/验证工作目录均校验归档并以软链接
替代本地副本，`/dev/shm` 可用空间回升至约 6.5 GiB；所有材料可从归档恢复。

后续链进程 4144925 已直接启动，执行本轮归档中的 `continue-preflight.sh`，
等待第一批父进程退出，不是 cron/定时任务。当前三实验进程仍存活。
链启动前检查第一批不是 storage_blocked，并检查四个 baseline 验证已通过；
完成四设计补跑后自动导出 `report-combined.{json,md,csv}`。

当前统一追踪文件位于第一批归档的 `tracking-combined.json`，记录两批全部尝试。
查看本轮全 16 个设计须使用这个文件，而不是只看第一批 `progress.json` 中
四个已经保留的预检失败。17:25 的报告仍为 0/16 终态配对，实验尚在运行。
