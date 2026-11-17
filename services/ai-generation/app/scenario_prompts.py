"""One editable prompt release; frozen values retain saved-job and benchmark compatibility."""
from functools import cache
import gzip
import hashlib
import json
from pathlib import Path
from types import ModuleType

ROOT = Path(__file__).parent
CURRENT_NAME = "scenario_prompts_meaning_v5_2"
_HISTORY_HASH = "41048fb25dcdcf713f49c1cc99528447f80e68665c2ea124308b3828f1a502e4"


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
    if name == "scenario_prompts_meaning_v5_3":
        return _release(name, json.loads((ROOT / "scenario-dynamic-release.json").read_text(encoding="utf-8")))
    try:
        return _release(name, _history()[name])
    except KeyError:
        raise ValueError("Unknown historical scenario prompt") from None
