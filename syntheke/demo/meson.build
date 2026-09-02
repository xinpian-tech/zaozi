# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>

# Everything downstream of the Scala. Mill compiles a jar and stops; from that jar this build elaborates the demo SoC
# into artifacts, verilates them against the testbench's DPI, and brings the result up with a debugger.
#
# No language of its own: the design is Scala's, the testbench's behaviour is Rust in a package of its own, and the
# only compiler here is verilator's.
project(
  'syntheke-demo',
  version: '0.1.0',
  meson_version: '>=1.1',
)

# The packages only this demo needs. `nix develop .#syntheke` exports all three; the options override them.
prefixes = {}
foreach tool : [
  ['dpi', 'SYNTHEKE_DPI_INSTALL_PATH'],
  ['simprobe', 'SIMPROBE_INSTALL_PATH'],
  ['riscv', 'RISCV_TOOLCHAIN_INSTALL_PATH'],
]
  prefix = get_option(tool[0])
  if prefix == ''
    prefix = run_command('sh', '-c', 'printf %s "$' + tool[1] + '"', check: true).stdout()
  endif
  assert(prefix != '', 'no ' + tool[0] + ': set $' + tool[1] + ' or -D' + tool[0] + '=<prefix>')
  prefixes += {tool[0]: prefix}
endforeach

verilator = find_program('verilator')
simprobe = find_program(prefixes['simprobe'] / 'bin' / 'simprobe')
scripts = meson.current_source_dir() / 'scripts'
sim = meson.current_source_dir() / 'sim'

# ---- the design, out of the jar mill builds ----------------------------------------------------------------------
# Mill decides what is stale on the Scala side, so this always runs and is quick when nothing changed.
artifacts = custom_target(
  'artifacts',
  output: 'artifacts',
  command: [
    scripts / 'elaborate.sh',
    meson.current_source_dir() / '..' / '..',
    '@OUTPUT@',
    get_option('config'),
  ],
  build_always_stale: true,
  build_by_default: true,
  console: true,
)

# ---- the program the debugger downloads ------------------------------------------------------------------------
# Assembled against the design's own address map, which `design.env` carries out of the elaboration.
program = custom_target(
  'program',
  input: artifacts,
  output: ['program.bin', 'program.env'],
  command: [
    scripts / 'assemble.sh',
    prefixes['riscv'],
    meson.current_source_dir() / 'program' / 'hello.S',
    '@INPUT@',
    '@OUTPUT0@',
    '@OUTPUT1@',
  ],
  build_by_default: true,
)

# ---- the simulator -----------------------------------------------------------------------------------------------
# The behavioral definitions of the external modules; what they do beyond driving pins is the DPI library's.
models = ['ClockGen.sv', 'SimConsole.sv', 'PllAnalog.sv', 'TraceLog.sv', 'JtagDpi.sv', 'DramDpi.sv']

vtop = custom_target(
  'VTop',
  input: artifacts,
  output: 'VTop',
  command: [
    scripts / 'verilate.sh',
    verilator.full_path(),
    '@INPUT@',
    sim,
    prefixes['dpi'],
    '@OUTPUT@',
  ]
  + models,
  build_by_default: true,
  console: true,
)

# ---- the bring-up: a debugger attaches over JTAG, loads the harts and lets them go --------------------------------
test(
  'soc bring-up',
  find_program(scripts / 'bringup.sh'),
  args: [vtop, simprobe.full_path(), artifacts, program, sim / 'dram.yaml'],
  timeout: 600,
  workdir: meson.current_build_dir(),
)
