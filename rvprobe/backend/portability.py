"""Portable native replay for the canonical throughout sequence expansion.

This is the standard throughout expansion in reverse, not a [*0:$] -> [*1:$]
rewrite. In particular, empty matches inside a larger sequence are not removed.
Only a Boolean repeated operand and a single common explicit clock are handled.
Other repeat forms, compound sequence repeats and multi-clock expressions stay
unchanged. Original source/provenance checks must run before this transformation.
"""
import re

EVENT = re.compile(r'@\s*\(\s*(posedge|negedge)\s+([A-Za-z_]\w*)\s*\)')
REPEAT_ZERO = re.compile(r'\[\s*\*\s*(?:0\s*:\s*\$\s*)?\]\s*$')
WORDS = re.compile(r'\b(?:or|and|intersect)\b|[()\[\]{}]')
SEQUENCE = re.compile(r'@|##|\[\s*[*=>]|\b(?:or|and|intersect|throughout|within|until|s_until|'
                      r'eventually|s_eventually|always|s_always|nexttime|s_nexttime|first_match)\b|\|[-=]>')


def pairs(text):
    stack=[]
    result={}
    match={')':'(', ']':'[', '}':'{'}
    for token in WORDS.finditer(text):
        word=token[0]
        if word in ('(', '[', '{'):
            stack.append((word,token.start()))
        elif word in match:
            if not stack or stack[-1][0]!=match[word]:
                raise ValueError('unbalanced SVA expression')
            _,start=stack.pop()
            result[start]=token.start()
    if stack:
        raise ValueError('unbalanced SVA expression')
    return result


def strip_group(text):
    text=text.strip()
    while text.startswith('(') and pairs(text).get(0)==len(text)-1:
        text=text[1:-1].strip()
    return text


def top_words(text):
    depth=0
    result=[]
    for token in WORDS.finditer(text):
        word=token[0]
        if word in ('(', '[', '{'):
            depth+=1
        elif word in (')',']','}'):
            depth-=1
        elif depth==0:
            result.append(token)
    return result


def repeated_boolean(text):
    text=strip_group(text)
    event=EVENT.match(text)
    clock=event[0] if event else ''
    if event:
        text=strip_group(text[event.end():])
    repeat=REPEAT_ZERO.search(text)
    if repeat is None:
        return None
    predicate=strip_group(text[:repeat.start()])
    if not predicate or SEQUENCE.search(predicate):
        return None
    return clock,predicate


def restore_throughout(expression):
    """Return an equivalent portable expression and a source-edit audit."""
    # Narrow lexical boundary: native emitted expressions do not contain these.
    # Do not mistake tokens within a comment, string, or escaped name for SVA.
    if any(marker in expression for marker in ('//','/*','"','\\')):
        return expression,[]
    disable=re.match(r'\s*disable\s+iff\s*\(',expression)
    if disable:
        end=pairs(expression)[disable.end()-1]+1
        rewritten,edits=restore_throughout(expression[end:])
        return (expression[:end]+' '+rewritten,edits) if edits else (expression,[])
    if re.search(r'\|[-=]>|\b(?:iff|implies|until|s_until|eventually|s_eventually|'
                 r'always|s_always|nexttime|s_nexttime|until_with|s_until_with|not|strong|weak|'
                 r'accept_on|reject_on|sync_accept_on|sync_reject_on)\b',expression):
        return expression,[]
    events=list(EVENT.finditer(expression))
    if expression.count('@')!=len(events):
        return expression,[]
    if len({(e[1],e[2]) for e in events})>1:
        return expression,[]
    pairs(expression)
    edits=[]

    def walk(text):
        trimmed=text.strip()
        if not trimmed:
            return text
        offsets=pairs(trimmed)
        if trimmed.startswith('(') and offsets.get(0)==len(trimmed)-1:
            return '('+walk(trimmed[1:-1])+')'
        event=EVENT.match(trimmed)
        if event:
            return event[0]+' '+walk(trimmed[event.end():])
        words=top_words(trimmed)
        # Sequence and/or bind less tightly than intersect. Preserve that tree.
        for operator in ('or','and','intersect'):
            cuts=[token for token in words if token[0]==operator]
            if not cuts:
                continue
            parts=[]
            start=0
            for token in cuts:
                parts.append(trimmed[start:token.start()])
                start=token.end()
            parts.append(trimmed[start:])
            left=walk(parts[0])
            for raw_right in parts[1:]:
                right=walk(raw_right)
                repeated=repeated_boolean(left) if operator=='intersect' else None
                if repeated is None:
                    left=left+' '+operator+' '+right
                    continue
                clock,predicate=repeated
                before=left+' intersect '+right
                left='('+((clock+' ') if clock else '')+'('+predicate+') throughout ('+right+'))'
                edits.append(dict(rule='boolean-zero-repeat-intersection-to-throughout',
                                  before=before,after=left))
            return left
        # Recurse only into parenthesized subexpressions, not repeat bounds.
        output=[]
        cursor=0
        while cursor<len(trimmed):
            if trimmed[cursor]=='(':
                end=offsets[cursor]
                output.append('('+walk(trimmed[cursor+1:end])+')')
                cursor=end+1
            else:
                output.append(trimmed[cursor])
                cursor+=1
        return ''.join(output)

    rewritten=walk(expression)
    return (rewritten,edits) if edits else (expression,[])
