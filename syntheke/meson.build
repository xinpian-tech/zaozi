# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>

# Everything downstream of the Scala. Mill compiles a jar and stops; from that jar this build elaborates the demo SoC
# into artifacts, compiles the DPI the testbench needs, verilates the result and brings it up with a debugger.
project(
  'syntheke-demo',
  'cpp',
  version: '0.1.0',
  meson_version: '>=1.1',
  default_options: ['cpp_std=c++20', 'warning_level=1'],
)

fs = import('fs')

# The two nix packages only these tests need. `nix develop .#syntheke` exports both; -Dramulator=/-Dsimprobe= override.
ramulator_prefix = get_option('ramulator')
if ramulator_prefix == ''
  ramulator_prefix = run_command('sh', '-c', 'printf %s "$RAMULATOR_INSTALL_PATH"', check: true).stdout()
endif
simprobe_prefix = get_option('simprobe')
if simprobe_prefix == ''
  simprobe_prefix = run_command('sh', '-c', 'printf %s "$SIMPROBE_INSTALL_PATH"', check: true).stdout()
endif
assert(ramulator_prefix != '', 'no Ramulator: set $RAMULATOR_INSTALL_PATH or -Dramulator=<prefix>')
assert(simprobe_prefix != '', 'no simprobe: set $SIMPROBE_INSTALL_PATH or -Dsimprobe=<prefix>')

verilator = find_program('verilator')
simprobe = find_program(simprobe_prefix / 'bin' / 'simprobe')
scripts = meson.current_source_dir() / 'scripts'
sim = meson.current_source_dir() / 'demo' / 'sim'

# ---- the design, out of the jar mill builds ----------------------------------------------------------------------
# Mill decides what is stale on the Scala side, so this always runs and is quick when nothing changed.
artifacts = custom_target(
  'artifacts',
  output: 'artifacts',
  command: [scripts / 'elaborate.sh', meson.current_source_dir() / '..', '@OUTPUT@'],
  build_always_stale: true,
  build_by_default: true,
  console: true,
)

# ---- the testbench's C++: what the external modules do, and Ramulator behind the memory port ----------------------
ramulator = declare_dependency(
  dependencies: meson.get_compiler('cpp').find_library('ramulator', dirs: ramulator_prefix / 'lib'),
  include_directories: include_directories(ramulator_prefix / 'include', is_system: true),
)

dpi = static_library(
  'syntheke_dpi',
  [sim / 'jtag_dpi.cc', sim / 'dram_dpi.cc'],
  dependencies: ramulator,
)

# ---- the simulator -----------------------------------------------------------------------------------------------
models = ['ClockGen.sv', 'SimConsole.sv', 'PllAnalog.sv', 'TraceLog.sv', 'JtagDpi.sv', 'DramDpi.sv']

vtop = custom_target(
  'VTop',
  input: artifacts,
  output: 'VTop',
  depends: dpi,
  command: [
    scripts / 'verilate.sh',
    verilator.full_path(),
    '@INPUT@',
    sim,
    dpi.full_path(),
    ramulator_prefix,
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
  args: [vtop, simprobe.full_path(), artifacts, sim / 'dram.yaml'],
  timeout: 600,
  workdir: meson.current_build_dir(),
)
