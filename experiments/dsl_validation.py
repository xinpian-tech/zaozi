"""Early checks for cross-field DSL mistakes that otherwise reach the SV compiler."""
import re


def validate_native_interface(dsl):
    """Never silently filter a request for RVProbe-private replay storage."""
    def visit(value):
        if isinstance(value, dict):
            for key, child in value.items():
                if key not in ('description', 'name', 'module_name', 'message'):
                    visit(child)
        elif isinstance(value, list):
            for child in value:
                visit(child)
        elif isinstance(value, str) and re.search(r'\brvp_\w+\b', value):
            raise ValueError('HAVEN native DSL cannot access RVProbe-private raw replay fields; use the declared native transaction/BFM API')
    visit(dsl.model_dump())


def validate_storage(dsl):
    errors=[]
    for seq in dsl.model_dump()['sequences']:
        declarations=[p['name'] for p in seq.get('locals',[])]+[p['name'] for p in seq.get('params',[])]
        if len(declarations)!=len(set(declarations)):
            errors.append(f"{seq['name']}: duplicate local/parameter declaration")
        for step in [*seq.get('init_steps',[]),*seq.get('steps',[])]:
            if step['type'] not in ('register_read','poll'): continue
            store=step['store']; match=re.match(r'^([A-Za-z_]\w*)(?:\s*\[[^]]+\])?$',store)
            if not match or match[1] not in declarations:
                errors.append(f"{seq['name']}/{step['name']}: store={store!r} is not a declared local/parameter. Declare its type and width in locals before using it; do not remove the read/check.")
    if errors: raise ValueError('\n'.join(errors))
