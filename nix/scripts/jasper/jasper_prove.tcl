# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 xinpian-tech
#
# Generic JasperGold batch proof script.
#
# Required environment:
#   TOP           top module to elaborate
#   DESIGN_FILES  whitespace-separated RTL files
#
# Optional environment:
#   ASSERTION_FILES  whitespace-separated assertion/checker files
#   REPORT_DIR       report directory, default: reports
#   CLOCKS           whitespace-separated Jasper clock expressions
#   RESETS           whitespace-separated Jasper reset expressions
#   PROVE_ARGS       extra arguments passed to `prove -all`

proc env_or_default {name default} {
  if {[info exists ::env($name)] && $::env($name) ne ""} {
    return $::env($name)
  }
  return $default
}

proc require_env {name} {
  if {![info exists ::env($name)] || $::env($name) eq ""} {
    puts stderr "ERROR: required environment variable $name is not set"
    exit 2
  }
  return $::env($name)
}

proc split_words {value} {
  if {$value eq ""} {
    return {}
  }
  return [regexp -all -inline {\S+} $value]
}

set start_time [clock seconds]
puts [clock format $start_time -gmt false]

set top             [require_env TOP]
set design_files    [split_words [require_env DESIGN_FILES]]
set assertion_files [split_words [env_or_default ASSERTION_FILES ""]]
set report_dir      [env_or_default REPORT_DIR "reports"]
set clocks          [split_words [env_or_default CLOCKS ""]]
set resets          [split_words [env_or_default RESETS ""]]
set prove_args      [split_words [env_or_default PROVE_ARGS ""]]

file mkdir $report_dir

clear -all

foreach f [concat $design_files $assertion_files] {
  if {![file exists $f]} {
    puts stderr "ERROR: input file does not exist: $f"
    exit 2
  }
  analyze -sv $f
}

elaborate -top $top

foreach clk $clocks {
  clock $clk
}

foreach rst $resets {
  reset $rst
}

set prove_status [catch {prove -all {*}$prove_args} prove_result]
puts $prove_result

foreach report_cmd [list \
  [list report -summary -file "$report_dir/summary.rpt"] \
  [list report -properties -all -file "$report_dir/properties.rpt"] \
] {
  if {[catch $report_cmd report_error]} {
    puts "WARN: report command failed: $report_cmd"
    puts "WARN: $report_error"
  }
}

set end_time [clock seconds]
puts "Time elapsed: [expr {$end_time - $start_time}] seconds"

if {$prove_status == 0} {
  exit 0
}
exit 1
