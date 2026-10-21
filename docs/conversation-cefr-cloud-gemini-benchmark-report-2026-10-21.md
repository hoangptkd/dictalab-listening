# Báo cáo benchmark CEFR bằng Gemini — 20261021-222644

- Trạng thái: **complete** — Supported arm captured in full; shadow arm is not in scope for this run. No B2/C1 quality claim is made.
- Provider/model: `gemini` / `gemini-3.5-flash-lite` (`5fc72a1ca52038f24859bc4fc6d24e57f8b6e08e50184739d89fe3053c2a26b7`)
- Endpoint: `https://generativelanguage.googleapis.com/v1beta/openai` (OpenAI-compatible, Bearer auth)
- Timeout: 600s; `response_format` dùng `json_schema` với schema lấy từ frozen bundle.
- HEAD: `70aa549` (`codex/practice-drafts-audio-20261017`)

Google từ chối `gemini-2.5-flash-lite` và `gemini-2.5-flash` với key này ("no longer available to new users"). Chủ dự án đã chọn `gemini-3.5-flash-lite`; model này trả JSON đúng qua endpoint OpenAI-compatible và không tiêu token suy luận như các bản Flash không-Lite.

- Cấu hình: `primary_provider=gemini`, `production_mode=false`, `fallback_enabled=false`, `repair_enabled=false`, `model=qwen3.5:9b`, `gemini_model=gemini-3.5-flash-lite`, `temperature=0.3`, `translation_temperature=0.1`, `num_ctx=8192`, `num_predict=1000`, `max_concurrency=1`.

## Đợt supported A1/A2/B1

Coverage 30/30.

| Mức | First length | First structure | Repair | Final valid | Final length (expected) | Semantic (AI) |
|---|---|---|---|---|---|---|
| A1 | 100.0% | 91.7% | 0 (ok 0) | 91.7% | 91.7% | 83.3% |
| A2 | 88.9% | 100.0% | 0 (ok 0) | 88.9% | 88.9% | 88.9% |
| B1 | 100.0% | 88.9% | 0 (ok 0) | 88.9% | 88.9% | 77.8% |

| Metric | Passed/evaluated |
|---|---|
| transport | 27/30 |
| schema | 27/30 |
| evidence | 27/30 |
| question_fact | 27/30 |
| translation | 25/30 |
| role | 27/30 |
| continuity | 18/21 |
| turn4_independence | 9/9 |

Latency p50/p95/p99: 11.4s / 14.6s / 77.6s.

## So với baseline `20261021-184520` (Ollama, attempt 1)

| Mức | Length Ollama | Length Gemini | Structure Ollama | Structure Gemini | Semantic Ollama | Semantic Gemini |
|---|---|---|---|---|---|---|
| A1 | 100.0% | 100.0% | 83.3% | 91.7% | 50.0% | 83.3% |
| A2 | 66.7% | 88.9% | 100.0% | 100.0% | 55.6% | 88.9% |
| B1 | 66.7% | 100.0% | 88.9% | 88.9% | 44.4% | 77.8% |

Baseline Ollama có repair; cột Gemini là attempt 1 (repair tắt), nên chỉ so attempt-1 rates.

## So với baseline Shop AI `20261021-205451` (attempt 1)

| Mức | Final valid Shop AI | Final valid Gemini | Semantic Shop AI | Semantic Gemini |
|---|---|---|---|---|
| A1 | 75.0% | 91.7% | 66.7% | 83.3% |
| A2 | 44.4% | 88.9% | 44.4% | 88.9% |
| B1 | 77.8% | 88.9% | 77.8% | 77.8% |

Shop AI dùng `claude-sonnet-4-5`, cũng repair tắt trong run so sánh. Gemini có ít mẫu bị reject hơn nhưng vẫn không đạt gate; B1 semantic không cải thiện so với Shop AI.

## Bảng lỗi đã xác nhận

| Key | Mức | Nhóm lỗi | Ghi chú |
|---|---|---|---|
| cefr-v7-cafe-A2-t3:17 | A2 | no_task_or_rejected | First attempt chỉ 16 từ, dưới mức tối thiểu 18 của A2; validator chặn và không có task cuối. |
| cefr-v7-hotel-A1-t3:71 | A1 | no_task_or_rejected | Validator chặn `fact[0].textVi` vì lộ vào `taskVi`; không có task cuối. |
| cefr-v7-hotel-B1-t2:17 | B1 | no_task_or_rejected | `fact[1].evidenceEn` không phải substring chính xác của `utteranceEn`; không có task cuối. |
| cefr-v7-hotel-A1-t3:17 | A1 | translation_defect | Mật khẩu `guest one two three` bị dịch thành `guest một hai ba`, không giữ nguyên mã. Các kiểm tra khác đạt. |
| cefr-v7-directions-B1-t3:43 | B1 | translation_defect | `before boarding` dịch thành `trước khi lên tàu` trong ngữ cảnh xe buýt; từ chỉ phương tiện chưa chính xác. |

## Kết luận

Gate supported: **FAIL** — Structure/grounding, length hoặc AI semantic review dưới ngưỡng; manual review chưa diễn ra.
Không release, không đổi trạng thái SUPPORTED, không chạy C2.

- Không có repair; 3 mẫu bị validator chặn ngay attempt 1.
- A1 final 91.7%, semantic AI 83.3%; A2 final 88.9%, semantic AI 88.9%; B1 final 88.9%, semantic AI 77.8%.
- B1 vẫn là mức yếu nhất: 2/9 mẫu không có task hoặc bị đánh giá semantic fail.
- Kết quả này chỉ là bằng chứng provider; không dùng để promote bundle hoặc thay thế manual review.

## Giới hạn

- AI review by the same agent that ran the benchmark; not independent human adjudication.
- B1 supported arm has 9 samples only; A1/A2 cannot compensate for B1.
- 600s timeout is an offline quality setting; it does not prove the 180s default or production SLA.
- The observer records attempts but does not change prompts, seeds, schemas, options or repair count.
- The frozen bundle is pinned to Ollama qwen3.5:9b; this cloud run changes provider and model, so differences cannot be attributed to the prompt alone.
- Gemini requests do not carry the Ollama seed option; each seed is a repeated case label, not a reproducible sampling seed.

## Việc tiếp theo

1. Owner manual review of the nine B1 samples (and any failing sample); AI verdicts are not approval.
2. Keep this run as provider evidence; do not promote or approve the bundle from a cloud result.
3. Run the shadow arm separately if B2/C1 evidence is needed; it cannot run on a cloud provider.
4. Fix the password/code preservation and bus-boarding translation in a later prompt revision, then rerun without changing this evidence.
