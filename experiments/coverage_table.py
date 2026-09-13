"""Lossless columnar coverage evidence; no design-specific selection or stimulus."""
from copy import deepcopy
from itertools import groupby
import json


def pack(rows):
    groups=[]
    for keys,items in groupby(rows,key=lambda r:tuple(sorted(r)) if isinstance(r,dict) else None):
        items=list(items)
        if keys is None:
            groups.append({'raw':deepcopy(items)});continue
        common={k:items[0][k] for k in keys if all(
            json.dumps(r[k],sort_keys=True)==json.dumps(items[0][k],sort_keys=True) for r in items)}
        columns=[k for k in keys if k not in common]
        groups.append({'common':deepcopy(common),'columns':columns,
                       'rows':[[deepcopy(r[k]) for k in columns] for r in items]})
    return {'encoding':'columnar-gaps-v1','count':len(rows),'groups':groups}


def unpack(table):
    if table['encoding']!='columnar-gaps-v1':raise ValueError('unknown coverage encoding')
    result=[]
    for group in table['groups']:
        if 'raw' in group:result.extend(deepcopy(group['raw']));continue
        for row in group['rows']:
            if len(row)!=len(group['columns']):raise ValueError('coverage row width mismatch')
            result.append({**deepcopy(group['common']),**dict(zip(group['columns'],deepcopy(row)))})
    if len(result)!=table['count']:raise ValueError('coverage row count mismatch')
    return result
