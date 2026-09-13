"""Detect legacy item scenarios that restrict the public stimulus fields."""
import re


def sequence_fields(blueprint):
    mapping=(blueprint.get('protocol_flows') or {}).get('bus_field_mapping') or {}
    contract=blueprint.get('transaction_contract') or {}
    if contract.get('randomizable'):
        return set(contract['randomizable'])
    if all(mapping.get(k) for k in ('cyc','stb','ack','addr')):
        return {mapping[k] for k in ('addr','data','we') if mapping.get(k)}
    environment=blueprint.get('environment_contract') or {}
    excluded=set(environment.get('bfm_owned',{}))|set(environment.get('infrastructure_owned',{}))
    excluded|={blueprint['clock']['port'],blueprint['reset']['name']}
    return {p['name'] for p in blueprint['io_specification'].get('inputs',[])}-excluded


def scenario_constraints(code, fields):
    # Preserve offsets while removing comments/strings from lexical inspection.
    clean=re.sub(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"',lambda m:' '*len(m[0]),code,flags=re.S)
    result=[]
    for match in re.finditer(r'\bconstraint\s+(\w+)\s*\{',clean):
        depth=1; end=match.end()
        while depth and end<len(clean):
            depth += (clean[end]=='{')-(clean[end]=='}'); end+=1
        if depth: raise ValueError('unterminated item constraint: '+match[1])
        touched=set(re.findall(r'\b[A-Za-z_]\w*\b',clean[match.end():end]))&set(fields)
        if touched:
            result.append(dict(name=match[1],fields=sorted(touched),start=match.start(),end=end,
                               source=code[match.start():end]))
    return result


def normalize(code, blueprint):
    removed=scenario_constraints(code,sequence_fields(blueprint))
    for block in reversed(removed):
        code=code[:block['start']]+code[block['end']:]
    return code,removed
