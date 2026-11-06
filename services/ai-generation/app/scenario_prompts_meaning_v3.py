"""V3 assesses the complete referenced meaning, including reasons and conditions."""
from .scenario_prompts_meaning_v2 import (
    INSTRUCTIONS as V2_INSTRUCTIONS, POLICY_VERSION, HARNESS_POLICY, OUTPUT_TOKENS,
)

PROMPT_VERSION = "SCENARIO_MEANING_ASSESSMENT_V3"

_COVERAGE = """
A question's prompt and reference answer must elicit ALL essential information in every referenced
meaningEn, including its reason, condition or contrast when present. Merely quoting the whole source
does not make a narrower question cover that whole meaning. Do not count information supplied in
the question itself as learner evidence. Broaden an incomplete question/reference answer or reference
an appropriately scoped important meaning. Multi-detail meanings and questions remain valid.
"""

INSTRUCTIONS = {**V2_INSTRUCTIONS,
    "QUESTIONS": V2_INSTRUCTIONS["QUESTIONS"] + _COVERAGE,
    "REVIEW_QUESTIONS": V2_INSTRUCTIONS["REVIEW_QUESTIONS"] + _COVERAGE + """
Check the question/reference answer against the full meaningEn behind each meaningId.
If either tests only one side of a benefit-and-drawback meaning, or omits its essential reason or
condition, set questionMatchesFact=false and explain the uncovered part in reasonVi. A supported
answer to a narrower question is insufficient evidence of full meaning coverage.
""",
    "REPAIR_QUESTIONS": V2_INSTRUCTIONS["REPAIR_QUESTIONS"] + _COVERAGE,
    "GRADE": V2_INSTRUCTIONS["GRADE"] + """
<complete_meaning>
Judge each complete meaningEn, not just the topic, overlapping keywords or a narrower reference
answer. Check its essential parts, such as the action AND reason, benefit AND drawback, or stated
condition. CAPTURED requires learner evidence for all these parts; never fill a missing part from
the transcript, question wording, reference answer, plausibility or another person's knowledge.
Correctly recalling an action or preference while omitting its essential reason is PARTIAL.
For CHECKS, a complete answer to a question that asks only part of its referenced meaning is still
PARTIAL for that meaning. Credit only what the learner actually supplied. For RECAP, no relevant
evidence is UNASSESSED; some correct evidence with an essential part absent is PARTIAL.
Concise paraphrases can fully express several parts. Do not require exact wording or split every
meaning into an atomic fact. Keep uncertainty and contradictions distinct from complete evidence.
</complete_meaning>
<contrastive_examples>
Meaning: The meeting was moved to Friday because the room is being repaired.
Recap: "Họ chuyển buổi họp sang thứ Sáu." -> PARTIAL; quote that sentence: the reason is absent.
Recap: "Họ dời cuộc họp sang thứ Sáu vì phòng đang sửa." -> CAPTURED; quote that sentence.
Recap: "Họ nói về buổi họp." -> UNASSESSED with no evidence: neither the change nor its reason is recalled.
Question: "Buổi họp được chuyển sang ngày nào?" Answer: "Thứ Sáu." -> PARTIAL for this same meaning,
with the answer as evidence; the question did not elicit the repair reason, so do not invent its recall.
</contrastive_examples>
""",
}
