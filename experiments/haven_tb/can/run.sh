#!/bin/sh
# Compile, simulate and score one arm. Usage: ./run.sh <tag> [+define+RVPROBE_FLOW]
#   <tag> names the work dir (work_<tag>) so baseline and flow runs keep separate vdb/urg reports.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
TAG=$1; shift
W=$HERE/work_$TAG; rm -rf "$W"; mkdir -p "$W"
cp "$HERE"/*.sv "$HERE"/*.v "$HERE"/filelist.f "$W"/
SNPS="$HERE/../snps-shell"
CM="line+cond+tgl+fsm+branch"
cd "$W"
"$SNPS" -c "vcs +vcs+lic+wait -sverilog -ntb_opts uvm -cm $CM -timescale=1ns/1ps +incdir+. +verilog2001ext+.v +error+100 $* -f filelist.f -o simv" > vcs.log 2>&1
"$SNPS" -c "./simv +vcs+lic+wait +UVM_TESTNAME=can_top_test +UVM_TIMEOUT=5000000000 +UVM_VERBOSITY=UVM_LOW -cm $CM ${SIM_EXTRA:-}" > sim.log 2>&1
"$SNPS" -c "urg -dir simv.vdb -metric line+branch+tgl+fsm+cond -format text -report urgReport" > urg.log 2>&1
python3 /root/yjh-workspace/rvprobe-workspace/zaozi/experiments/urg_score.py urgReport/modinfo.txt can_top can_registers can_bsp can_btl can_acf can_crc can_fifo can_ibo can_register can_register_asyn can_register_asyn_syn can_register_syn --metrics line,cond,toggle,branch,fsm | tee score.txt
