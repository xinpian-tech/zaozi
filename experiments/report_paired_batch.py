"""Read-only experiment aggregation. Terminal failures are not successful pairs."""
import argparse
import json
from pathlib import Path
import re
from run_records import save, totals, utc


def read(path):
    return json.loads(path.read_text()) if path.is_file() else {}


def run_record(path):
    directory = path/'paired'
    source = directory/('summary.json' if (directory/'summary.json').is_file() else 'progress.json')
    record = read(source)
    arms = {}
    for arm in ('haven','rvprobe'):
        data = record.get('arms',{}).get(arm,{})
        cost = data.get('costs') or totals(directory/arm)
        accepted = data.get('rounds',[])
        last = data.get('final') or (accepted[-1]['coverage'] if accepted else record.get('baseline',{}))
        arms[arm] = {'status':data.get('status','not_started'), 'accepted_rounds':len(accepted),
            'sequence_count':data.get('sequence_count'),
            'percent':last.get('percent'), 'score':last.get('score'),
            'tokens':cost.get('usage_reported',{}).get('total_tokens'),
            'token_accounting_complete':cost.get('token_accounting_complete'),
            'requests_without_usage':cost.get('requests_without_usage'),
            'elapsed_seconds':data.get('elapsed_seconds'), 'stop_reason':data.get('stop_reason'),
            'error':data.get('error'), 'source':str(source)}
    return {'run':str(path),'status':record.get('status',read(path/'progress.json').get('status','not_started')),
        'terminal_pair':all(a['status'] in ('completed','failed') for a in arms.values()),
        'successful_pair':all(a['status']=='completed' for a in arms.values()),
        'baseline':record.get('baseline',{}).get('percent'), 'arms':arms,
        'elapsed_seconds':record.get('elapsed_seconds'), 'error':record.get('error')}


def collect(root, date, designs):
    selected, attempts, setup = [],[],[]
    for number in designs:
        runs=sorted(root.glob(f'design{number}-*-complete-pair-{date}-v*'),
                    key=lambda p:int(re.search(r'-v(\d+)$',p.name)[1]))
        rows=[run_record(p) for p in runs]
        attempts.extend({'design':number,**r} for r in rows)
        selected.append({'design':number,**(rows[-1] if rows else {'status':'not_started','arms':{},'successful_pair':False,'terminal_pair':False})})
        for p in root.glob(f'design{number}-*{date}*/stage1-costs.json'):
            r=read(p); correction=read(p.parent/'usage-correction.json')
            usage=correction or r.get('native_token_tracker',{}).get('total',{})
            setup.append({'design':number,'source':str(p),'status':r.get('status'),
                'tokens':usage.get('total_tokens'),'elapsed_seconds':r.get('elapsed_seconds'),
                'usage_correction':str(p.parent/'usage-correction.json') if correction else None,
                'error':r.get('error')})
        for p in root.glob(f'design{number}-*repair-{date}-*/progress.json'):
            r=read(p)
            if 'source_bundle' not in r or 'driver' not in r:
                continue
            usage=r.get('costs') or totals(p.parent)
            setup.append({'design':number,'source':str(p),'status':r.get('status'),
                'tokens':usage.get('usage_reported',{}).get('total_tokens'),
                'elapsed_seconds':r.get('elapsed_seconds'),
                'token_accounting_complete':usage.get('token_accounting_complete'), 'error':r.get('error')})
    known=sum(a.get('tokens') or 0 for r in attempts for a in r['arms'].values())+sum(s.get('tokens') or 0 for s in setup)
    return {'updated_utc':utc(),'scope':'specified-date attempts only; earlier setup/run costs are not included',
        'successful_pairs':sum(r['successful_pair'] for r in selected),
        'terminal_pairs':sum(r['terminal_pair'] for r in selected),
        'selected':selected,'all_attempts':attempts,'shared_setup_attempts':setup,
        'known_tokens_all_attempts_and_setup':known,
        'note':'running requests may have unknown additional usage; failed-arm coverage is only its last accepted state, not a successful final result',
        'timing_caveat':'designs ran concurrently on the shared host; wall times include contention and differing sequence counts, not a controlled isolated latency benchmark'}


def markdown(record):
    lines=['# 设计 6–10 配对实验记录','',f"更新时间：{record['updated_utc']}。成功配对 {record['successful_pairs']}/5；双侧已终止 {record['terminal_pairs']}/5（包含失败）。",'',
        '覆盖率按行／条件／翻转／分支排列，均为顶层百分比。失败侧只列已接受状态；0 轮表示没有接受新增候选。', '',
        '| 设计 | 侧 | 状态 | 接受轮数 | 覆盖率（%） | 已报告 tokens | 侧耗时（秒） |',
        '| --- | --- | --- | ---: | --- | ---: | ---: |']
    names={6:'UART',7:'CAN',8:'Ethernet',9:'I2C',10:'GPIO'}
    for row in record['selected']:
        for arm in ('haven','rvprobe'):
            a=row['arms'].get(arm,{})
            pct=a.get('percent') or {}
            coverage=' / '.join(f'{pct[k]:.2f}' if k in pct else '—' for k in ('line','cond','toggle','branch'))
            elapsed=a.get('elapsed_seconds'); tokens=a.get('tokens')
            lines.append(f"| {row['design']} {names.get(row['design'],'')} | {arm} | {a.get('status',row['status'])} | {a.get('accepted_rounds',0)} | {coverage} | {tokens if tokens is not None else '未知'} | {elapsed:.2f} |" if elapsed is not None else
                f"| {row['design']} {names.get(row['design'],'')} | {arm} | {a.get('status',row['status'])} | {a.get('accepted_rounds',0)} | {coverage} | {tokens if tokens is not None else '未知'} | 未结束／未启动 |")
    lines+=['',f"本日期全部配对尝试及共享 setup 已知费用合计：{record['known_tokens_all_attempts_and_setup']:,} tokens。包含失败与被替代尝试；不包含之前日期的费用。未结束请求可能增加费用。",'',
        '多设计曾并行运行。耗时包含资源竞争及不同的序列数量，不应直接解释为隔离条件下的工具速度比。', '',
        '## 运行目录','']
    for row in record['selected']:
        lines.append(f"- 设计 {row['design']}：`{row.get('run','尚无配对目录')}`；{row.get('error') or row['status']}")
        for arm,a in row['arms'].items():
            if a.get('error'): lines.append(f"  - {arm}：{a['error']}")
    lines+=['','全部原始尝试、共享 setup、费用修正和错误保存在同名 JSON 中。未用成功重跑覆盖失败记录。',
        '', '解释和已知限制见 [本批实验边界](design6-10-current-limitations.md)。','']
    return '\n'.join(lines)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,default=Path('out/experiments'))
    parser.add_argument('--date',required=True)
    parser.add_argument('--designs',type=int,nargs='+',default=[6,7,8,9,10])
    parser.add_argument('--out',type=Path,required=True)
    args=parser.parse_args()
    record=collect(args.root,args.date,args.designs)
    save(args.out.with_suffix('.json'),record)
    args.out.with_suffix('.md').write_text(markdown(record))
    print(json.dumps({k:record[k] for k in ('updated_utc','successful_pairs','terminal_pairs','known_tokens_all_attempts_and_setup')}))
