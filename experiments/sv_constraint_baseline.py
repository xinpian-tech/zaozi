"""Restricted SV constraint clauses over finite DUT-input arrays, sampled by VCS.

Only expressions/constraint constructs pass the lexical boundary. The model
cannot declare classes, functions, callbacks, tasks, clock/reset or a DUT model.
"""
import re
import shlex
import subprocess
from pathlib import Path
from run_records import Records, save
from rvprobe.backend.process import run

API='''Return JSON only, or STOP:
{"intents":[{"label":"unique_name","intent":"behavior to exercise",
"cycles":8,"constraints":"foreach (req[i]) { req[i] == (i == 0); }"}]}.
Select 1..4 intents. For each choose a finite horizon cycles (1..4096).
The framework declares every writable data input as rand bit [WIDTH-1:0]
INPUT_NAME[cycles]. Array element i drives that input for primary-clock cycle i
after the same fixed reset. All input elements are randomized together, so
cross-cycle and cross-input relations are allowed; unconstrained elements are
random, not idle. Each sample is replayed independently. Fixed clocks, reset,
secondary reset and static pins are not random variables. Outputs and internal
DUT state cannot be referenced: randomize() does not solve DUT transitions.
Return ONLY SV constraint block CONTENT, not class/constraint declarations.
Use indexed input arrays, unsigned sized constants, comparisons, arithmetic,
bit operations, inside ranges, implication ->, if/else, foreach with indices
i/j/k, unique, dist, soft, solve/before. int'/longint' casts are allowed.
Array reductions .sum()/.product()/.and()/.or()/.xor() with (item...) are allowed.
No procedural statements, functions, helpers, callbacks, dynamic allocation,
strings, macros, system functions, hierarchical access, clock/reset assignments,
or model-authored DUT. The trusted wrapper performs four randomize() samples
per intent with a fixed seed and checks each result. Use finite input sequence
constraints, not SVA or predicted output values. The req example above is only
a syntax illustration, not a recommended DUT transaction.
'''

class ConstraintError(ValueError):pass


def writable_ports(design,config):
    fixed=set(config['environment'].get('static',{}))|{r['port'] for r in config['environment'].get('extra_resets',[])}
    clocks={c['port'] for c in config['environment']['clocks']}
    return [p for p in design.data_ports if p.direction=='input' and p.name not in fixed|clocks|{design.reset,design.clock}]


def validate_clauses(text, names):
    if not isinstance(text,str) or not 1<=len(text)<=65536:
        raise ConstraintError('constraints must contain 1..65536 characters')
    # Comments are disallowed rather than silently stripping possible directives.
    if any(x in text for x in ('//','/*','*/','`','"','$','\\','::','++','--')):
        raise ConstraintError('constraints contain forbidden source constructs')
    keywords={'foreach','if','else','inside','unique','dist','soft','solve','before',
              'int','longint','signed','unsigned','with','item','i','j','k',
              'sum','product','and','or','xor'}
    token=re.compile(r"(?:[0-9][0-9_]*'s?[bBoOdDhH][0-9a-fA-F_]+|'[01]|[0-9][0-9_]*|[A-Za-z_][A-Za-z_0-9]*|[{}\[\]();,:.?'+*/%~!&|^<>=-])")
    at=0;stack=[];pairs={'}':'{',']':'[',')':'('}
    while at<len(text):
        if text[at].isspace():at+=1;continue
        match=token.match(text,at)
        if not match:raise ConstraintError(f'unsupported constraint token at offset {at}')
        word=match[0];at=match.end()
        if re.fullmatch(r'[A-Za-z_]\w*',word) and word not in set(names)|keywords:
            raise ConstraintError('unknown/forbidden constraint identifier: '+word)
        if word in ('{','[','('):stack.append(word)
        elif word in pairs:
            if not stack or stack.pop()!=pairs[word]:raise ConstraintError('unbalanced constraint delimiters')
    if stack:raise ConstraintError('unbalanced constraint delimiters')
    for m in re.finditer(r'\.\s*([A-Za-z_]\w*)',text):
        if m[1] not in ('sum','product','and','or','xor'):
            raise ConstraintError('only array reduction methods are allowed')
    return text


def render(design, config, intent):
    cycles=intent.get('cycles')
    if type(cycles) is not int or not 1<=cycles<=4096:raise ConstraintError('cycles must be 1..4096')
    ports=writable_ports(design,config)
    if not ports:raise ConstraintError('no writable data inputs')
    if any(not re.fullmatch(r'[A-Za-z_]\w*',p.name) or p.name.startswith('rvp_') for p in ports):
        raise ConstraintError('unsupported or reserved input identifier')
    text=validate_clauses(intent.get('constraints'),[p.name for p in ports])
    declarations='\n'.join(f'rand bit [{p.width-1}:0] {p.name}[{cycles}];' for p in ports)
    formats=' '.join('%h' for p in ports)
    values=', '.join('rvp_obj.'+p.name+'[rvp_t]' for p in ports)
    source=f'''class RvpConstraint;
{declarations}
constraint rvp_generated {{
{text}
}}
endclass
module RvpConstraintTB;
RvpConstraint rvp_obj;
integer rvp_fd, rvp_seed;
initial begin
rvp_obj = new();
rvp_seed = 20260906;
if ($value$plusargs("RVP_SEED=%d", rvp_seed)) begin end
rvp_obj.srandom(rvp_seed);
rvp_fd = $fopen("samples.txt", "w");
if (!rvp_fd) $fatal(1, "RVP_SAMPLE_FILE_ERROR");
for (int rvp_s=0; rvp_s<4; rvp_s++) begin
if (!rvp_obj.randomize()) begin
$display("RVP_RANDOMIZE_FAILED %0d", rvp_s);
$fclose(rvp_fd); $finish;
end
for (int rvp_t=0; rvp_t<{cycles}; rvp_t++)
$fdisplay(rvp_fd, "%0d %0d {formats}", rvp_s, rvp_t, {values});
end
$fclose(rvp_fd); $display("RVP_CONSTRAINT_PASS"); $finish;
end
endmodule
'''
    return source,ports,cycles


def decode(text,ports,cycles):
    lines=[line.split() for line in text.splitlines() if line.strip()]
    if len(lines)!=4*cycles:raise ConstraintError('randomizer did not emit four complete sequences')
    samples=[]
    for sample in range(4):
        steps=[]
        for t in range(cycles):
            row=lines[sample*cycles+t]
            if len(row)!=len(ports)+2 or row[:2]!=[str(sample),str(t)]:
                raise ConstraintError('malformed randomizer trace')
            drive={}
            for p,value in zip(ports,row[2:]):
                if not re.fullmatch(r'[0-9a-fA-F]+',value):raise ConstraintError('unknown random input bits')
                number=int(value,16)
                if number>=1<<p.width:raise ConstraintError('random input exceeds port width')
                drive[p.name]=hex(number)
            steps.append({'cycles':1,'drive':drive})
        samples.append({'steps':steps})
    return samples


def sample(design,config,intent,directory,args):
    source,ports,cycles=render(design,config,intent)
    directory=Path(directory).resolve();directory.mkdir(parents=True,exist_ok=False)
    (directory/'constraints.sv').write_text(source)
    record=Records(directory)
    def execute(phase,command,timeout):
        with record.phase(phase):
            with (directory/(phase+'.log')).open('w') as log:
                result=run([str(args.eda_shell.resolve()),'-c',shlex.join(command)],cwd=directory,
                    stdout=log,stderr=subprocess.STDOUT,timeout=timeout)
        return result,(directory/(phase+'.log')).read_text(errors='replace')
    result,log=execute('constraint-compile',['vcs','-full64','-sverilog','constraints.sv',
        '-top','RvpConstraintTB','-o','simv'],300)
    if result.returncode:
        if re.search(r'license|Cannot checkout|not found',log,re.I):raise RuntimeError('constraint compiler infrastructure failure: '+log[-2000:])
        raise ConstraintError('VCS constraint compilation failed: '+log[-6000:])
    result,log=execute('constraint-randomize',['./simv',f'+RVP_SEED={args.sampling_seed}'],120)
    if 'RVP_RANDOMIZE_FAILED' in log:raise ConstraintError('randomize() failed for the original constraints; preserve the intent when repairing')
    if result.returncode or 'RVP_CONSTRAINT_PASS' not in log:raise RuntimeError('constraint simulation infrastructure failure: '+log[-2000:])
    samples=decode((directory/'samples.txt').read_text(),ports,cycles)
    save(directory/'sampled-sequences.json',{'intent':intent,'samples':samples,'seed':args.sampling_seed,
        'solver':'VCS randomize','dut_transition_relation_in_solver':False,'natural_language_intent_checked':False})
    return samples
