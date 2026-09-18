"""Reuse only this run's previously returned, byte-validated RTL reads."""
import hashlib
import json
from pathlib import Path

INLINE_LIMIT = 48000
PACKET_INLINE_LIMIT = 32768


def index(ranges):
    """Expose only verified range metadata; bodies remain available on demand.

    Compact dialogues should not resend old RTL bodies on every new coverage
    round.  The exact ranges are retained in the run artifact and can be
    fetched with read_context(topic=rtl_history) or read_rtl at their offsets.
    """
    return {'policy':'same-run-verified-rtl-index-v1', 'inline':False,
            'ranges':[{k:v for k,v in row.items() if k!='text'} for row in ranges],
            'characters':sum(len(row.get('text','')) for row in ranges),
            'details':'Bodies remain available through read_context(topic=rtl_history) or read_rtl; request only missing ranges.'}


def project_inline(ranges, limit=PACKET_INLINE_LIMIT):
    """Return a deterministic recent source window for a stateless request.

    ``ranges`` has already been validated against the frozen RTL.  The complete
    merged ranges remain in the run artifact and in ``read_context``; this
    projection only bounds repeated prompt size.  The newest observed ranges are
    preferred because they correspond to the current model lookup.  A partial
    range retains its source offset/hash so the model can request the omitted
    prefix or suffix explicitly.
    """
    if type(limit) is not int or limit < 1:
        raise ValueError('inline RTL limit must be positive')
    total = sum(len(row.get('text', '')) for row in ranges)
    if total <= limit:
        return ranges
    remaining = limit
    selected = []
    for row in reversed(ranges):
        if remaining <= 0:
            break
        text = row.get('text', '')
        if not text:
            continue
        if len(text) <= remaining:
            selected.append(dict(row))
            remaining -= len(text)
        else:
            # Keep the tail of the newest range.  Line numbers in the source
            # text remain intact; ``omitted_prefix`` is explicit metadata.
            start = row['offset'] + len(text) - remaining
            selected.append({**row, 'offset': start, 'text': text[-remaining:],
                             'omitted_prefix': len(text) - remaining})
            remaining = 0
    selected.sort(key=lambda row: (row['file_id'], row['offset']))
    return selected


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
    # Keep an exact prefix instead of dropping all bodies at the size boundary.
    # Full indices and continuation offsets preserve access to the omitted tail.
    remaining = INLINE_LIMIT
    visible = []
    for row in ranges:
        if remaining <= 0:
            break
        text = row['text'][:remaining]
        end = row['offset'] + len(text)
        visible.append({**row, 'text':text, 'end_offset':end,
                        'next_offset':end if end < row['end_offset'] else None})
        remaining -= len(text)
    return {'policy':'same-run-verified-rtl-v2','inline':False,
        'ranges':[{k:v for k,v in row.items() if k!='text'} for row in ranges],
        'inline_ranges':visible, 'inline_text_characters':INLINE_LIMIT-remaining,
        'characters':size,'details':'Reuse inline_ranges; read_rtl at next_offset for a needed continuation, or read_context(topic=rtl_history) for all prior text. The full range index is retained.'}
