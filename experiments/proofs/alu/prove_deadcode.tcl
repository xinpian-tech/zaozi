clear -all
analyze -v2k experiments/fixtures/haven/alu_top.v
analyze -sv12 experiments/proofs/alu/alu_deadcode_formal.sv
elaborate -top alu_deadcode_formal
clock clock
reset reset
set_prove_time_limit 120s
prove -all
foreach p [get_property_list -include {type cover}] {
  set st [get_property_info $p -list status]
  puts "JGSTATUS $p $st"
}
puts "JGDONE"
exit
