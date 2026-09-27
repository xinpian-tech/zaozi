"""Prepare a direct C++ implementation of CIRCT-exported DPI functions."""

from pathlib import Path

from frontend.api import FrontendBuild
from frontend.cpp.generate import generate


def prepare(schema, driver, stimulus, build_dir):
    driver = Path(driver).resolve()
    if not driver.is_file() or driver.suffix not in (".cpp", ".cc", ".cxx"):
        raise ValueError("C++ frontend driver must be a C++ source file")
    generate(schema, build_dir)
    return FrontendBuild(
        sources=(driver,),
        cflags=(f"-I{build_dir}",),
        environment={"ZAOZI_STIMULUS_JSON": str(stimulus)},
    )
