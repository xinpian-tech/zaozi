#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
"""Small, deterministic retrieval for rvprobe prompt construction.

The corpus is restricted to reviewed framework API/documentation excerpts.
Design RTL, historical answers, witnesses and proof results are not retrieval
sources. The exact excerpts, source hashes and scores are saved with each run.
"""

from __future__ import annotations

import json
import hashlib
import math
import re
from collections import Counter
from dataclasses import asdict, dataclass
from pathlib import Path


TOKEN = re.compile(r"0x[0-9a-f]+|[a-z_][a-z0-9_]*|\d+", re.IGNORECASE)
REPO_ROOT = Path(__file__).resolve().parent.parent
FRAMEWORK_EXAMPLE_SOURCES = frozenset({
    "experiments/src/rag/FrameworkDataExample.scala",
    "experiments/src/rag/FrameworkGoalExample.scala",
    "experiments/src/rag/FrameworkPipelineExample.scala",
})
FRAMEWORK_SOURCES = frozenset({
    "experiments/rag/framework_contract.md",
    "utlib/src/UTGenerator.scala",
    "utlib/src/JasperGold.scala",
    "utlib/src/Stimulus.scala",
    "utlib/src/UvmSequence.scala",
    "utlib/src/Gen.scala",
}) | FRAMEWORK_EXAMPLE_SOURCES
STOP_WORDS = {
    "a",
    "an",
    "and",
    "as",
    "at",
    "be",
    "begin",
    "by",
    "case",
    "do",
    "else",
    "end",
    "for",
    "from",
    "if",
    "in",
    "is",
    "it",
    "of",
    "on",
    "or",
    "reg",
    "the",
    "then",
    "to",
    "when",
    "wire",
    "with",
}


@dataclass(frozen=True)
class RagDocument:
    id: str
    title: str
    source: str
    tags: tuple[str, ...]
    content: str
    source_sha256: str
    kind: str = "reference"


@dataclass(frozen=True)
class RagHit:
    id: str
    title: str
    source: str
    score: float
    matched: tuple[str, ...]
    content: str
    source_sha256: str
    kind: str = "reference"

    def json(self) -> dict:
        return asdict(self)


def tokenize(text: str) -> list[str]:
    """Tokenize identifiers while retaining both full and underscore parts."""
    out: list[str] = []
    for match in TOKEN.findall(text.lower()):
        candidates = [match]
        if "_" in match:
            candidates.extend(part for part in match.split("_") if len(part) > 1)
        out.extend(token for token in candidates if token not in STOP_WORDS and len(token) > 1)
    return out


def load_corpus(path: Path) -> tuple[int, list[RagDocument]]:
    raw = json.loads(path.read_text())
    if raw.get("scope") != "framework-only":
        raise ValueError("RAG corpus must declare scope=framework-only; historical-answer corpora are forbidden")
    version = int(raw["version"])
    documents: list[RagDocument] = []
    seen: set[str] = set()
    for item in raw["documents"]:
        doc_id = str(item["id"])
        if doc_id in seen:
            raise ValueError(f"duplicate RAG document id: {doc_id}")
        seen.add(doc_id)
        source = str(item["source"])
        if source not in FRAMEWORK_SOURCES:
            raise ValueError(f"RAG source is not an approved framework source: {source}")
        source_path = REPO_ROOT / source
        if source_path.resolve() != source_path:
            raise ValueError(f"RAG source must not redirect through a symlink: {source}")
        source_bytes = source_path.read_bytes()
        kind = item.get("kind", "reference")
        if kind not in ("reference", "example"):
            raise ValueError(f"Unknown framework record kind: {kind}")
        if item.get("whole_source") is True:
            if "content" in item or kind != "example" or source not in FRAMEWORK_EXAMPLE_SOURCES:
                raise ValueError("whole_source requires an approved example source and no content override")
            content = source_bytes.decode().strip()
        else:
            content = str(item["content"]).strip()
        if not content or content not in source_bytes.decode():
            raise ValueError(f"RAG content must be a verbatim framework excerpt: {doc_id}")
        documents.append(
            RagDocument(
                id=doc_id,
                title=str(item["title"]),
                source=source,
                tags=tuple(str(tag).lower() for tag in item.get("tags", [])),
                content=content,
                source_sha256=hashlib.sha256(source_bytes).hexdigest(),
                kind=kind,
            )
        )
    if not documents:
        raise ValueError(f"RAG corpus is empty: {path}")
    return version, documents


def retrieve(query: str, documents: list[RagDocument], top_k: int = 4) -> list[RagHit]:
    """Rank corpus records with BM25 plus a small explicit-tag bonus."""
    if top_k <= 0 or not query.strip():
        return []

    query_terms = Counter(tokenize(query))
    bodies = [
        tokenize(" ".join((doc.title, doc.title, " ".join(doc.tags), " ".join(doc.tags), doc.content)))
        for doc in documents
    ]
    doc_freq = Counter(term for body in bodies for term in set(body))
    average_length = sum(map(len, bodies)) / len(bodies)
    count = len(documents)
    k1, b = 1.5, 0.72
    ranked: list[RagHit] = []

    for doc, body in zip(documents, bodies, strict=True):
        frequencies = Counter(body)
        score = 0.0
        matched: list[str] = []
        tag_terms = set(tokenize(" ".join(doc.tags)))
        for term, query_frequency in query_terms.items():
            frequency = frequencies[term]
            if not frequency:
                continue
            inverse_frequency = math.log(
                1.0 + (count - doc_freq[term] + 0.5) / (doc_freq[term] + 0.5)
            )
            normalization = frequency + k1 * (
                1.0 - b + b * len(body) / max(average_length, 1.0)
            )
            term_score = inverse_frequency * frequency * (k1 + 1.0) / normalization
            score += term_score * (1.0 + 0.08 * min(query_frequency - 1, 2))
            if term in tag_terms:
                score += 0.35 * inverse_frequency
            matched.append(term)
        if score > 0:
            ranked.append(
                RagHit(
                    id=doc.id,
                    title=doc.title,
                    source=doc.source,
                    score=round(score, 6),
                    matched=tuple(sorted(matched)),
                    content=doc.content,
                    source_sha256=doc.source_sha256,
                    kind=doc.kind,
                )
            )

    ranked.sort(key=lambda hit: (-hit.score, hit.id))
    return ranked[:top_k]


def retrieve_diverse(
    queries: list[str], documents: list[RagDocument], top_k: int = 4
) -> list[RagHit]:
    """Retrieve one record per framework interface query, then deduplicate."""
    if top_k <= 0:
        return []
    selected: dict[str, RagHit] = {}
    for query in queries:
        for hit in retrieve(query, documents, top_k=1):
            previous = selected.get(hit.id)
            if previous is None or hit.score > previous.score:
                selected[hit.id] = hit
    return sorted(selected.values(), key=lambda hit: (-hit.score, hit.id))[:top_k]


def render_hits(hits: list[RagHit]) -> str:
    """Render retrieved records as bounded, clearly labelled prompt context."""
    if not hits:
        return "(RAG disabled or no framework reference matched.)"
    chunks = []
    for hit in hits:
        body = (f"Framework few-shot example (caller-supplied parameters; not a DUT answer):\n"
                f"```scala\n{hit.content}\n```") if hit.kind == "example" else f"Framework excerpt:\n{hit.content}"
        chunks.append(
            f"[{hit.id}] {hit.title}\n"
            f"Source: {hit.source}\n"
            f"{body}"
        )
    return "\n\n".join(chunks)
