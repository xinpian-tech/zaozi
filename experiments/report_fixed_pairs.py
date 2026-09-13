"""Aggregate paired outcomes, retaining rejected attempts and their reported costs."""
import argparse
import csv
import json
from pathlib import Path
from run_records import save, totals, utc


def inspect(path,archive_path=None):
    path=Path(path)
    if not path.is_file(): path=path.with_name('progress.json')
    if not path.is_file() and path.parent.name=='paired':
        path=path.parent.parent/'progress.json'
    if not path.is_file() and archive_path is not None:
        archived=inspect(archive_path)
        archived['archive_source']=str(archive_path)
        return archived
    record=json.loads(path.read_text()) if path.is_file() else {}
    arms={}
    for name in ('haven','rvprobe'):
        arm=record.get('arms',{}).get(name,{})
        incremental='new_costs' in arm and 'costs' not in arm
        costs=arm.get('costs') or arm.get('new_costs') or totals(path.parent/name)
        incremental = incremental or 'prior_generation_usage' in costs
        final=arm.get('final') or {}
        arms[name]=dict(status=arm.get('status','not_started'),rounds=len(arm.get('rounds',[])),
                        tokens=costs.get('usage_reported',{}).get('total_tokens'),
                        token_scope='continuation_only' if incremental else 'listed_arm_attempt',
                        token_accounting_complete=costs.get('token_accounting_complete'),
                        elapsed_seconds=arm.get('elapsed_seconds'),percent=final.get('percent'),
                        coverage_score=final.get('score'),coverage_metrics=list((final.get('percent') or {}).keys()),
                        started_utc=arm.get('started_utc'),finished_utc=arm.get('finished_utc'),
                        error=arm.get('error'),stop_reason=arm.get('stop_reason'),
                        reported_models=costs.get('reported_models'))
    return dict(source=str(path),status=record.get('status','not_started'),arms=arms,
                complete=all(a['status']=='completed' for a in arms.values()),
                terminal=not record.get('diagnostic_only', False) and
                    all(a['status'] in ('completed','failed') for a in arms.values()),
                archive_source=str(archive_path) if archive_path else None,
                error=record.get('error'))


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--manifest',type=Path,required=True); p.add_argument('--out',type=Path,required=True)
    args=p.parse_args(); manifest=json.loads(args.manifest.read_text()); rows={}
    for name,paths in manifest['attempts'].items():
        attempts=[]
        for path in paths:
            archive=None
            if manifest.get('working_root') and manifest.get('archive_root'):
                try:
                    archive=Path(manifest['archive_root'])/Path(path).relative_to(manifest['working_root'])
                except ValueError:
                    pass
            attempts.append(inspect(path,archive))
        complete=[a for a in attempts if a['complete']]
        started=[a for a in attempts if a['status']!='not_started']
        rows[name]=dict(attempts=attempts,selected=complete[-1] if complete else (started or attempts)[-1],
                       listed_attempt_tokens=sum(a.get('tokens') or 0 for run in attempts for a in run['arms'].values()))
    result=dict(updated_utc=utc(),complete_pairs=sum(r['selected']['complete'] for r in rows.values()),
                terminal_pairs=sum(r['selected']['terminal'] for r in rows.values()),
                total_designs=len(rows),designs=rows,
                listed_attempt_tokens=sum(r['listed_attempt_tokens'] for r in rows.values()),
                scope='Listed attempts only; historical setups and unlisted failed trials excluded from aggregate costs',
                timing='Concurrent host runs include resource contention; not an isolated speed benchmark',
                cohort=manifest.get('cohort','Historical completed pairs and new fixed-Stage-1 pairs; per-run framework hashes retained'),
                selection_policy='Latest completed pair if available, otherwise latest started attempt; all listed attempts retained')
    save(args.out.with_suffix('.json'),result)
    lines=['# 16 个设计配对进度','',f"更新时间：{result['updated_utc']}；已结束配对（含失败） {result['terminal_pairs']}/{len(rows)}；双方成功 {result['complete_pairs']}/{len(rows)}。",'',
           '已结束配对包括成功、输出截断、轮询超时、预算内求解/采样不足等终态；这些是实验结果，不因其本身自动重跑。双方成功仅统计两侧均 completed，不改写原 failed 状态。离线诊断不计正式结果。',
           '批次口径：'+result['cohort'],'',
           '每设计选最近完整配对；没有完整配对则选最新已启动尝试。失败侧覆盖为最后已接受结果（零接受轮为基线），token 含该次失败消耗。所有列入的历史尝试保留在 JSON。','',
           '每格为 HAVEN / RVProbe。综合覆盖率为本次报告中有效覆盖指标的等权平均，不是各设计覆盖 bin 混合统计；— 表示缺失。',
           '轮次为已接受的覆盖迭代数；时间为各侧流程墙钟耗时，含模型、编译、求解和仿真。','',
           '| 设计 | 状态 H / R | 综合覆盖率 % H / R | Token H / R | 轮次 H / R | 分钟 H / R |',
           '| --- | --- | ---: | ---: | ---: | ---: |']
    csv_rows=[]
    for name,row in rows.items():
        arms=row['selected']['arms']; h,r=arms['haven'],arms['rvprobe']
        def token_cell(arm):
            value='—' if arm['tokens'] is None else str(arm['tokens'])
            return value+('†' if arm['token_scope']=='continuation_only' else '')
        def number(value, divisor=1):
            return '—' if value is None else f'{value/divisor:.2f}'
        lines.append(f"| {name} | {h['status']} / {r['status']} | {number(h['coverage_score'])} / {number(r['coverage_score'])} | {token_cell(h)} / {token_cell(r)} | {h['rounds']} / {r['rounds']} | {number(h['elapsed_seconds'],60)} / {number(r['elapsed_seconds'],60)} |")
        for arm,value in arms.items():
            csv_rows.append({'design':name,'arm':arm,'pair_complete':row['selected']['complete'],
                'status':value['status'],'coverage_score_percent':value['coverage_score'],
                'coverage_metrics':','.join(value['coverage_metrics']),'tokens':value['tokens'],
                'token_scope':value['token_scope'],'token_accounting_complete':value['token_accounting_complete'],
                'accepted_rounds':value['rounds'],'elapsed_seconds':value['elapsed_seconds'],
                'started_utc':value['started_utc'],'finished_utc':value['finished_utc'],
                'stop_reason':value['stop_reason'],'error':value['error'],
                'reported_models':','.join(value['reported_models'] or []),
                'source':row['selected']['source']})
    lines+=['','† 仅恢复续跑新增费用，不含复用的历史模型输出，不能作为完整方法成本比较。',
            '耗时、覆盖率、失败原因、原始路径和所有列入尝试见同名 JSON。进行中的 tokens 只反映已返回用量。','']
    args.out.with_suffix('.md').write_text('\n'.join(lines))
    with args.out.with_suffix('.csv').open('w',newline='',encoding='utf-8-sig') as stream:
        writer=csv.DictWriter(stream,fieldnames=list(csv_rows[0]) if csv_rows else ['design','arm'])
        writer.writeheader();writer.writerows(csv_rows)
    print(json.dumps({k:result[k] for k in ('updated_utc','terminal_pairs','complete_pairs','total_designs')}))


if __name__=='__main__': main()
