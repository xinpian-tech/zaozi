"""Test-only LTL fixtures; never used by generation or RAG."""
from sequence_framework import parse_response


def goal_response(label, expression, design=None):
    return goals_response([(label, expression)])


def append_goals(response, goals):
    text = response["ltl"]
    for label, expression in goals:
        text += f'\nGen((\n{expression}\n), "{label}")\n'
    response.update(parse_response(text))
    return response


def goals_response(goals, design=None):
    text = ""
    for label, expression in goals:
        text += f'Gen((\n{expression}\n), "{label}")\n'
    return parse_response(text)
