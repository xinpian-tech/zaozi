"""Export actual frozen-property count-sweep measurements, including shortfalls."""
import argparse
from collections import Counter
import csv
import json
from pathlib import Path
import statistics

from run_records import utc
from sweep_frozen_stimuli import curve_time


def table(headers, rows):
    def cell(x): return str(x).replace('|', '\\|').replace('\n', ' ')
    return '\n'.join(['| ' + ' | '.join(map(cell, headers)) + ' |',
                      '| ' + ' | '.join('---' for _ in headers) + ' |'] +
                     ['| ' + ' | '.join(map(cell, row)) + ' |' for row in rows])


def csv_out(path, rows):
    if not rows: return
    with path.open('w', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader(); writer.writerows(rows)


def report(root):
    batch = json.loads((root / 'summary.json').read_text())
    designs, data, property_rows = {}, [], []
    for name in batch['designs']:
        path = root / name / 'summary.json'
        d = json.loads(path.read_text()) if path.is_file() else {'status': 'failed', 'error': 'missing summary'}
        designs[name] = d
        for cell in d.get('cells', []):
            data.append(dict(design=name, requested_per_property=cell['count'],
                design_status=d['status'], properties=cell['properties'],
                properties_reaching_cap=cell['properties_reaching_cap'], actual_stimuli=cell['actual_stimuli'],
                baseline_composite=d['baseline']['score'], composite=cell['coverage']['score'],
                line=cell['coverage']['percent'].get('line'), cond=cell['coverage']['percent'].get('cond'),
                toggle=cell['coverage']['percent'].get('toggle'), branch=cell['coverage']['percent'].get('branch'),
                fsm=cell['coverage']['percent'].get('fsm'),
                solver_seconds=cell['solver_seconds'] if cell['solver_time_complete'] else None,
                solver_known_seconds=cell['solver_seconds'], solver_time_complete=cell['solver_time_complete'],
                core_prefix_seconds=cell['core_prefix_seconds'],
                coverage_merge_seconds=cell['coverage_merge_seconds'],
                all_counts_session_wall_seconds=d['elapsed_seconds']))
        for prop in d.get('properties', []):
            for cap in batch['counts']:
                property_rows.append(dict(design=name, round=prop['round'], label=prop['label'],
                    requested=cap, actual=min(cap, len(prop['accepted'])), original_status=prop['original_status'],
                    final_status=prop.get('status'), sampler_status=prop.get('sampling', {}).get('status'),
                    solver_seconds=curve_time(prop, cap, 'solver_prefix_seconds') if 'status' in prop else None,
                    available_native_valid=len(prop['accepted']), replay_rejections=len(prop['rejected']),
                    sampling_error=prop.get('sampling', {}).get('error'),
                    original_solve_seconds=prop['original_solve_seconds'], ltl_sha256=prop['ltl_sha256'],
                    source_summary=prop['source_summary']))
    csv_out(root / 'design_counts.csv', data)
    csv_out(root / 'property_counts.csv', property_rows)
    complete = {n: d for n, d in designs.items() if d['status'] == 'completed' and len(d.get('cells', [])) == len(batch['counts'])}
    averages = []
    for cap in batch['counts']:
        cells = [d['cells'][cap - 1] for d in complete.values()]
        if not cells: continue
        averages.append(dict(count=cap, designs=len(cells),
            mean_composite=statistics.mean(c['coverage']['score'] for c in cells),
            stimuli=sum(c['actual_stimuli'] for c in cells),
            reaching=sum(c['properties_reaching_cap'] for c in cells),
            solver_seconds=sum(c['solver_seconds'] for c in cells),
            solver_time_complete=all(c['solver_time_complete'] for c in cells)))
    csv_out(root / 'averages.csv', averages)
    props = [p for d in designs.values() for p in d.get('properties', [])]
    statuses = Counter(p.get('status', 'interrupted') for p in props)
    original = Counter(p['original_status'] for p in props)
    rejection_count = sum(len(p['rejected']) for p in props)
    samplers = Counter(p.get('sampling', {}).get('status') for p in props if p.get('sampling'))
    out = ['# RVProbe：每个 property 生成 1–8 条 stimulus 的敏感性实验', '',
        f'报告生成时间：{utc()}。批次状态：`{batch["status"]}`。', '',
        '## 1. 实验口径', '',
        f'- Property 来源：`{batch["source_batch"]}`；固定这个批次已经生成的各轮最终 LTL，不混入其他运行。',
        '- 来源批次处理过 16 个设计，但 ETHMAC 后续模型生成中断；只纳入实际保存了求解输入的 property，不补造缺失轮次。',
        '- 本实验新增 LLM Calls / Tokens 均为 0；不重新运行模型覆盖率闭环。',
        '- 每个已可解 property 重新求解，采用固定种子 20260906、原 witness 长度和原性质，最多产生 8 条不同输入轨迹。',
        '- 1–8 使用同一个最大采样池的递增前缀，避免每档独立随机采样造成混淆；不是八次独立冷启动实验。',
        '- 本次采样脚本的 property/task compile limit 为来源 frozen job 的 120s；`prove` 和每次 `visualize -replot` 的求解限制均为来源 sampling 配置的 30s。这些是配置限制，不是实测总耗时；不能把 120s 编译限制写成 prove 限制。初始轨迹后最多 14 次偏好尝试以获得另外 7 条不同轨迹。',
        '- 已在来源记录中 infeasible/unknown 的 property 不重复尝试。新的首次非 covered 求解立即停止该 property 的采样；不修改 LTL、不增加预算、不切换备用求解器。',
        '- 候选必须经过原生四态仿真和原 LTL Cover 验收；失败候选不计入有效 stimulus，也不触发模型修复或额外补池。',
        '- 不足 N 条时用实际已有的有效前缀，明确记录 shortfall；不会复制样本凑数。不同设计仍继续执行。',
        '- 固定 Stage-1 baseline；已有 baseline 仿真只在哈希、输入来源匹配时复用。新刺激逐条独立仿真，再用 URG 合并真实覆盖数据库。',
        '- 综合代码覆盖率是可测 line/cond/toggle/branch/fsm 百分比的算术平均，不是功能覆盖率。',
        '- 原始大实验使用 4 条上限并可能使用后端备用 witness 搜索；本次无备用搜索且重新取样，因此本次 N=4 不应冒充原实验的精确复现。', '',
        '## 2. 时间定义', '',
        '- **求解时间**：JG 内部实际测得的 `prove` 和 `visualize -replot` 墙钟耗时之和，包含到该前缀为止的重复/被拒绝候选搜索；不是纯 SMT CPU 时间。',
        '- 达不到请求上限时，计入该 property 的整池搜索成本；因此完全无有效 stimulus 的 property 在所有档位都贡献相同失败成本。这是对最大池的离线前缀归因，不是独立运行 N=1 时 fail-fast 的耗时预测。求解在计时点前异常的情况标为缺失，不把未知时间填成 0。',
        '- 历史已经不可解且本次未重试的 property，本次增量求解时间为 0，原始求解时间另外保留在 property CSV。',
        '- `core_prefix_seconds` 另含 JG 分析、elaboration/reset 设置耗时，不含进程启动和所有导出开销。',
        '- 单次 pool 的完整 JG 进程墙钟时间、每条验收时间及整个 1–8 sweep 的墙钟时间保存在各设计 summary 中。不要把共享前缀时间误写成八个独立端到端运行时间。', '',
        '## 3. 完成情况', '',
        f'- 完整得到 1–8 八个覆盖率点的设计：{len(complete)}/{len(designs)}。',
        f'- 纳入 property：{len(props)}；来源求解状态：`{dict(original)}`。',
        f'- 最终 property 状态：`{dict(statuses)}`；采样状态：`{dict(samplers)}`。',
        f'- 原生回放拒绝候选：{rejection_count} 条。',
        '- 设计 `completed` 仅表示全部八档测量完成，不表示每个 property 都可解；采样 `complete` 仅表示形式候选数量满足要求，不保证这些候选全部通过原生回放。',
        f'- 整批实际墙钟时间：{batch.get("elapsed_seconds", 0) / 60:.2f} 分钟；设计并发数：{batch["jobs"]}。', '',
        '## 4. 平均覆盖率、实际刺激量和求解时间', '',
        '平均值仅使用完整得到全部八个点的相同设计集合；失败设计不以零或其他历史结果补齐。', '',
        table(['每 property 上限', '设计数', '平均综合覆盖率 %', '实际 stimulus 总数', '达到上限的 property 数', '求解时间总和 s'],
              [[a['count'], a['designs'], f"{a['mean_composite']:.4f}", a['stimuli'], a['reaching'],
                f"{a['solver_seconds']:.3f}" if a['solver_time_complete'] else '缺失（见 CSV）'] for a in averages]), '',
        '## 5. 各设计覆盖率曲线（%）', '']
    rows = []
    for name, d in designs.items():
        scores = {c['count']: c['coverage']['score'] for c in d.get('cells', [])}
        rows.append([name, f"{d['baseline']['score']:.3f}" if 'baseline' in d else '—'] +
                    [f'{scores[k]:.3f}' if k in scores else '—' for k in batch['counts']] +
                    [f"{scores[batch['counts'][-1]] - scores[1]:+.3f}" if 1 in scores and batch['counts'][-1] in scores else '—'])
    out += [table(['设计', 'baseline'] + [str(k) for k in batch['counts']] + ['末档−1，百分点'], rows), '',
            '## 6. 各设计求解时间曲线（秒）', '']
    rows = []
    for name, d in designs.items():
        cells = {c['count']: c for c in d.get('cells', [])}
        rows.append([name] + [f"{cells[k]['solver_seconds']:.3f}" if k in cells and cells[k]['solver_time_complete'] else '—'
                              for k in batch['counts']])
    out += [table(['设计'] + [str(k) for k in batch['counts']], rows), '',
            '## 7. 实际有效 stimulus 数量', '']
    rows = []
    for name, d in designs.items():
        cells = {c['count']: c for c in d.get('cells', [])}
        rows.append([name, len(d.get('properties', []))] +
                    [cells[k]['actual_stimuli'] if k in cells else '—' for k in batch['counts']])
    out += [table(['设计', 'property 数'] + [str(k) for k in batch['counts']], rows), '',
            '## 8. 观测结论', '']
    if averages:
        first, last = averages[0], averages[-1]
        out.append(f"- 从 1 条上限到 {last['count']} 条上限，平均覆盖率由 {first['mean_composite']:.4f}% 变为 {last['mean_composite']:.4f}%，增量 {last['mean_composite'] - first['mean_composite']:.4f} 个百分点。")
        for before, after in zip(averages, averages[1:]):
            out.append(f"- {before['count']}→{after['count']}：平均覆盖率增量 {after['mean_composite'] - before['mean_composite']:.4f} 个百分点，实际增加 {after['stimuli'] - before['stimuli']} 条有效 stimulus。")
        gains = sorted(((d['cells'][-1]['coverage']['score'] - d['cells'][0]['coverage']['score'], n)
                        for n, d in complete.items()), reverse=True)
        out.append('- 覆盖率增量最大的设计：' + '；'.join(f'{n} {g:+.3f} 个百分点' for g, n in gains[:5]) + '。')
        flat = [n for g, n in gains if abs(g) < 1e-9]
        if flat:
            out.append(f'- {len(flat)}/{len(complete)} 个设计在 N=1 后没有新增代码覆盖：' + '、'.join(flat) + '；增加样本主要增加求解和回放工作量。')
        middle = next((a for a in averages if a['count'] == 4), None)
        if middle and last['count'] > 4 and last['mean_composite'] > first['mean_composite']:
            share = 100 * (middle['mean_composite'] - first['mean_composite']) / (last['mean_composite'] - first['mean_composite'])
            out.append(f"- N=4 已取得本次 N=1→{last['count']} 覆盖增量的 {share:.2f}%；4→{last['count']} 再提升 {last['mean_composite'] - middle['mean_composite']:.4f} 个百分点。")
            if middle['solver_time_complete'] and last['solver_time_complete'] and middle['solver_seconds']:
                extra = last['solver_seconds'] - middle['solver_seconds']
                out.append(f"- 4→{last['count']} 的前缀归因求解成本增加 {extra:.3f}s（+{100 * extra / middle['solver_seconds']:.2f}%），实际有效 stimulus 从 {middle['stimuli']} 增到 {last['stimuli']}。按这一次测量，4 条是较合理的成本/覆盖率折中；追求更高覆盖率的 GPIO、SDRAM、ETHMAC 等设计可单独增加上限。")
        if last['solver_time_complete'] and last['solver_seconds']:
            expensive = sorted(((d['cells'][-1]['solver_seconds'], n) for n, d in complete.items()), reverse=True)
            out.append('- N=8 求解成本最高的三个设计：' + '；'.join(f'{n} {s:.3f}s' for s, n in expensive[:3])
                       + f"，合计占 {100 * sum(s for s, _ in expensive[:3]) / last['solver_seconds']:.2f}%。总时间包含重复候选和失败搜索，不能直接理解为平均每新增一条有效 stimulus 的纯求解成本。")
    out += ['- 这是固定 property、固定长度、单种子的采样量敏感性结果；不能推论增加序列必然补足所有状态深度、协议初始化或不可达目标。',
            '- 同一最大池的前缀通常应保持覆盖率非递减；每档数据来自实际覆盖数据库合并，不进行数值取最大或跨运行拼接。', '',
            '## 9. 未达到上限或失败的 property', '']
    failures = []
    for name, d in designs.items():
        if d['status'] != 'completed': out.append(f'- 设计 `{name}` 未完整完成：`{d.get("error", "unknown")}`。')
        for p in d.get('properties', []):
            if len(p['accepted']) < batch['counts'][-1]:
                failures.append([name, p['round'], p['label'], p['original_status'],
                                 len(p['accepted']), p.get('sampling', {}).get('status', '未重试'),
                                 len(p['rejected'])])
    out += ['', table(['设计', '轮次', 'property', '来源状态', '有效数', '本次采样', '回放拒绝数'], failures), '',
            '## 10. 数据与复现', '',
            f'- 实验目录：`{root}`。',
            '- `design_counts.csv`：每个设计 × 1–8 的代码覆盖率各分项、综合覆盖率、时间及实际数量。',
            '- `property_counts.csv`：每个 property × 1–8 的数量、时间、失败状态和来源哈希。',
            '- `averages.csv`：共同完整设计集合的均值和总量。',
            '- 各设计 `summary.json`、`sampling/*/jg.log`、`pool.json`、回放日志和 URG 数据均保留。',
            '- 脚本：`experiments/sweep_frozen_stimuli.py`；导出：`experiments/report_frozen_stimuli.py`。',
            '- 工作缓存通过哈希验证后迁移到持久化归档；`/dev/shm` 只留下指向归档的链接。', '']
    (root / 'report.md').write_text('\n'.join(out))
    print(json.dumps(dict(report=str(root / 'report.md'), complete_designs=len(complete),
                          properties=len(props), averages=averages), ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    report(parser.parse_args().root.resolve())
