"""Prepare the CPython adapter for CIRCT-exported DPI functions."""

import os
import sysconfig
from pathlib import Path

from frontend.api import FrontendBuild
from frontend.cpython.generate import generate


def python_link_flags():
    include = sysconfig.get_path("include")
    library_dir = sysconfig.get_config_var("LIBDIR")
    library_name = sysconfig.get_config_var("LDLIBRARY")
    if (
        not include
        or not library_dir
        or not library_name
        or not library_name.startswith("libpython")
    ):
        raise RuntimeError("embedded CPython headers or library are unavailable")
    stem = library_name.removeprefix("lib").split(".so")[0].split(".a")[0]
    return f"-I{include}", f"-L{library_dir} -Wl,-rpath,{library_dir} -l{stem}"


def prepare(schema, driver, stimulus, build_dir):
    driver = Path(driver).resolve()
    if not driver.is_file() or driver.suffix != ".py":
        raise ValueError("CPython frontend driver must be a Python source file")
    generate(schema, build_dir)
    python_include, python_libraries = python_link_flags()
    binding_dir = Path(__file__).resolve().parent
    python_path = os.pathsep.join(
        filter(None, (str(binding_dir), os.environ.get("PYTHONPATH")))
    )
    return FrontendBuild(
        sources=(binding_dir / "bridge.cpp",),
        cflags=(f"-I{build_dir}", python_include),
        ldflags=(python_libraries,),
        environment={
            "PYTHONDONTWRITEBYTECODE": "1",
            "PYTHONPATH": python_path,
            "ZAOZI_FRONTEND_DRIVER": str(driver),
            "ZAOZI_DPI_SCHEMA": str(schema),
            "ZAOZI_STIMULUS_JSON": str(stimulus),
        },
    )
