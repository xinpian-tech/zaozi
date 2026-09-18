"""Launch long experiments under systemd, independently of the tool-call lifetime.

This is a one-shot service, not a timer, scheduled task or model retry mechanism.
No model credentials are placed in service command-line environment values.
"""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess


def service_command(unit, log, command, cwd):
    if not re.fullmatch(r'rvprobe-[a-z0-9][a-z0-9-]{0,100}',unit):
        raise ValueError('use a unique rvprobe- prefixed service name')
    if not command:
        raise ValueError('missing service command')
    runner=shutil.which('systemd-run')
    if not runner:
        raise RuntimeError('systemd-run required; no tool-session background fallback')
    args=[runner,'--quiet','--unit='+unit,'--property=Type=exec',
          '--property=WorkingDirectory='+str(Path(cwd).resolve()),
          '--property=StandardOutput=append:'+str(Path(log).resolve()),
          '--property=StandardError=append:'+str(Path(log).resolve())]
    for key in ('PATH','HTTP_PROXY','HTTPS_PROXY','ALL_PROXY','NO_PROXY',
                'http_proxy','https_proxy','all_proxy','no_proxy','SSL_CERT_FILE','NIX_SSL_CERT_FILE','LANG'):
        if key in os.environ:
            args.append('--setenv='+key)
    return args+['--',*map(str,command)]


def start(unit, log, command, cwd):
    args=service_command(unit,log,command,cwd)
    # Never append a new launch to an old experiment log.
    Path(log).parent.mkdir(parents=True,exist_ok=True)
    with Path(log).open('x'):
        pass
    subprocess.run(args,check=True)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--unit',required=True)
    parser.add_argument('--log',type=Path,required=True)
    parser.add_argument('--cwd',type=Path,default=Path(__file__).resolve().parents[1])
    parser.add_argument('command',nargs=argparse.REMAINDER)
    args=parser.parse_args()
    command=args.command[1:] if args.command[:1]==['--'] else args.command
    start(args.unit,args.log,command,args.cwd)
    print('Started '+args.unit+'.service')


if __name__=='__main__': main()
