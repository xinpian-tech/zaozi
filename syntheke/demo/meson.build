project(
  'syntheke-demo',
  version: '0.1.0',
  meson_version: '>=1.1',
)

prefixes = {}
foreach tool : [
  ['dpi', 'SYNTHEKE_DPI_INSTALL_PATH'],
  ['riscv', 'RISCV_TOOLCHAIN_INSTALL_PATH'],
  ['simprobe', 'SIMPROBE_INSTALL_PATH'],
]
  prefix = get_option(tool[0])
  if prefix == ''
    prefix = run_command('sh', '-c', 'printf %s "$' + tool[1] + '"', check: true).stdout()
  endif
  assert(prefix != '', 'no ' + tool[0] + ': set $' + tool[1] + ' or -D' + tool[0] + '=<prefix>')
  prefixes += {tool[0]: prefix}
endforeach

scripts = meson.current_source_dir() / 'scripts'

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
# The behavioural definitions of the external modules; what they do beyond driving pins is the DPI library's.
sim = meson.current_source_dir() / 'sim'
models = ['ClockGen.sv', 'SimConsole.sv', 'PllAnalog.sv', 'TraceLog.sv', 'JtagDpi.sv', 'DramDpi.sv', 'PowerCells.sv']
find_program('verilator')

simulator = custom_target(
  'simulator',
  input: artifacts,
  output: 'VTestbench',
  command: [scripts / 'verilate.sh', '@INPUT@', sim, prefixes['dpi'], '@OUTPUT@'] + models,
  build_by_default: true,
  console: true,
)

# ---- the bring-up: a debugger attaches over JTAG, power-cycles CPU1, loads the harts and lets them go -------------
test(
  'soc bring-up',
  find_program(scripts / 'bringup.sh'),
  args: [simulator, prefixes['simprobe'] / 'bin' / 'simprobe', artifacts, program[0], program[1], sim / 'dram.yaml'],
  timeout: 600,
)

summary({'simulator models': ' '.join(models), 'DPI library': prefixes['dpi']})
