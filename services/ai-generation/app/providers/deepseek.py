"""DeepSeek-specific thinking and cache options; shared Chat Completions transport."""
from .openai_compatible import OpenAiCompatibleProvider


class DeepSeekProvider(OpenAiCompatibleProvider):
    @classmethod
    def gateway_options(cls, options, json_object):
        options.require_supported(reasoning=True, cache=True)
        if options.reasoning_effort is not None and (not options.reasoning or options.reasoning_effort not in {"low", "high", "max"}):
            raise ValueError("reasoning effort requires DeepSeek thinking and low/high/max")
        return {"reasoning": options.reasoning, "reasoning_effort": options.reasoning_effort,
                "cache_stable_prefix": options.cache_stable_prefix}

    def __init__(self, *, reasoning=False, reasoning_effort=None, cache_stable_prefix=False, **kwargs):
        if type(reasoning) is not bool or type(cache_stable_prefix) is not bool:
            raise ValueError("invalid reasoning/cache option")
        if reasoning_effort is not None and (not reasoning or reasoning_effort not in {"low", "high", "max"}):
            raise ValueError("reasoning effort requires DeepSeek thinking and low/high/max")
        extra = {"thinking": {"type": "enabled" if reasoning else "disabled"}}
        if reasoning_effort is not None:
            extra["reasoning_effort"] = reasoning_effort
        super().__init__(provider_name="deepseek", extra_body=extra,
                         cache_stable_prefix=cache_stable_prefix, **kwargs)
