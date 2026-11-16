"""One editable prompt release; frozen values retain saved-job and benchmark compatibility."""
from functools import cache
import gzip
import hashlib
import json
from pathlib import Path
from types import ModuleType

ROOT = Path(__file__).parent
CURRENT_NAME = "scenario_prompts_meaning_v5_1"
_HISTORY_HASH = "cfd0df550f3ee3885f6f586970a74b2774ac9da28cc5f6e0cb626e4260768db0"


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
