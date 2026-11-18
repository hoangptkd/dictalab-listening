"""Provider measurements and an opt-in evaluation layout; no cache API calls."""
import hashlib
import json
import re

LAYOUTS = ("original.v1", "implicit.v1")


def measured(value):
    return type(value) is int and 0 <= value < 2**63


def usage_details(data):
    usage = data.get("usage")
    usage = usage if isinstance(usage, dict) else {}
    result = {target: usage[field] for target, field in
              (("input_tokens", "prompt_tokens"), ("output_tokens", "completion_tokens"))
              if measured(usage.get(field))}
    details = usage.get("prompt_tokens_details")
    details = details if isinstance(details, dict) else {}
    cached = details.get("cached_tokens", usage.get("prompt_cache_hit_tokens"))
    present = "cached_tokens" in details or "prompt_cache_hit_tokens" in usage
    result["cache_usage_status"] = "not-reported"
    if present:
        result["cache_usage_status"] = "invalid"
        if measured(cached) and "input_tokens" in result and cached <= result["input_tokens"]:
            result.update(cached_input_tokens=cached, uncached_input_tokens=result["input_tokens"] - cached,
                          cache_usage_status="hit" if cached else "zero")
    output_details = usage.get("completion_tokens_details")
    reasoning = output_details.get("reasoning_tokens") if isinstance(output_details, dict) else None
    if measured(reasoning) and reasoning <= result.get("output_tokens", -1):
        # OpenAI-compatible reasoning tokens are a subset, never added again to completion_tokens.
        result["reasoning_tokens"] = reasoning
    model = data.get("model")
    if isinstance(model, str) and re.fullmatch(r"[a-zA-Z0-9_.:/-]{1,128}", model):
        result["response_model"] = model
    return result


def fingerprint(value):
    # Preserve actual order, including schema answer-first ordering. Never include headers/credentials.
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, separators=(",", ":"),
                                     allow_nan=False).encode("utf-8")).hexdigest()


def request_details(body):
    systems = [m for m in body["messages"] if m.get("role") == "system"]
    return dict(wireRequestHash=fingerprint(body), systemHash=fingerprint(systems),
                schemaHash=fingerprint(body.get("response_format")),
                systemBytes=sum(len(m.get("content", "").encode("utf-8")) for m in systems),
                prefixHash=fingerprint(dict(model=body["model"], system=systems,
                                           response_format=body.get("response_format"))))


def request_messages(instruction, data, layout="original.v1"):
    if layout not in LAYOUTS:
        raise ValueError("unsupported request layout")
    if layout == "original.v1":
        return [dict(role="system", content=instruction),
                dict(role="user", content=json.dumps(data, ensure_ascii=False))]
    references = []
    if "\nEXAMPLES\n" in instruction:
        before, rest = instruction.split("\nEXAMPLES\n", 1)
        example, after = rest.split("\nINPUT\n", 1)
        instruction = before + "\nINPUT\n" + after
        references.append("<reference_examples>\n" + example + "\n</reference_examples>")
    if "\n<reference_examples>\n" in instruction:
        instruction, example = instruction.split("\n<reference_examples>\n", 1)
        references.append("<reference_examples>\n" + example)
    # Move whole examples, never their fields or their order. Current input stays the final user message.
    messages = [dict(role="system", content=instruction)]
    messages.extend(dict(role="user", content=reference) for reference in references)
    leading = ("learningContext", "profile", "specification", "dialogue")
    trailing = ("inputVi", "issues", "verificationContext", "repairContext", "formatFeedback")
    ordered = {key: data[key] for key in leading if key in data}
    ordered.update((key, value) for key, value in data.items() if key not in leading + trailing)
    ordered.update((key, data[key]) for key in trailing if key in data)
    messages.append(dict(role="user", content=json.dumps(ordered, ensure_ascii=False)))
    return messages
