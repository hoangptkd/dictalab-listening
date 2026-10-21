# Báo cáo benchmark CEFR bằng model thật — 20261021-184520

- Trạng thái: **partial** — Supported arm complete; shadow arm stopped on owner request after 3/60 samples. The remaining shadow samples were not run and no quality claim covers them.
- Model: `qwen3.5:9b` (`56671c2ab9385f9cfcb404638e32cd62d88e3501d44822208363c010179a3c90`), timeout 600s
- HEAD: `9de9b26d7a04a7ad7084b73afca78ceb3b7cf0eb` (codex/practice-drafts-audio-20261017)
- Cấu hình: {"primary_provider": "ollama", "production_mode": false, "fallback_enabled": false, "model": "qwen3.5:9b", "temperature": 0.3, "translation_temperature": 0.1, "num_ctx": 8192, "num_predict": 1000, "max_concurrency": 1}

## Đợt supported A1/A2/B1

Coverage 30/30.

| Mức | First length | First structure | Repair | Final valid | Final length (expected) | Semantic (AI) |
|---|---|---|---|---|---|---|
| A1 | 100.0% | 83.3% | 2 (ok 1) | 91.7% | 91.7% | 50.0% |
| A2 | 66.7% | 100.0% | 3 (ok 1) | 77.8% | 77.8% | 55.6% |
| B1 | 66.7% | 88.9% | 4 (ok 2) | 77.8% | 77.8% | 44.4% |

| Metric | Passed/evaluated |
|---|---|
| transport | 25/30 |
| schema | 25/30 |
| evidence | 25/30 |
| question_fact | 20/30 |
| translation | 25/30 |
| role | 19/30 |
| continuity | 17/21 |
| turn4_independence | 8/9 |

Latency p50/p95/p99: 84.7s / 204.8s / 204.8s

## Đợt shadow B2/C1 (một phần)

Coverage 3/60 — còn 57 mẫu chưa chạy. Tỷ lệ dưới đây tính trên mẫu **đã chạy**; tỷ lệ trên mẫu dự kiến nằm trong JSON.

| Mức | Capture | Final valid (captured) | Semantic (AI, captured) | Trong khoảng từ (present) |
|---|---|---|---|---|
| B2 | 3/30 | 66.7% | 33.3% | 100.0% |
| C1 | 0/30 | n/a | n/a | n/a |

Shadow gate: `{'releaseAllowed': False, 'reason': 'shadow_only_requires_separate_promotion_review'}`; candidateGatePassed: {'B2': False, 'C1': False}

## Bảng lỗi đã xác nhận

| Key | Mức | Nhóm lỗi | Ghi chú |
|---|---|---|---|
| cefr-v7-cafe-A1-t2:17 | A1 | question_misalignment, role_inconsistency | Câu hỏi dùng 'Bạn muốn gọi…' trong khi người học là nhân viên và transcript là đơn của khách: lệch vai, hỏi sai chủ thể. Đáp án vẫn có trong bài. |
| cefr-v7-cafe-A1-t2:43 | A1 | question_misalignment, role_inconsistency | Cùng lỗi lệch vai như seed 17: khách gọi món nhưng câu hỏi hỏi người học (nhân viên) muốn gọi gì. |
| cefr-v7-cafe-A1-t2:71 | A1 | question_misalignment, role_inconsistency | Lệch vai như hai seed trước; taskVi còn ghi 'danh sách món ăn mới' trong khi transcript là một lượt gọi món. |
| cefr-v7-directions-A1-t4:43 | A1 | no_task_or_rejected | Không sinh được task: attempt 1 và repair đều để textVi lộ trong questionVi, workflow reject. Không có bài để review. |
| cefr-v7-directions-A1-t4:71 | A1 | question_misalignment, unanswerable_question | questionVi là một câu kể lặp lại nội dung bài ('Hãy đi theo con phố chính. Bưu điện…') chứ không phải câu hỏi; lộ đáp án về mặt ngữ nghĩa dù validator chuỗi con không bắt. |
| cefr-v7-hotel-A1-t3:71 | A1 | question_misalignment, unanswerable_question | Câu hỏi 'wifi ở đâu' nhưng transcript chỉ nói mật khẩu Wi-Fi nằm trên thẻ chìa khóa, không nói vị trí wifi. Câu hỏi và đáp án lệch nhau. |
| cefr-v7-cafe-A2-t3:17 | A2 | role_inconsistency | Partner ghi là customer nhưng bài nói là lời nhân viên ('your order will be ready…'), đảo vai. |
| cefr-v7-cafe-A2-t3:43 | A2 | no_task_or_rejected | Không sinh được task: attempt 1 13 từ và evidence sai; repair vẫn 13 từ nên bị reject. |
| cefr-v7-cafe-A2-t3:71 | A2 | role_inconsistency | Một utterance trộn hai người nói: câu điều kiện của nhân viên và câu đổi món của khách. Partner chỉ là customer nên vai không nhất quán. |
| cefr-v7-directions-A2-t2:17 | A2 | no_task_or_rejected | Không sinh được task: attempt 1 17 từ (min 18), repair vẫn 17 từ nên bị reject. |
| cefr-v7-cafe-B1-t4:17 | B1 | role_inconsistency | Utterance trộn lời khách (ý kiến về salad) và lời nhân viên (ưu đãi tráng miệng). Partner là customer nên vai không nhất quán. Lượt 4 độc lập. |
| cefr-v7-directions-B1-t3:17 | B1 | no_task_or_rejected | Không sinh được task: attempt 1 và repair đều 27 từ (min 30) nên bị reject. |
| cefr-v7-directions-B1-t3:71 | B1 | no_task_or_rejected | Không sinh được task: attempt 1 23 từ, repair không cứu được; workflow reject. |
| cefr-v7-hotel-B1-t2:43 | B1 | unsupported_conclusion | Lý do không nhất quán: đội dọn phòng 'vừa dọn xong' lại là lý do phải chờ đến 2 giờ. Câu hỏi và đáp án khớp chuỗi nhưng lập luận không đứng được. |
| cefr-v7-hotel-B1-t2:71 | B1 | tasktype_mismatch | Fact 2 gắn nhãn inference_prediction nhưng transcript nói thẳng 'moved all rooms to the garden area'; đây là direct_detail mặc áo suy luận. Đây là mẫu repair: attempt 1 bị chặn vì 25 từ. |
| cefr-shadow-library-B2-t2:17 | B2 | non_atomic_answer, question_misalignment, translation_defect, unanswerable_question | Hai lỗi: (1) slot askVi hỏi 'ủng hộ việc mở rộng giờ mở cửa' nhưng người nói đề xuất 'maintaining current hours' — câu hỏi và đáp án ngược nhau; (2) textVi của fact 0 bị hỏng, chứa nguyên mảnh JSON '"evidenceEn":"…' nên bản dịch đáp án không dùng được. Validator hiện chỉ kiểm tra textVi không rỗng nên bỏ lọt. |
| cefr-shadow-library-B2-t2:43 | B2 | no_task_or_rejected | Không sinh được task: attempt 1 và repair đều bị validator chặn (xem shadow-attempts.jsonl); workflow hết 287s rồi reject. |

## Kết luận

Gate supported: **FAIL** — Structure/grounding, length or AI semantic review is below the planned thresholds; manual review has not happened.
Gate shadow: **NOT EVALUATED** — Coverage 3/60; shadow tool always returns releaseAllowed=false.
Không có release, không đổi trạng thái SUPPORTED, không chạy C2.

- First-attempt sai độ dài: {'A1': 0, 'A2': 3, 'B1': 3} trên mẫu dự kiến từng mức.
- Repair: gọi 9 lần, thành công 4, còn 5 mẫu cuối bị reject.
- A1 final 91.7%, semantic AI 50.0%; A2 final 77.8%, semantic AI 55.6%; B1 final 77.8%, semantic AI 44.4%.
- Chưa kết luận được độ phân biệt B1/B2/C1: shadow mới có 3/60 mẫu và B1 chỉ có 9 mẫu.

## Giới hạn

- AI review by the same agent that ran the benchmark; not independent human adjudication.
- B1 supported arm has 9 samples only; A1/A2 cannot compensate for B1.
- Shadow arm is partial (B2 3/30 captured, C1 0/30) and uses the compact 30-90 word format; it does not test long B2/C1 listening.
- 600s timeout is an offline quality setting; it does not prove the 180s default or production SLA.
- CPU-only inference; cold start is included in latency.
- The observer records attempts but does not change prompts, seeds, schemas, options or repair count.

## Việc tiếp theo

1. Resume the remaining 57 shadow samples with the same bundle, dataset, seeds and environment, writing a new raw capture plus sidecar, then merge by key with duplicate/missing checks.
2. Owner manual review of the 9 B1 samples and at least 6 B2 / 6 C1 once the shadow arm is complete; AI verdicts are not approval.
3. Fix the repair loop: 5/5 supported failures repeated the same length or leak error, wasting a second call.
4. Close the validator gap where fact textVi containing serialized JSON (shadow library seed 17) passes.
5. Tighten role handling: the role metric is 19/30; 6 samples actually invert customer/staff or mix two speakers in one utterance, 5 more have no output.

