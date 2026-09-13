"""Read clock/reset roles from the elaborated original RTL, without a model."""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
from run_records import save, utc, fresh_directory
from cycle_replay import digest


def report_words(raw):
    """Split the braced Tcl list emitted by JG without evaluating Tcl code."""
    words, token, depth = [], '', 0
    for char in raw:
        if char == '{': depth += 1
        elif char == '}':
            depth -= 1
            if depth < 0: raise ValueError('unbalanced JG report')
        if char.isspace() and depth == 0:
            if token: words.append(token); token = ''
        else: token += char
    if depth: raise ValueError('unbalanced JG report')
    if token: words.append(token)
    return words


def parse_roles(report, inputs, primary_clock, primary_reset):
    """Only top-level scalar pins can become environment controls automatically."""
    scalar = {p['name'] for p in inputs if int(p['width']) == 1}
    def tokens(raw):
        # Reports containing hierarchy, expressions or Tcl quoting require a
        # separately verified mapping; never guess by stripping scope names.
        words = raw.split()
        if any(not re.fullmatch(r'~?[A-Za-z_]\w*', word) for word in words):
            raise ValueError('elaborated environment has nontrivial clock/reset expressions')
        return words
    clocks = tokens(report['clocks_raw'])
    resets = report_words(report['resets_raw'])
    if any(name not in scalar for name in clocks):
        raise ValueError('elaborated clock is not a scalar top-level input')
    if primary_clock not in clocks:
        raise ValueError('selected primary clock was not found in elaborated RTL')
    extra, internal = [], []
    for raw in resets:
        if not re.fullmatch(r'~?[A-Za-z_]\w*',raw):
            # Synchronous-clear candidates may mix top-level data pins with
            # local RTL state, not only hierarchical names. They remain RTL
            # logic, never additional externally asserted reset assumptions.
            expression = re.sub(r"\d*'[sS]?[bBoOdDhH][0-9a-fA-F_xXzZ?]+", '', raw)
            identifiers = re.findall(r'[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*',expression)
            external = {p['name'] for p in inputs}
            reset_pins = {primary_reset['name']} | {
                r.removeprefix('~') for r in resets if re.fullmatch(r'~?[A-Za-z_]\w*',r)}
            if identifiers and set(identifiers) - external and not (set(identifiers) & reset_pins):
                # JG also labels internal FIFO clear/write conditions as reset
                # candidates. Keep their RTL behavior; never turn them into
                # environment assumptions or drive internal DUT signals.
                internal.append(raw)
                continue
            raise ValueError('elaborated environment has a nontrivial external reset expression')
        name = raw.removeprefix('~')
        if name not in scalar:
            raise ValueError('elaborated reset is not a scalar top-level input')
        level = 'low' if raw.startswith('~') else 'high'
        if name == primary_reset['name']:
            if level != primary_reset['level']:
                raise ValueError('primary reset polarity contradicts elaborated RTL')
        else:
            extra.append({'signal':name,'level':level})
    if len(set(clocks)) != len(clocks) or len({r['signal'] for r in extra}) != len(extra):
        raise ValueError('ambiguous or duplicate elaborated clock/reset roles')
    return {'clocks':sorted(clocks),'extra_resets':sorted(extra,key=lambda r:r['signal']),
            'internal_reset_conditions':internal}


def import_ports(task, directory):
    """Use CIRCT elaboration, including macro/parameter widths, not guessed widths."""
    directory = Path(directory)
    fresh_directory(directory)
    sources = [(Path(task['root']) / p).resolve() for p in task['rtl_files']]
    from sequence_framework import verilog_import_options
    command = ['circt-verilog',*map(str,sources),'--ir-hw','--top='+task['module_name'],
               *verilog_import_options(sources)]
    for include in sorted({p.parent for p in sources}): command += ['-I',str(include)]
    # Tools may quote legacy non-UTF8 RTL comments in warnings. Preserve the
    # original sources/hashes; replacement is confined to diagnostic decoding.
    process = subprocess.run(command, capture_output=True, text=True, errors='replace', timeout=180)
    (directory/'imported.hw.mlir').write_text(process.stdout)
    (directory/'import.log').write_text(process.stderr)
    process.check_returncode()
    header = re.search(r'hw\.module @'+re.escape(task['module_name'])+r'\((.*?)\)\s*\{',process.stdout,re.S)
    if not header: raise ValueError('CIRCT did not return the requested top interface')
    ports = []
    for field in header[1].split(','):
        ref = re.fullmatch(r'\s*in\s+%([A-Za-z_]\w*)\s*:\s*!llhd\.ref<i(\d+)>\s*',field)
        if ref:
            ports.append({'name':ref[1],'direction':'inout','width':int(ref[2])})
            continue
        port = re.fullmatch(r'\s*(in|out)\s+%?([A-Za-z_]\w*)\s*:\s*i(\d+)\s*',field)
        if not port: raise ValueError('unsupported imported IO: '+field.strip())
        ports.append({'name':port[2],'direction':'input' if port[1]=='in' else 'output','width':int(port[3])})
    save(directory/'ports.json',ports)
    return ports


def discover(task, directory, eda_shell):
    directory = Path(directory).resolve()
    directory.mkdir(parents=True, exist_ok=False)
    top = task['module_name']
    if not re.fullmatch(r'[A-Za-z_]\w*',top): raise ValueError('invalid RTL top')
    root = Path(task['root'])
    sources = [(root/Path(p)).resolve() for p in task['rtl_files']]
    if any(re.search(r'[{}\n\r\\]',str(p)) for p in [*sources,root]): raise ValueError('unsupported Tcl path')
    tcl = '\n'.join(['clear -all', 'analyze -v2k {+incdir+'+str(root/'rtl')+'} '+
                     ' '.join('{'+str(p)+'}' for p in sources), f'elaborate -disable_auto_bbox -top {top}',
                     'puts "RVPROBE_CLOCKS [clock -analyze -silent]"',
                     'puts "RVPROBE_RESETS [reset -analyze -synchronous -list signal -silent]"', 'exit'])
    script = directory/'discover.tcl'
    script.write_text(tcl+'\n')
    command = shlex.join(['jg','-batch','-tcl',str(script),'-proj',str(directory/'jgproj')])
    with (directory/'jg.log').open('w') as log:
        subprocess.run([str(eda_shell),'-c',command],stdout=log,stderr=subprocess.STDOUT,check=True,timeout=300)
    text = (directory/'jg.log').read_text()
    clock = re.search(r'^RVPROBE_CLOCKS (.*)$',text,re.M)
    reset = re.search(r'^RVPROBE_RESETS (.*)$',text,re.M)
    if not clock or not reset: raise ValueError('missing elaborated environment reports')
    result = {'version':1,'top':top,'checked_utc':utc(),'clocks_raw':clock[1],'resets_raw':reset[1],
              'source_sha256':{str(p):digest(p) for p in sources}}
    save(directory/'roles.json',result)
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('task',type=Path)
    parser.add_argument('--out',type=Path,required=True)
    parser.add_argument('--eda-shell',type=Path,default=Path(__file__).resolve().parent/'eda-shell')
    args = parser.parse_args()
    print(json.dumps(discover(json.loads(args.task.read_text()),args.out,args.eda_shell),indent=2))
