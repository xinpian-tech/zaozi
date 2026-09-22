"""Report an RQ1-matched anchored stimulus extension without inventing timing."""
import argparse
from collections import Counter
import json
from pathlib import Path
import statistics

from report_frozen_stimuli import csv_out, table
from run_records import utc
from sweep_rq1_stimuli import extra_time


def report(root):
    batch = json.loads((root/'summary.json').read_text())
    designs = {}
    for name in batch['designs']:
        path = root/name/'summary.json'
        designs[name] = json.loads(path.read_text()) if path.is_file() else dict(status='failed',error='missing summary')
    complete = {n:d for n,d in designs.items() if d['status']=='completed' and len(d['cells'])==8
                and batch['designs'][n]['status']=='completed'}
    data, properties, average = [], [], []
    for name,d in designs.items():
        for c in d.get('cells',[]):
            data.append(dict(design=name,cap=c['count'],status=batch['designs'][name]['status'],
                rq1_coverage=d['source']['expected_coverage'],reference_four_verified=d.get('reference_check',{}).get('passed',False),
                baseline=d['baseline']['score'],coverage=c['coverage']['score'],
                **{k:c['coverage']['percent'].get(k) for k in ('line','cond','toggle','branch','fsm')},
                actual_stimuli=c['actual_stimuli'],properties=c['properties'],reaching_cap=c['properties_reaching_cap'],
                new_solver_seconds=c['new_solver_seconds'] if c['new_solver_time_complete'] else None,
                new_solver_known_seconds=c['new_solver_seconds'],new_solver_time_complete=c['new_solver_time_complete'],
                historical_prefix_reused=c['historical_prefix_reused'],cold_end_to_end_solver_seconds=None,
                historical_all_goal_solve_seconds=d['historical_goal_solve_seconds'],
                coverage_merge_seconds=c['coverage_merge_seconds'],source=d['source']['source']))
        for p in d.get('properties',[]):
            for cap in range(1,9):
                properties.append(dict(design=name,round=p['round'],label=p['label'],cap=cap,
                    historical_count=p['historical_count'],actual=min(cap,len(p['accepted'])),
                    status=p['status'],extension_route=p['extension_route'],original_status=p['original_status'],
                    historical_initial_solve_seconds=p['original_solve_seconds'],
                    new_solver_seconds=extra_time(p,cap),rejected=len(p['rejected']),
                    stop_reason=p.get('stop_reason') or p.get('error') or p.get('sampling',{}).get('error')
                                or p.get('sampling',{}).get('stop_reason'),ltl_sha256=p['ltl_sha256'],
                    source_summary=p['source_summary']))
    for cap in range(1,9):
        cells = [d['cells'][cap-1] for d in complete.values()]
        if cells:
            average.append(dict(cap=cap,designs=len(cells),coverage=statistics.mean(c['coverage']['score'] for c in cells),
                actual_stimuli=sum(c['actual_stimuli'] for c in cells),reaching_cap=sum(c['properties_reaching_cap'] for c in cells),
                new_solver_seconds=sum(c['new_solver_seconds'] for c in cells),
                new_solver_time_complete=all(c['new_solver_time_complete'] for c in cells)))
    csv_out(root/'design_counts.csv',data)
    csv_out(root/'property_counts.csv',properties)
    csv_out(root/'averages.csv',average)
    all_props = [p for d in designs.values() for p in d.get('properties',[])]
    verified = [n for n,d in designs.items() if d.get('reference_check',{}).get('passed')]
    text = ['# RVProbe：与 RQ1 95.58% 对齐的 1–8 条 stimulus 实验','',
        f'生成时间：{utc()}。批次状态：`{batch["status"]}`。','',
        '## 1. 与上一版的区别','',
        '- 使用 RQ1 表逐设计对应的 16 次历史成功运行，逐一验证 summary SHA-256、完成状态、覆盖率和 RQ1 CSV 哈希。不是取单一批次，也不是跨运行混用同一设计的 property。',
        '- 冻结每次运行的已接受各轮 property、RTL、Stage-1、重置/时钟和回放检查。HAVEN、模型输出和 RQ1 数据文件均未修改，新增 LLM calls/tokens 为 0。',
        '- 保留每个 property 历史有效刺激的原始顺序；1–4 使用其前缀。先实际合并原始 VDB，逐设计核对 N=4 的全部 coverage bins 与 RQ1 原始运行完全一致，校验失败则不开始扩展。',
        '- 只有历史已达到 4 条的 property 扩展到最多 8 条；历史不足 4 条或不可解的 property 保持原数量，不重试、不补齐。新增刺激追加在已有刺激之后，不替换旧刺激。',
        '- 扩展使用历史后端路径：原来使用普通 JG 的继续软偏好采样，原来接受过四态编码候选的使用相同四态编码后端。初始 prove 使用原生成预算（通常 120s），普通 soft replot 使用原采样预算（通常 30s），编码后端使用原 auxiliary 预算（通常 120s）；每个设计的原参数完整保存在 manifest。',
        '- 普通采样最多获取 8 个新形式候选（与历史样本去重），编码候选预算固定为原每阶段 16 次。首次不能求解就停止该 property，不升级预算或临时切换后端；新增回放拒绝同样停止该 property。',
        '- 所有新增刺激通过原始 LTL 的 native 四态回放才计入覆盖率。采样、回放或实现错误被记录，不能清除历史成功刺激，也不阻塞其他 property/设计。',
        '- 综合代码覆盖率沿用 RQ1：可测 line/cond/toggle/branch/fsm 百分比的算术平均，随后对 16 个设计取均值。不是功能覆盖率。','',
        '## 2. 时间口径（不能把复用写成零成本求解）','',
        '- 这是以历史 N=4 为锚点的保留/扩展实验，不是八次独立从头求解。N=1–4 的覆盖率由已求得的历史样本前缀重新合并，未重新调用求解器。',
        '- `new_solver_seconds` 仅表示本次新增 JG `prove` / `visualize -replot` 的实测累计墙钟时间，包括到当前有效前缀的重复和被拒绝候选；不是总流程时间，不包含仿真、编译和 Yosys 转换。',
        '- N=1–4 的新增求解时间是 0，但原来的求解绝非免费。历史完整 N=4 的原始 goal solve 和 sampling 阶段记录另列，不能据此伪造缺失的 N=1/2/3 完整求解耗时。CSV 的 `cold_end_to_end_solver_seconds` 明确保留空值。',
        '- 未达到请求上限时，计入其本次已执行的全部求解成本。异常导致缺少计时则标缺失，不填 0。共享前缀成本不能当成独立冷启动运行的时间。','',
        '## 3. 完成情况','',
        f'- RQ1 N=4 bins 精确复现：{len(verified)}/{len(designs)} 个设计。',
        f'- 得到全部八档测量：{len(complete)}/{len(designs)} 个设计。',
        f'- Property 数量：{len(all_props)}；状态：`{dict(Counter(p["status"] for p in all_props))}`。',
        f'- 新增 native 回放拒绝：{sum(len(p["rejected"]) for p in all_props)} 条。',
        f'- 本批控制器墙钟时间：{batch["elapsed_seconds"]/60:.2f} 分钟，并发 {batch["jobs"]} 个设计。'
        + (' 这是恢复后的控制器时间，不包括上次中断前的运行时间，不能作为完整冷启动总时间。' if batch.get('continuation') else ''),'',
        '## 4. 平均曲线','',
        '均值只取完整获得八个点的同一设计集合；缺失设计不补值。','',
        table(['每 property 上限','设计数','综合覆盖率 %','有效 stimulus 总数','达到上限的 property','新增累计 JG 求解秒'],
              [[a['cap'],a['designs'],f"{a['coverage']:.4f}",a['actual_stimuli'],a['reaching_cap'],
                '历史复用，未重求解' if a['cap']<=4 else f"{a['new_solver_seconds']:.3f}" if a['new_solver_time_complete'] else '计时缺失，见 CSV'] for a in average]),'',
        '## 5. 各设计覆盖率（%）','']
    rows = []
    for name,d in designs.items():
        cells = {c['count']:c for c in d.get('cells',[])}
        rows.append([name,f"{batch['designs'][name]['expected_coverage']:.4f}"] +
            [f"{cells[k]['coverage']['score']:.4f}" if k in cells else '—' for k in range(1,9)])
    text += [table(['设计','RQ1 N=4']+[str(k) for k in range(1,9)],rows),'',
             '## 6. 各设计新增 JG 求解时间（秒）','']
    rows = []
    for name,d in designs.items():
        cells = {c['count']:c for c in d.get('cells',[])}
        rows.append([name]+[f"{cells[k]['new_solver_seconds']:.3f}" if k in cells and cells[k]['new_solver_time_complete'] else '—' for k in range(5,9)])
    text += [table(['设计','5','6','7','8'],rows),'',
             '## 7. 历史 N=4 的时间记录（独立列示，不与新增列混算）','']
    rows = []
    for name,d in designs.items():
        phases = d.get('historical_phases',{})
        solve = d.get('historical_goal_solve_seconds')
        sampling = phases.get('goal-sampling',{}).get('seconds')
        rows.append([name,f'{solve:.3f}' if solve is not None else '—',
                     f'{sampling:.3f}' if sampling is not None else '—'])
    text += [table(['设计','历史所有 property 初始求解秒（含失败）','历史 sampling 阶段墙钟秒'],rows),'',
        '历史 sampling 阶段还包含启动、分析和导出；不是与本次 prove/replot 同口径的纯求解时间。上述两列未包含完整 native 选择/编码后端成本，不得相加声称完整总求解耗时。','',
        '## 8. 观测','']
    if average:
        first,four,last = average[0],average[3],average[-1]
        text += [f"- N=1→4：{first['coverage']:.4f}% → {four['coverage']:.4f}%；N=4→8：{four['coverage']:.4f}% → {last['coverage']:.4f}%（+{last['coverage']-four['coverage']:.4f} 个百分点）。"]
        for before,after in zip(average,average[1:]):
            text.append(f"- {before['cap']}→{after['cap']}：平均覆盖率 +{after['coverage']-before['coverage']:.4f} 个百分点，实际新增 {after['actual_stimuli']-before['actual_stimuli']} 条刺激。")
        gains = sorted(((d['cells'][7]['coverage']['score']-d['cells'][3]['coverage']['score'],n) for n,d in complete.items()),reverse=True)
        text.append('- 4→8 收益最大的设计：'+'；'.join(f'{n} {gain:+.4f} 个百分点' for gain,n in gains[:5])+'。')
    text += ['- 这是固定历史 property 和已验证刺激的单种子扩展结果，不支持把样本数增加解释成对任意新运行都必然有效。',
             '- 1–4 没有独立冷启动计时，因此不能仅依据本实验判断这四档的完整求解成本最优点。','',
             '## 9. 不足 8 条或失败的 property','']
    if batch.get('continuation'):
        text += ['本批从中断记录恢复：已完成设计保持原结果；已完成 JG 候选和 native 验收刺激经过来源校验后复用。'
                 '复用的扩展求解时间仍按原日志计入曲线，不写成 0。终端中断、被打断且缺少结束计时的求解，'
                 '以及托管环境缺少 Perl 导致的 VCS/UVM 失败，均属于单独的运行开销，不属于 property 不可解。'
                 '原记录保留在上一批目录，各恢复设计的 summary.recovery 和 property.infrastructure_attempt 给出来源。'
                 '这些中断开销没有完整纯求解计时，不能将下表新增求解时间称为实际总支出或独立冷启动耗时。','']
    rows = []
    for name,d in designs.items():
        if batch['designs'][name]['status']!='completed':
            text.append(f'- `{name}`：{batch["designs"][name].get("error",d.get("error","unknown error"))}。')
        for p in d.get('properties',[]):
            if len(p['accepted'])<8:
                sampling = p.get('sampling',{})
                reason = p.get('stop_reason') or p.get('error') or sampling.get('error') or sampling.get('stop_reason') or sampling.get('status','历史不足，未扩展')
                rows.append([name,p['round'],p['label'],p['historical_count'],len(p['accepted']),p['status'],reason])
    text += ['',table(['设计','轮次','property','历史有效数','最终有效数','状态','原因'],rows),'',
             '## 10. 来源与数据','',
             f'- 实验目录：`{root}`。',
             f'- RQ1 CSV：`{batch["rq1"]}`，SHA-256 `{batch["rq1_sha256"]}`。',
             '- `sources.json`：16 个选定运行的路径、SHA-256 和预期覆盖率。',
             '- `design_counts.csv`、`property_counts.csv`、`averages.csv`：逐设计/逐 property/均值数据；历史与新增时间分列。',
             '- 各设计 `reference-four/coverage.json`、`restored-cache.json`、`summary.json`、`manifest.json` 保留核对证据；已有 VDB 来自哈希校验后的原始仿真，新刺激保留求解、原生回放和覆盖数据库。',
             '- 脚本：`experiments/sweep_rq1_stimuli.py`；报告：`experiments/report_rq1_stimuli.py`。','',
             table(['设计','选定原始运行 summary'],[[n,batch['designs'][n]['source']] for n in designs]),'']
    (root/'report.md').write_text('\n'.join(text))
    print(json.dumps(dict(report=str(root/'report.md'),verified=len(verified),completed=len(complete),averages=average),ensure_ascii=False))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root',type=Path)
    report(parser.parse_args().root.resolve())
