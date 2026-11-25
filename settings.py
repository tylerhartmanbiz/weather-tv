"""Reads the video-site settings exactly like the TV app does.

docs/config-noclaude.json holds your settings; docs/config.json (the example) is used only if
that file doesn't exist. The format is JSON, but it may also have // and /* */ comments,
trailing commas, curly quotes (from text editors), and may leave out the outer { }.
"""
import json
import re
from pathlib import Path

DOCS = Path(__file__).resolve().parent / "docs"


def parse(text):
    text = text.replace("“", '"').replace("”", '"').lstrip("﻿")
    out, i, in_str = [], 0, False
    while i < len(text):
        ch, nx = text[i], text[i + 1] if i + 1 < len(text) else ""
        if in_str:
            out.append(ch)
            if ch == "\\" and nx:
                out.append(nx)
                i += 1
            elif ch == '"':
                in_str = False
        elif ch == '"':
            in_str = True
            out.append(ch)
        elif ch == "/" and nx == "/":
            while i < len(text) and text[i] != "\n":
                i += 1
            out.append("\n")
        elif ch == "/" and nx == "*":
            end = text.find("*/", i + 2)
            i = len(text) if end < 0 else end + 1
        else:
            out.append(ch)
        i += 1
    t = re.sub(r",\s*$", "", re.sub(r",\s*([}\]])", r"\1", "".join(out).strip()))
    if not t.startswith("{"):
        t = "{" + t + "}"
    return json.loads(t)


def path():
    p = DOCS / "config-noclaude.json"
    return p if p.exists() else DOCS / "config.json"


def load(p=None):
    """Your settings as a dict. Raises ValueError (without the file's contents) if unreadable."""
    p = Path(p) if p else path()
    try:
        return parse(p.read_text())
    except json.JSONDecodeError as e:
        raise ValueError(f"{p.name} has a mistake in it, so it isn't valid JSON "
                         f"(line {e.lineno}, column {e.colno} after removing comments)") from None
