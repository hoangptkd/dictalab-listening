"""Offline scoring only. Missing/invalid judge labels never count as passes."""
import json
import math

FLAGS = {
    "question_fact": ("questionFactsAligned", "answerable", "atomicFacts"),
    "translation": ("translationAccurate",),
    "role": ("rolesConsistent",),
    "continuity": ("continuity",),
    "turn4_independence": ("transfer",),
}


def score(task, context, verdict, transport="ok"):
    result = {"transport": transport == "ok", "schema": False, "evidence": False,
              **{name: None for name in FLAGS}, "verdict_status": "missing"}
    if isinstance(task, str):
        try:
            task = json.loads(task)
        except (ValueError, TypeError):
            task = None
    if isinstance(task, dict):
        facts = task.get("facts")
        limits = {"utteranceEn": 1200, "questionVi": 180, "taskVi": 180, "skill": 180, "translationVi": 1200}
        result["schema"] = (all(isinstance(task.get(k), str) and task[k].strip() and len(task[k]) <= limit for k, limit in limits.items())
                            and isinstance(facts, list) and 2 <= len(facts) <= (2 if context.get("difficulty") == "A1" else 3)
                            and all(isinstance(f, dict) and all(isinstance(f.get(k), str) and f[k].strip()
                                        and len(f[k]) <= limit for k, limit in (("textVi", 160), ("evidenceEn", 300))) for f in facts))
        slots = task.get("slots")
        if slots is not None:
            result["schema"] = bool(result["schema"] and isinstance(slots, list) and len(slots) == len(facts)
                                     and all(isinstance(s, dict) and all(isinstance(s.get(k), str) and s[k].strip()
                                                    and len(s[k]) <= limit for k, limit in (("kind", 64), ("askVi", 100))) for s in slots))
        result["evidence"] = bool(result["schema"] and all(f["evidenceEn"] in task["utteranceEn"] for f in facts))
    if verdict is None or isinstance(verdict, str) and verdict in ("timeout", "missing"):
        result["verdict_status"] = verdict or "missing"
        return result
    if isinstance(verdict, str):
        try:
            verdict = json.loads(verdict)
        except ValueError:
            verdict = "invalid_verdict"
    required = {f for fields in FLAGS.values() for f in fields}
    if not isinstance(verdict, dict) or any(type(verdict.get(f)) is not bool for f in required):
        result["verdict_status"] = "invalid_verdict"
        return result
    result["verdict_status"] = "valid"
    for name, fields in FLAGS.items():
        applicable = (name != "turn4_independence" or context["turnNumber"] == 4) and (
            name != "continuity" or context["turnNumber"] in (2, 3))
        result[name] = all(verdict[f] for f in fields) if applicable else None
    return result


def aggregate(rows):
    metrics = {}
    for name in ("transport", "schema", "evidence", *FLAGS):
        values = [r["metrics"][name] for r in rows if r["metrics"][name] is not None]
        metrics[name] = {"passed": sum(v is True for v in values), "evaluated": len(values),
                         "rate": sum(v is True for v in values) / len(values) if values else None}
    durations = sorted(r["seconds"] for r in rows if isinstance(r.get("seconds"), (int, float))
                       and math.isfinite(r["seconds"]) and r["seconds"] >= 0)
    return {"metrics": metrics,
            "latency_seconds": {f"p{p}": durations[max(0, math.ceil(len(durations)*p/100)-1)] if durations else None
                                for p in (50, 95, 99)},
            "verdict_status": {s: sum(r["metrics"]["verdict_status"] == s for r in rows)
                               for s in ("valid", "missing", "timeout", "invalid_verdict")}}
