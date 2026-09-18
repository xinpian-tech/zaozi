"""Reproducible usage/coverage audit. Never infer prices or absent usage as zero."""
import argparse
import csv
import io
import json
from pathlib import Path

from run_records import save, utc


def usage_detail(costs):
    usage=costs.get('usage_reported',{})
    detail=costs.get('usage_breakdown',{})
    def value(key):return detail.get(key,{}).get('reported_tokens')
    hit=value('prompt_cache_hit_tokens')
    if hit is None:hit=value('cached_tokens')
    miss=value('prompt_cache_miss_tokens')
    reasoning=value('reasoning_tokens')
    output=usage.get('completion_tokens')
    # Output-minus-reasoning requires complete, same-population usage.
    complete=bool(costs.get('token_accounting_complete'))
    reasoning_complete=bool(detail.get('reasoning_tokens',{}).get('complete'))
    nonreasoning=(output-reasoning if complete and reasoning_complete and
                  output is not None and reasoning is not None else None)
    if nonreasoning is not None and nonreasoning<0:raise ValueError('reasoning exceeds output')
    cache_complete=(complete and detail.get('prompt_cache_hit_tokens',detail.get('cached_tokens',{})).get('complete',False)
                    and detail.get('prompt_cache_miss_tokens',{}).get('complete',False))
    if cache_complete and hit is not None and miss is not None and hit+miss!=usage.get('prompt_tokens'):
        raise ValueError('cache hit/miss do not partition prompt usage')
    return dict(input_tokens=usage.get('prompt_tokens'),cache_hit_tokens=hit,cache_miss_tokens=miss,
        output_tokens=output,reasoning_tokens=reasoning,nonreasoning_output_tokens=nonreasoning,
        total_tokens=usage.get('total_tokens'),requests=costs.get('requests'),
        usage_complete=complete,cache_complete=bool(cache_complete),reasoning_complete=reasoning_complete,
        cache_hit_fraction=(hit/(hit+miss) if cache_complete and hit is not None and miss is not None and hit+miss else None),
        usd=None,usd_note='Unknown: actual provider rates and time-dependent discounts not supplied')


def measure(source, method, design, cohort):
    data=json.loads(Path(source).read_text())
    arm=data.get('arms',{}).get(method,data)
    baseline=data.get('baseline') or arm['baseline']
    current=baseline;curve=[dict(round=0,coverage=baseline['score'],added_sequences=0)]
    valid=0
    for r in arm.get('rounds',[]):
        added=r.get('added_sequences',0)
        if added and r.get('status')!='no_validated_sequences':
            current=r['coverage'];valid+=1
        curve.append(dict(round=r['round'],coverage=current['score'],added_sequences=added,
                          score_gain=current['score']-curve[-1]['coverage']))
    if abs(current['score']-arm['final']['score'])>1e-8:
        raise ValueError('final coverage differs from last accepted result: '+str(source))
    costs=arm.get('costs',data.get('costs',{}))
    return dict(design=design,method=method,cohort=cohort,status=arm['status'],
        baseline=baseline['score'],coverage=current['score'],valid_rounds=valid,
        stage2_seconds=arm.get('elapsed_seconds') if method in ('haven','rvprobe') else data.get('stage2_seconds'),
        total_seconds=data.get('elapsed_seconds'),source=str(source),
        phases=costs.get('phases',{}),curve=curve,**usage_detail(costs))


def export(reference,cohort,out):
    reference=Path(reference);cohort=Path(cohort);out=Path(out)
    out.mkdir(parents=True,exist_ok=False)
    old=json.loads(reference.read_text())
    rows=[measure(r['summary'],r['method'],r['design'],'original') for r in old['rows']]
    batch=json.loads((cohort/'progress.json').read_text())
    for design in batch['designs']:
        rows.append(measure(cohort/design/'flow/paired/summary.json','rvprobe',design,cohort.name))
    groups={}
    for row in rows:
        key=row['cohort']+'/'+row['method']
        groups.setdefault(key,[]).append(row)
    aggregates=[]
    token_fields=('input_tokens','cache_hit_tokens','cache_miss_tokens','output_tokens',
                  'reasoning_tokens','nonreasoning_output_tokens','total_tokens')
    for key,group in groups.items():
        sums={field:sum(r[field] for r in group if r[field] is not None)
              if any(r[field] is not None for r in group) else None for field in token_fields}
        aggregates.append(dict(group=key,designs=len(group),completed=sum(r['status']=='completed' for r in group),
            mean_coverage=sum(r['coverage'] for r in group)/len(group),
            usage_complete=all(r['usage_complete'] for r in group),
            cache_complete=all(r['cache_complete'] for r in group),
            nonreasoning_complete=all(r['nonreasoning_output_tokens'] is not None for r in group),
            usd=None,**sums))
    repairs=[]
    for path in sorted(cohort.glob('*/flow/paired/rvprobe/round-*/generation/summary.json')):
        data=json.loads(path.read_text());history=data.get('history',[])
        if data.get('error') or any(not h.get('ok') for h in history):
            repairs.append(dict(source=str(path),status=data.get('status'),error=data.get('error'),history=history))
    result=dict(updated_utc=utc(),reference=str(reference),cohort=str(cohort),
        generation_options=batch['rvprobe_generation_options'],rows=rows,aggregates=aggregates,repair_records=repairs,
        notes=['Partial usage is a reported subtotal, not zero. Cache aliases are not added together.',
               'Reasoning is part of output, never add it to total again. USD remains unknown.',
               'Phase timings may nest or be absent; do not sum raw phases or label missing phases zero.',
               'No independent intent audit. Successful source repair is not proof of intended semantics.',
               'Coverage uses last accepted simulation, or baseline when no additions were accepted.'])
    save(out/'usage-detail.json',result)
    scalar=[{k:v for k,v in r.items() if k not in ('curve','phases')} for r in rows]
    stream=io.StringIO();writer=csv.DictWriter(stream,fieldnames=list(scalar[0]))
    writer.writeheader();writer.writerows(scalar);(out/'usage-detail.csv').write_text(stream.getvalue())
    stream=io.StringIO();writer=csv.DictWriter(stream,fieldnames=['cohort','method','design','round','coverage','added_sequences','score_gain'])
    writer.writeheader()
    for row in rows:
        for point in row['curve']:writer.writerow({**{k:row[k] for k in ('cohort','method','design')},**point})
    (out/'coverage-curves.csv').write_text(stream.getvalue())
    def n(value):return '未知' if value is None else f'{value:,}'
    lines=['# Token、缓存与覆盖率明细','',
        '缓存命中仍包含在输入 token 中；推理 token 已包含在输出中。USD 未填，等待实际服务商费率。',
        '† 表示部分用量缺失，数值是已报告小计；非推理输出只在对应输入数据完整时计算。','',
        '| 批次／方法 | 正常/总数 | 覆盖率% | 命中输入 | 未命中输入 | 输出（含推理） | 其中推理 | 总 tokens |',
        '|---|---:|---:|---:|---:|---:|---:|---:|']
    for a in aggregates:
        lines.append(f"| {a['group']}{'' if a['usage_complete'] and a['cache_complete'] else ' †'} | {a['completed']}/{a['designs']} | {a['mean_coverage']:.2f} | {n(a['cache_hit_tokens'])} | {n(a['cache_miss_tokens'])} | {n(a['output_tokens'])} | {n(a['reasoning_tokens'])} | {n(a['total_tokens'])} |")
    lines+=['','## 新 RVProbe 各设计','',
        '| 设计 | 状态 | 命中输入 | 未命中输入 | 推理输出 | 非推理输出 | 输入命中率 |',
        '|---|---|---:|---:|---:|---:|---:|']
    for row in rows:
        if row['cohort']=='original':continue
        hit='未知' if row['cache_hit_fraction'] is None else f"{row['cache_hit_fraction']:.1%}"
        lines.append(f"| {row['design']} | {row['status']} | {n(row['cache_hit_tokens'])} | {n(row['cache_miss_tokens'])} | {n(row['reasoning_tokens'])} | {n(row['nonreasoning_output_tokens'])} | {hit} |")
    lines+=['','## 附件口径','',
        '- usage-detail.json：完整统计、原始 phase 时间、失败/修复记录及来源路径。',
        '- usage-detail.csv：80 个单元的用量与时间明细；coverage-curves.csv：按覆盖轮次的曲线数据。',
        '- phase 可能嵌套，某些历史续跑缺失 phase；当前不把它们伪装成完整、不重叠的时间分解。',
        '- 暂未导出按累计时间的曲线：需进一步对齐各轮仿真完成时间与嵌套/并行事件。',
        '- 不改变任何原实验记录、不重新调用模型。','']
    (out/'usage-detail.md').write_text('\n'.join(lines))
    return result


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference',required=True,type=Path)
    parser.add_argument('--cohort',required=True,type=Path)
    parser.add_argument('--out',required=True,type=Path)
    args=parser.parse_args();result=export(args.reference,args.cohort,args.out)
    print(json.dumps(result['aggregates']))
