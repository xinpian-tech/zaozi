# SPDX-License-Identifier: Apache-2.0
"""Typed boundaries for JSON data and the scalar DPI interface."""

import json
from typing import TypedDict, cast


class DpiArgument(TypedDict):
    name: str
    direction: str
    width: int
    signed: bool


class DpiFunction(TypedDict):
    function: str
    arguments: list[DpiArgument]


def json_value(text: str) -> object:
    return cast(object, json.loads(text))


def object_fields(value: object) -> dict[str, object]:
    if not isinstance(value, dict):
        raise ValueError("Expected a JSON object")
    fields = cast(dict[object, object], value)
    result: dict[str, object] = {}
    for key, item in fields.items():
        if not isinstance(key, str):
            raise ValueError("Expected string object keys")
        result[key] = item
    return result


def array_items(value: object) -> list[object]:
    if not isinstance(value, list):
        raise ValueError("Expected a JSON array")
    return cast(list[object], value)


def string_fields(value: object) -> dict[str, str]:
    result: dict[str, str] = {}
    for key, item in object_fields(value).items():
        if not isinstance(item, str):
            raise ValueError(f"Expected a string for {key}")
        result[key] = item
    return result
