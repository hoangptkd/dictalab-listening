"""One editable prompt release; frozen values retain saved-job and benchmark compatibility."""
from functools import cache
import gzip
import hashlib
import json
from pathlib import Path
from types import ModuleType

ROOT = Path(__file__).parent
CURRENT_NAME = "scenario_prompts_meaning_v4_15"
_HISTORY_HASH = "8eddc0adf8c65a5725c09551f1ac5d29ae450a2f3499cf7f4af4be63c9009ca6"


def _release(name, values):
    result = ModuleType(name)
    vars(result).update(values)
    return result


CURRENT = _release(CURRENT_NAME, json.loads((ROOT / "scenario-prompts.json").read_text(encoding="utf-8")))


@cache
def _history():
    data = (ROOT / "scenario-prompt-history.json.gz").read_bytes()
    if hashlib.sha256(data).hexdigest() != _HISTORY_HASH:
        raise ValueError("Historical scenario prompt artifact mismatch")
    return json.loads(gzip.decompress(data))


@cache
def historical_release(name):
    if name == CURRENT_NAME:
        return CURRENT
    try:
        return _release(name, _history()[name])
    except KeyError:
        raise ValueError("Unknown historical scenario prompt") from None
