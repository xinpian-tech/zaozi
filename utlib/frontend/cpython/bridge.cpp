// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>

#include <Python.h>
#include <svdpi.h>

#include <cstdint>
#include <cstdio>
#include <cstdlib>

static PyObject *invoke = nullptr;

[[noreturn]] static void fail() {
  PyErr_Print();
  std::fflush(stderr);
  std::abort();
}

static void initialize() {
  if (invoke)
    return;
  if (!Py_IsInitialized())
    Py_Initialize();
  PyObject *module = PyImport_ImportModule("binding");
  if (!module)
    fail();
  invoke = PyObject_GetAttrString(module, "invoke");
  Py_DECREF(module);
  if (!invoke || !PyCallable_Check(invoke))
    fail();
}

static void input(PyObject *inputs, const char *name, unsigned width,
                  uint64_t raw) {
  if (width < 64)
    raw &= (UINT64_C(1) << width) - 1;
  PyObject *item = PyLong_FromUnsignedLongLong(raw);
  if (!item || PyDict_SetItemString(inputs, name, item) != 0)
    fail();
  Py_DECREF(item);
}

static uint64_t read_vec(const svBitVecVal *bits, unsigned width) {
  return uint64_t(bits[0]) | (width > 32 ? uint64_t(bits[1]) << 32 : 0);
}

static PyObject *call(const char *name, PyObject *inputs) {
  PyObject *function = PyUnicode_FromString(name);
  if (!function)
    fail();
  PyObject *values =
      PyObject_CallFunctionObjArgs(invoke, function, inputs, nullptr);
  Py_DECREF(function);
  Py_DECREF(inputs);
  if (!values || !PyDict_Check(values))
    fail();
  return values;
}

static uint64_t value(PyObject *values, const char *name, unsigned width) {
  PyObject *item = PyDict_GetItemString(values, name);
  if (!item) {
    PyErr_Format(PyExc_KeyError, "missing DPI output %s", name);
    fail();
  }
  const auto result = PyLong_AsUnsignedLongLong(item);
  if (PyErr_Occurred())
    fail();
  if (width < 64 && result >= (UINT64_C(1) << width)) {
    PyErr_Format(PyExc_ValueError, "DPI output %s exceeds %u bits", name,
                 width);
    fail();
  }
  return result;
}

static void write_vec(svBitVecVal *bits, unsigned width, uint64_t input) {
  bits[0] = svBitVecVal(input);
  if (width > 32)
    bits[1] = svBitVecVal(input >> 32);
}

#define ZAOZI_DPI_BEGIN(return_type, name, parameters)                         \
  extern "C" return_type name parameters {                                     \
    initialize();                                                              \
    PyObject *inputs = PyDict_New();                                           \
    if (!inputs)                                                               \
      fail();
#define ZAOZI_DPI_INPUT_BIT(name, width)                                       \
  input(inputs, #name, width, zaozi_arg_##name);
#define ZAOZI_DPI_INPUT_SCALAR(name, width)                                    \
  input(inputs, #name, width, static_cast<uint64_t>(zaozi_arg_##name));
#define ZAOZI_DPI_INPUT_PACKED(name, width)                                    \
  input(inputs, #name, width, read_vec(zaozi_arg_##name, width));
#define ZAOZI_DPI_INOUT_BIT(name, width)                                       \
  input(inputs, #name, width, *zaozi_arg_##name);
#define ZAOZI_DPI_INOUT_SCALAR(name, width)                                    \
  input(inputs, #name, width, static_cast<uint64_t>(*zaozi_arg_##name));
#define ZAOZI_DPI_INOUT_PACKED(name, width)                                    \
  input(inputs, #name, width, read_vec(zaozi_arg_##name, width));
#define ZAOZI_DPI_CALL(name) PyObject *values = call(name, inputs);
#define ZAOZI_DPI_OUTPUT_BIT(name, width)                                      \
  *zaozi_arg_##name = svBit(value(values, #name, width));
#define ZAOZI_DPI_OUTPUT_SCALAR(name, width)                                   \
  *zaozi_arg_##name =                                                          \
      static_cast<ZAOZI_DPI_SCALAR_##width>(value(values, #name, width));
#define ZAOZI_DPI_OUTPUT_PACKED(name, width)                                   \
  write_vec(zaozi_arg_##name, width, value(values, #name, width));
#define ZAOZI_DPI_RETURN(name, width)                                          \
  const uint64_t result = value(values, #name, width);                         \
  Py_DECREF(values);                                                           \
  return result;
#define ZAOZI_DPI_END() }

#define ZAOZI_DPI_SCALAR_8 char
#define ZAOZI_DPI_SCALAR_16 short
#define ZAOZI_DPI_SCALAR_32 int
#define ZAOZI_DPI_SCALAR_64 long long

#include "zaozi_dpi.def"
