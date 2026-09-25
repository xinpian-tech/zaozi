// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>

#include <Python.h>
#include <svdpi.h>

#include <cstdint>
#include <cstdio>
#include <cstdlib>

static PyObject *dispatch = nullptr;

[[noreturn]] static void fail() {
  PyErr_Print();
  std::fflush(stderr);
  std::abort();
}

static void init() {
  if (dispatch) return;
  if (!Py_IsInitialized()) Py_Initialize();
  PyObject *module = PyImport_ImportModule("frontend_binding");
  if (!module) fail();
  dispatch = PyObject_GetAttrString(module, "dispatch");
  Py_DECREF(module);
  if (!dispatch || !PyCallable_Check(dispatch)) fail();
}

static uint64_t mask(unsigned width) {
  return width == 64 ? UINT64_MAX : ((UINT64_C(1) << width) - 1);
}

static uint64_t read_vec(const svBitVecVal *bits, unsigned width) {
  uint64_t value = bits[0];
  if (width > 32) value |= uint64_t(bits[1]) << 32;
  return value & mask(width);
}

static void write_vec(svBitVecVal *bits, unsigned width, uint64_t value) {
  value &= mask(width);
  bits[0] = svBitVecVal(value);
  if (width > 32) bits[1] = svBitVecVal(value >> 32);
}

static void append_input(PyObject *args, Py_ssize_t &index, uint64_t value) {
  PyObject *item = PyLong_FromUnsignedLongLong(value);
  if (!item) fail();
  PyTuple_SET_ITEM(args, index++, item);
}

static PyObject *invoke(PyObject *args, Py_ssize_t output_count) {
  PyObject *result = PyObject_CallObject(dispatch, args);
  Py_DECREF(args);
  if (!result) fail();
  if (!PyTuple_Check(result) || PyTuple_GET_SIZE(result) != output_count) {
    PyErr_SetString(PyExc_TypeError, "DPI dispatch returned invalid tuple");
    fail();
  }
  return result;
}

static uint64_t next_output(PyObject *result, Py_ssize_t &index) {
  uint64_t value = PyLong_AsUnsignedLongLong(PyTuple_GET_ITEM(result, index++));
  if (PyErr_Occurred()) fail();
  return value;
}

// The generated .def contains only function and argument metadata. These macros
// supply the C ABI signatures and the Python bridge implementation.
#define ZAOZI_DPI_SCALAR_8 char
#define ZAOZI_DPI_SCALAR_16 short
#define ZAOZI_DPI_SCALAR_32 int
#define ZAOZI_DPI_SCALAR_64 long long

#define ZAOZI_DPI_ARG_IN_BIT(name, width) svBit zaozi_arg_##name
#define ZAOZI_DPI_ARG_OUT_BIT(name, width) svBit *zaozi_arg_##name
#define ZAOZI_DPI_ARG_INOUT_BIT(name, width) svBit *zaozi_arg_##name
#define ZAOZI_DPI_ARG_IN_SCALAR(name, width) ZAOZI_DPI_SCALAR_##width zaozi_arg_##name
#define ZAOZI_DPI_ARG_OUT_SCALAR(name, width) ZAOZI_DPI_SCALAR_##width *zaozi_arg_##name
#define ZAOZI_DPI_ARG_INOUT_SCALAR(name, width) ZAOZI_DPI_SCALAR_##width *zaozi_arg_##name
#define ZAOZI_DPI_ARG_IN_PACKED(name, width) const svBitVecVal *zaozi_arg_##name
#define ZAOZI_DPI_ARG_OUT_PACKED(name, width) svBitVecVal *zaozi_arg_##name
#define ZAOZI_DPI_ARG_INOUT_PACKED(name, width) svBitVecVal *zaozi_arg_##name

#define ZAOZI_DPI_BEGIN(name, c_name, parameters, input_count) \
  extern "C" int c_name parameters { \
    init(); \
    PyObject *args = PyTuple_New(1 + input_count); \
    if (!args) fail(); \
    PyObject *function_name = PyUnicode_FromString(#name); \
    if (!function_name) fail(); \
    PyTuple_SET_ITEM(args, 0, function_name); \
    Py_ssize_t input_index = 1;

#define ZAOZI_DPI_INPUT_IN_BIT(name, width) append_input(args, input_index, zaozi_arg_##name);
#define ZAOZI_DPI_INPUT_IN_SCALAR(name, width) append_input(args, input_index, zaozi_arg_##name);
#define ZAOZI_DPI_INPUT_IN_PACKED(name, width) append_input(args, input_index, read_vec(zaozi_arg_##name, width));
#define ZAOZI_DPI_INPUT_INOUT_BIT(name, width) append_input(args, input_index, *zaozi_arg_##name);
#define ZAOZI_DPI_INPUT_INOUT_SCALAR(name, width) append_input(args, input_index, *zaozi_arg_##name);
#define ZAOZI_DPI_INPUT_INOUT_PACKED(name, width) append_input(args, input_index, read_vec(zaozi_arg_##name, width));

#define ZAOZI_DPI_CALL(output_count) \
  PyObject *result = invoke(args, output_count); \
  Py_ssize_t output_index = 0;

#define ZAOZI_DPI_OUTPUT_BIT(name, width) \
  *zaozi_arg_##name = svBit(next_output(result, output_index));
#define ZAOZI_DPI_OUTPUT_SCALAR(name, width) \
  *zaozi_arg_##name = static_cast<ZAOZI_DPI_SCALAR_##width>(next_output(result, output_index));
#define ZAOZI_DPI_OUTPUT_PACKED(name, width) \
  write_vec(zaozi_arg_##name, width, next_output(result, output_index));
#define ZAOZI_DPI_RETURN(name, width) int status = int(next_output(result, output_index));
#define ZAOZI_DPI_END() Py_DECREF(result); return status; }

#include "zaozi_dpi.def"
