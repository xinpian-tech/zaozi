# SPDX-License-Identifier: Apache-2.0
"""Static interface of the built-in module registered by CFFI embedding."""

from zaozi_tb.runtime import CallbackRegistry as _CallbackRegistry

ffi: _CallbackRegistry
