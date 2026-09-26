// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
module {
  llvm.mlir.global external @QUERY_CONTEXT() : !llvm.ptr
  llvm.mlir.global external @QUERY_SOLVER() : !llvm.ptr
  llvm.mlir.global private constant @query_timeout("timeout\00")
  llvm.mlir.global private constant @query_sat("sat\0A(%s)\0A\00")
  llvm.mlir.global private constant @query_unsat("unsat\0A%s\0A\00")
  llvm.mlir.global private constant @query_unknown("unknown\0A\00")
  llvm.func @printf(!llvm.ptr, ...) -> i32
  llvm.func @Z3_set_ast_print_mode(!llvm.ptr, i32)
  llvm.func @Z3_mk_params(!llvm.ptr) -> !llvm.ptr
  llvm.func @Z3_params_inc_ref(!llvm.ptr, !llvm.ptr)
  llvm.func @Z3_params_dec_ref(!llvm.ptr, !llvm.ptr)
  llvm.func @Z3_mk_string_symbol(!llvm.ptr, !llvm.ptr) -> !llvm.ptr
  llvm.func @Z3_params_set_uint(!llvm.ptr, !llvm.ptr, !llvm.ptr, i32)
  llvm.func @Z3_solver_set_params(!llvm.ptr, !llvm.ptr, !llvm.ptr)
  llvm.func @Z3_solver_check_assumptions(!llvm.ptr, !llvm.ptr, i32, !llvm.ptr) -> i32
  llvm.func @Z3_solver_get_model(!llvm.ptr, !llvm.ptr) -> !llvm.ptr
  llvm.func @Z3_model_inc_ref(!llvm.ptr, !llvm.ptr)
  llvm.func @Z3_model_dec_ref(!llvm.ptr, !llvm.ptr)
  llvm.func @Z3_model_to_string(!llvm.ptr, !llvm.ptr) -> !llvm.ptr
  llvm.func @Z3_solver_get_unsat_core(!llvm.ptr, !llvm.ptr) -> !llvm.ptr
  llvm.func @Z3_ast_vector_inc_ref(!llvm.ptr, !llvm.ptr)
  llvm.func @Z3_ast_vector_dec_ref(!llvm.ptr, !llvm.ptr)
  llvm.func @Z3_ast_vector_to_string(!llvm.ptr, !llvm.ptr) -> !llvm.ptr
  llvm.func @query_check(QUERY_ARGUMENTS) {
    %context_addr = llvm.mlir.addressof @QUERY_CONTEXT : !llvm.ptr
    %solver_addr = llvm.mlir.addressof @QUERY_SOLVER : !llvm.ptr
    %context = llvm.load %context_addr : !llvm.ptr -> !llvm.ptr
    %solver = llvm.load %solver_addr : !llvm.ptr -> !llvm.ptr
    %print_mode = llvm.mlir.constant(2 : i32) : i32
    llvm.call @Z3_set_ast_print_mode(%context, %print_mode) : (!llvm.ptr, i32) -> ()
    %params = llvm.call @Z3_mk_params(%context) : (!llvm.ptr) -> !llvm.ptr
    llvm.call @Z3_params_inc_ref(%context, %params) : (!llvm.ptr, !llvm.ptr) -> ()
    %key = llvm.mlir.addressof @query_timeout : !llvm.ptr
    %symbol = llvm.call @Z3_mk_string_symbol(%context, %key) : (!llvm.ptr, !llvm.ptr) -> !llvm.ptr
    %timeout = llvm.mlir.constant(QUERY_TIMEOUT : i32) : i32
    llvm.call @Z3_params_set_uint(%context, %params, %symbol, %timeout) : (!llvm.ptr, !llvm.ptr, !llvm.ptr, i32) -> ()
    llvm.call @Z3_solver_set_params(%context, %solver, %params) : (!llvm.ptr, !llvm.ptr, !llvm.ptr) -> ()
    llvm.call @Z3_params_dec_ref(%context, %params) : (!llvm.ptr, !llvm.ptr) -> ()
    QUERY_ASSUMPTIONS
    %count = llvm.mlir.constant(QUERY_COUNT : i32) : i32
    %status = llvm.call @Z3_solver_check_assumptions(%context, %solver, %count, %assumptions) : (!llvm.ptr, !llvm.ptr, i32, !llvm.ptr) -> i32
    %sat = llvm.mlir.constant(1 : i32) : i32
    %is_sat = llvm.icmp "eq" %status, %sat : i32
    llvm.cond_br %is_sat, ^sat, ^other
  ^sat:
    %model = llvm.call @Z3_solver_get_model(%context, %solver) : (!llvm.ptr, !llvm.ptr) -> !llvm.ptr
    llvm.call @Z3_model_inc_ref(%context, %model) : (!llvm.ptr, !llvm.ptr) -> ()
    %model_text = llvm.call @Z3_model_to_string(%context, %model) : (!llvm.ptr, !llvm.ptr) -> !llvm.ptr
    %sat_format = llvm.mlir.addressof @query_sat : !llvm.ptr
    %printed_sat = llvm.call @printf(%sat_format, %model_text) vararg(!llvm.func<i32 (ptr, ...)>) : (!llvm.ptr, !llvm.ptr) -> i32
    llvm.call @Z3_model_dec_ref(%context, %model) : (!llvm.ptr, !llvm.ptr) -> ()
    llvm.return
  ^other:
    %unsat = llvm.mlir.constant(-1 : i32) : i32
    %is_unsat = llvm.icmp "eq" %status, %unsat : i32
    llvm.cond_br %is_unsat, ^unsat, ^unknown
  ^unsat:
    %core = llvm.call @Z3_solver_get_unsat_core(%context, %solver) : (!llvm.ptr, !llvm.ptr) -> !llvm.ptr
    llvm.call @Z3_ast_vector_inc_ref(%context, %core) : (!llvm.ptr, !llvm.ptr) -> ()
    %core_text = llvm.call @Z3_ast_vector_to_string(%context, %core) : (!llvm.ptr, !llvm.ptr) -> !llvm.ptr
    %unsat_format = llvm.mlir.addressof @query_unsat : !llvm.ptr
    %printed_unsat = llvm.call @printf(%unsat_format, %core_text) vararg(!llvm.func<i32 (ptr, ...)>) : (!llvm.ptr, !llvm.ptr) -> i32
    llvm.call @Z3_ast_vector_dec_ref(%context, %core) : (!llvm.ptr, !llvm.ptr) -> ()
    llvm.return
  ^unknown:
    %unknown_format = llvm.mlir.addressof @query_unknown : !llvm.ptr
    %printed_unknown = llvm.call @printf(%unknown_format) vararg(!llvm.func<i32 (ptr, ...)>) : (!llvm.ptr) -> i32
    llvm.return
  }
}
