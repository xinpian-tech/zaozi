"""Bounded diagnostic projection; full compiler evidence remains readable."""
import hashlib
import json
import re


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
    selected=(syntax or unique)[:8]
    return {'diagnostics':selected,'total_diagnostics':len(rows),
        'unique_diagnostics':len(unique),'omitted_from_initial':len(unique)-len(selected),
        'selection':'syntax-first' if syntax else 'compiler-order',
        'full_report_sha256':hashlib.sha256(raw.encode()).hexdigest(),
        'full_report_access':'read_diagnostics; projection is not the complete report'}
