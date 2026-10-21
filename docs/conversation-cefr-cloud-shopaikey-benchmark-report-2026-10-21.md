# Báo cáo benchmark CEFR bằng model thật — 20261021-205451

- Trạng thái: **complete** — Supported arm captured in full; shadow arm is not in scope for this run. No B2/C1 quality claim is made.
- Provider/model: `shopaikey` / `claude-sonnet-4-5` (`17b71a0e4c344b9fa609de76765d649630be5fe8d774b9ee3f1d5d83c0790408`)

- Timeout: 600s
- HEAD: `dd44e0a062a84916d368242c95a9bd7829ee5dae` (codex/practice-drafts-audio-20261017)
- Cấu hình: {"primary_provider": "shopaikey", "production_mode": false, "fallback_enabled": false, "repair_enabled": false, "model": "qwen3.5:9b", "cloud_model": "claude-sonnet-4-5", "temperature": 0.3, "translation_temperature": 0.1, "num_ctx": 8192, "num_predict": 1000, "max_concurrency": 1}

## Đợt supported A1/A2/B1

Coverage 30/30.

| Mức | First length | First structure | Repair | Final valid | Final length (expected) | Semantic (AI) |
|---|---|---|---|---|---|---|
| A1 | 100.0% | 75.0% | 0 (ok 0) | 75.0% | 75.0% | 66.7% |
| A2 | 100.0% | 44.4% | 0 (ok 0) | 44.4% | 44.4% | 44.4% |
| B1 | 100.0% | 77.8% | 0 (ok 0) | 77.8% | 77.8% | 77.8% |

| Metric | Passed/evaluated |
|---|---|
| transport | 20/30 |
| schema | 20/30 |
| evidence | 20/30 |
| question_fact | 19/30 |
| translation | 20/30 |
| role | 20/30 |
| continuity | 16/21 |
| turn4_independence | 4/9 |

Latency p50/p95/p99/max: 17.3s / 45.3s / 50.4s / 50.4s

## So với baseline `20261021-184520` (attempt 1)

| Mức | Length Ollama | Length cloud | Structure Ollama | Structure cloud | Semantic Ollama | Semantic cloud |
|---|---|---|---|---|---|---|
| A1 | 100.0% | 100.0% | 83.3% | 75.0% | 50.0% | 66.7% |
| A2 | 66.7% | 100.0% | 100.0% | 44.4% | 55.6% | 44.4% |
| B1 | 66.7% | 100.0% | 88.9% | 77.8% | 44.4% | 77.8% |

Baseline có repair; cột cloud là attempt 1 (repair tắt), nên chỉ so attempt-1 rates.

## Bảng lỗi đã xác nhận

| Key | Mức | Nhóm lỗi | Ghi chú |
|---|---|---|---|
| cefr-v7-cafe-A1-t2:43 | A1 | no_task_or_rejected | Validator chặn: textVi 'nóng' nằm sẵn trong questionVi ('bánh mì nóng hay nguội') nên lộ đáp án; không có task cuối để chấm semantic. |
| cefr-v7-cafe-A1-t2:71 | A1 | non_atomic_answer | Fact 2 gộp 'trà nóng và bánh mì lạnh' trong khi câu hỏi chỉ hỏi đồ uống nóng hay lạnh; phần bánh mì là ý không được hỏi nên fact không atomic. |
| cefr-v7-directions-A1-t4:17 | A1 | no_task_or_rejected | Không có task cuối: hai fact textVi rỗng sau bước dịch, validator chặn. |
| cefr-v7-hotel-A1-t3:17 | A1 | no_task_or_rejected | Không có task cuối: cả hai fact có textVi rỗng. Chẩn đoán cùng dạng cho thấy bước dịch của Shop AI đôi khi trả khóa 'translationVi' trong từng fact thay vì 'textVi' mà schema yêu cầu. |
| cefr-v7-directions-A2-t2:17 | A2 | no_task_or_rejected | Không có task cuối: các fact textVi rỗng; bước dịch không trả đúng khóa textVi. |
| cefr-v7-directions-A2-t2:71 | A2 | no_task_or_rejected | Không có task cuối: các fact textVi rỗng, validator chặn ở bước dịch. |
| cefr-v7-hotel-A2-t4:17 | A2 | no_task_or_rejected | Không có task cuối: giờ ăn sáng và mật khẩu wifi đều bị textVi rỗng. |
| cefr-v7-hotel-A2-t4:43 | A2 | no_task_or_rejected | Không có task cuối: các fact textVi rỗng; lỗi dịch lặp lại ở lượt 4 mới. |
| cefr-v7-hotel-A2-t4:71 | A2 | no_task_or_rejected | Không có task cuối: giờ trả phòng và nơi ăn sáng đều bị textVi rỗng. |
| cefr-v7-cafe-B1-t4:17 | B1 | no_task_or_rejected | Không có task cuối: ba fact textVi rỗng sau bước dịch; nội dung tiếng Anh có món, cách chế biến và ý kiến nhưng không dùng được. |
| cefr-v7-hotel-B1-t2:43 | B1 | no_task_or_rejected | Validator chặn: segment 'Your room key…' viết hoa chữ Y trong khi transcript là 'your room key…', nên quote không phải substring chính xác; không có task cuối. |

## Kết luận

Gate supported: **FAIL** — Structure/grounding, length or AI semantic review is below the planned thresholds; manual review has not happened.
Không có release, không đổi trạng thái SUPPORTED, không chạy C2.

- First-attempt sai độ dài: {'A1': 0, 'A2': 0, 'B1': 0} trên mẫu dự kiến từng mức.
- Gate độ dài dùng final hợp lệ/expected (66.7%), nên mẫu bị reject làm gate fail dù first attempt có thể đúng độ dài.
- Repair: gọi 0 lần, thành công 0, còn 10 mẫu cuối bị reject.
- A1 final 75.0%, semantic AI 66.7%; A2 final 44.4%, semantic AI 44.4%; B1 final 77.8%, semantic AI 77.8%.

- Chưa có bằng chứng B2/C1 trong run này; shadow arm không nằm trong phạm vi.

## Giới hạn

- AI review by the same agent that ran the benchmark; not independent human adjudication.
- B1 supported arm has 9 samples only; A1/A2 cannot compensate for B1.
- 600s timeout is an offline quality setting; it does not prove the 180s default or production SLA.
- The observer records attempts but does not change prompts, seeds, schemas, options or repair count.
- The frozen bundle is pinned to Ollama qwen3.5:9b; this cloud run changes provider and model, so differences cannot be attributed to the prompt alone.
- Shop AI requests do not carry the Ollama seed option; each seed is a repeated case label, not a reproducible sampling seed.

## Việc tiếp theo

1. Owner manual review of the nine B1 samples (and any failing sample); AI verdicts are not approval.
2. Keep this run as provider evidence; do not promote or approve the bundle from a cloud result.
3. Run the shadow arm separately if B2/C1 evidence is needed; it cannot run on Shop AI.

