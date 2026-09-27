"""Shared build inputs produced by a unit-test frontend."""

from dataclasses import dataclass, field
from pathlib import Path


@dataclass(frozen=True)
class FrontendBuild:
    sources: tuple[Path, ...]
    cflags: tuple[str, ...] = ()
    ldflags: tuple[str, ...] = ()
    environment: dict[str, str] = field(default_factory=dict)
