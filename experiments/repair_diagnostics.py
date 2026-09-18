"""Bounded diagnostic projection; full compiler evidence remains readable."""
import hashlib
import json
import re

MAX_PROJECTED_MESSAGE_CHARS = 4000


def project(errors):
    raw=json.dumps(errors,ensure_ascii=False,separators=(',',':'))
    rows=errors if isinstance(errors,list) else [errors]
    unique=[];seen=set()
    for row in rows:
        identity=json.dumps(row,sort_keys=True,ensure_ascii=False)
        if identity not in seen:unique.append(row);seen.add(identity)
    # Parser errors can cause misleading member/type errors downstream. Do not
    # infer a fix or discard the complete report; make it available on demand.
    syntax=[r for r in unique if isinstance(r,dict) and (r.get('kind')=='Syntax Error' or
        re.search(r'(?m)^\s*(?:\|\s*)?unclosed (?:quoted identifier|string literal|character literal|comment)\s*$',r.get('message','')))]
    selected=[]
    for row in (syntax or unique)[:8]:
        if isinstance(row,dict):
            item=dict(row)
            # Keep the actionable prefix in the repair prompt; the exact report
            # remains archived and is available through read_diagnostics.
            message=item.get('message')
            if isinstance(message,str) and len(message)>MAX_PROJECTED_MESSAGE_CHARS:
                item['message']=message[:MAX_PROJECTED_MESSAGE_CHARS]+'\n[diagnostic tail omitted; use read_diagnostics]'
            selected.append(item)
        else:
            selected.append(row)
    return {'diagnostics':selected,'total_diagnostics':len(rows),
        'unique_diagnostics':len(unique),'omitted_from_initial':len(unique)-len(selected),
        'selection':'syntax-first' if syntax else 'compiler-order',
        'full_report_sha256':hashlib.sha256(raw.encode()).hexdigest(),
        'full_report_access':'read_diagnostics; projection is not the complete report'}
