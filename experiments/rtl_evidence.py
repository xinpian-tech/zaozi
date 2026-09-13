"""Reuse only this run's previously returned, byte-validated RTL reads."""
import hashlib
import json
from pathlib import Path

INLINE_LIMIT = 48000


def collect(run, before_round):
    rows = []
    for number in range(1, before_round):
        for path in sorted((Path(run)/f'round-{number}'/'generation').glob('attempt-*/task-tool-*.json')):
            record = json.loads(path.read_text())
            name = record['tool_call']['function']['name']
            result = record['result']
            if not isinstance(result, dict) or 'error' in result:
                continue
            if name == 'read_rtl':
                pieces = [result]
            elif name == 'read_rtl_batch':
                pieces = result['ranges']
            else:
                continue
            for piece in pieces:
                if piece.get('text'):
                    rows.append({key: piece[key] for key in ('file_id','sha256','offset','text')} |
                        {'source_round': number, 'tool_sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
    return rows


def validate_and_merge(files, rows):
    """No unseen neighboring lines, model summaries or historical candidates."""
    intervals = []
    for row in rows:
        if not isinstance(row,dict) or row.get('file_id') not in files:
            raise ValueError('unknown file in previous RTL evidence')
        entry=files[row['file_id']]
        start=row.get('offset');text=row.get('text')
        if (type(start) is not int or not 0<=start<=len(entry['text']) or not isinstance(text,str)
                or row.get('sha256')!=entry['sha256']
                or entry['text'][start:start+len(text)]!=text):
            raise ValueError('previous RTL evidence does not match frozen source')
        if text:
            intervals.append((row['file_id'],start,start+len(text)))
    merged=[]
    for file_id,start,end in sorted(intervals):
        if merged and merged[-1]['file_id']==file_id and start<=merged[-1]['end_offset']:
            merged[-1]['end_offset']=max(merged[-1]['end_offset'],end)
        else:
            merged.append({'file_id':file_id,'offset':start,'end_offset':end})
    for row in merged:
        entry=files[row['file_id']]
        row.update(sha256=entry['sha256'],text=entry['text'][row['offset']:row['end_offset']])
    return merged


def initial(ranges):
    """Bound initial context without silently discarding previous evidence."""
    size=len(json.dumps(ranges,ensure_ascii=False,separators=(',',':')))
    if size<=INLINE_LIMIT:
        return {'policy':'same-run-verified-rtl-v1','inline':True,'ranges':ranges,
            'instruction':'These exact RTL ranges were already requested by you in earlier coverage rounds. Reuse them; read only missing or additional ranges as needed.'}
    return {'policy':'same-run-verified-rtl-v1','inline':False,
        'ranges':[{k:v for k,v in row.items() if k!='text'} for row in ranges],
        'characters':size,'details':'read_context(topic=rtl_history) for all prior read text, or read_rtl for a needed range; no evidence is silently removed'}
