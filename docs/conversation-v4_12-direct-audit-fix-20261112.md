# V4.12 — sửa kiểm chứng đáp án và đối chiếu phần chấm

Ngày: 12/11/2026. **Code đã sửa và qua kiểm thử; chất lượng AI thật chưa xác nhận vì Gemini 503.** Candidate chưa được kích hoạt.

## Thay đổi từ V4.11

| Vấn đề có bằng chứng | Thay đổi V4.12 |
| --- | --- |
| Audit đánh COMPLETE nhưng không có quote trong đáp án | Viết lại AUDIT_QUESTIONS, yêu cầu lấy quote trước, phân biệt thông tin được hỏi và thông tin thực sự được trả lời. Schema đặt quote/lý do trước hai verdict. COMPLETE thiếu quote vẫn bị validator từ chối; không tự đổi verdict thành đạt |
| Blind audit gộp hai lợi ích/lựa chọn thành một claim; backend coi hai part trỏ cùng claim là trùng | REVIEW_QUESTIONS đối chiếu từng part trực tiếp với nguồn và ý gốc. Không dùng số lần tham chiếu claim để suy ra trùng, gộp hoặc thiếu ý |
| Cần giữ khả năng bắt lỗi thật sau thay đổi | Mỗi part vẫn có sourceSupport, parentSupport, quote nguồn và lý do. Vẫn kiểm tra ý parent bị thiếu, part gộp nhiều ý, cặp part trùng nội dung, certainty và speech act. Part trùng nguyên văn còn được phát hiện bằng code |

Blind audit vẫn là đầu vào đối chiếu nguồn độc lập; không còn được coi là cách phân chia ý duy nhất. Hai phần có chung chủ ngữ hoặc nằm trong cùng một claim rộng không tự trở thành trùng. Đây là thay đổi ở quan hệ kiểm chứng, không phải bỏ cờ kiểm duyệt để bài qua.

Giữ nguyên:

- Default V3; source/hash V4.11 và các phiên bản lịch sử.
- GENERATE/QUESTIONS/TRANSLATE/GRADE của V4.11; chấm từng part và tổng hợp PARTIAL bằng code.
- Trả lời Anh, Việt hoặc trộn; câu hỏi nhiều chi tiết.
- Bảy calls thường, tối đa mười một; deadline, format retry có giới hạn, scoped repair và lỗi provider.
- ID/quote/schema fail closed, semantic findings advisory; không thay policy, database, UI/audio, model hoặc provider routing của ứng dụng.

Candidate: `SCENARIO_MEANING_ASSESSMENT_V4_12`.
Hash Python/Core: `77d9ca8f29ef2be302741441bce8610f0502bcb7849071c1ae6d66dec78114f1`.

## File và luồng đã đồng bộ

- `scenario_prompts_meaning_v4_12.py`: chỉ thay hai stage audit/review liên quan.
- `scenario_assessment_audit.py`: schema riêng của candidate, kiểm tra trực tiếp part và chuẩn hóa verdict; không sửa cách chuẩn hóa lịch sử.
- `scenario_assessment.py`, `scenario_assessment_harness.py`: chọn đúng schema/pin/normalizer cho candidate; hash và runtime cùng dùng `models_for`.
- `ScenarioAssessment.java`: exact version/hash, parts policy và call envelope hiện có.
- Tests Python và Java: regression về claim rộng, lỗi nguồn/parent/thiếu ý/gộp ý/trùng thật, malformed IDs/quotes, no-upgrade, format retry và repair; pin tồn tại xuyên suốt luồng session/grade/completion.

## Kiểm thử offline

| Bộ kiểm tra | Kết quả |
| --- | --- |
| Python direct/evidence/independent audit | 105 passed |
| Python parts, assessment, harness, harness v9, workflow, contract | 263 passed |
| Tổng Python, hai tập module không trùng | **368 passed** |
| Java Assessment, Harness, EngineIntegration, Content | **124 tests, 0 failure/error/skipped** |
| UTF-8 trên chín tệp task, Python compile, `git diff --check` | Đạt |

Test regression quan trọng: cùng hai part riêng biệt phải được chấp nhận dù blind ledger biểu diễn bằng một claim rộng hoặc hai claim hẹp. Ngược lại, missing source, certainty/speech act sai, part ngoài parent, thiếu agreement, bundled part và duplicate thật vẫn hạ đúng cờ. Quote rỗng khi COMPLETE, ID thiếu/sai, cặp duplicate không hợp lệ vẫn là lỗi hợp đồng.

Đây là kiểm thử logic với verdict mô phỏng, không được dùng để tuyên bố model đã hiểu đúng F1–F5. Java dùng môi trường kiểm thử cô lập; không restart Core/worker hoặc đăng nhập thêm phiên QA.

## AI thật: dừng vì provider không sẵn sàng

Tái dùng đúng hai map negative/positive của lần V4.11. Cùng `gemini-3.5-flash-lite`, temperature 0, không retry/fallback. Chốt tối đa sáu POST; thực tế hai POST:

| Stage | Kết quả | Usage được provider trả |
| --- | --- | --- |
| Negative / AUDIT_MEANINGS | HTTP 200, hợp đồng hợp lệ | 569 input + 892 output = 1.461 tokens |
| Negative / AUDIT_QUESTIONS | **HTTP 503 UNAVAILABLE**, model đang chịu tải cao | Không có usage; không suy là chi phí bằng 0 |
| Negative / REVIEW_QUESTIONS | Chưa chạy | — |
| Positive / toàn chuỗi | Chưa chạy do dừng sau lỗi provider | — |

**F1–F5 và false rejection trên mẫu tốt chưa đo được ở V4.12.** Không gọi thêm để lấy một lần PASS, không thay model trong phép so sánh. Không tiếp tục sinh QUESTIONS hoặc full-flow khi gate chưa có kết quả.

[Experiment Langfuse](https://jp.cloud.langfuse.com/project/cmvx6vyks000iad0j2ph4qdbu/experiments/results?baseline=b015d42631411b28), [trace chứa hai generation observations](https://jp.cloud.langfuse.com/project/cmvx6vyks000iad0j2ph4qdbu/traces/b5b7a45e03209d29567b3ada65015ef8). Đã xác minh lại qua CLI.

Lưu ý công cụ đo: payload 503 là JSON array. Hook ghi receipt cũ gọi `.get` trên array nên summary ghi thêm AttributeError; raw HTTP/body đã lưu trước lỗi này và chứng minh nguyên nhân 503. Đã giữ nguyên runner thực thi trong `evaluate.executed.py`, sửa runner cho lần đo sau để đọc error array và ghi ca bị bỏ qua, không chạy lại. Gate score 0 của experiment gốc nghĩa là chưa có kết quả đủ điều kiện, **không phải độ chính xác ngữ nghĩa 0%**.

Evidence riêng, không đưa lên Git:
`data/conversation-meaning-assessment-20261106/direct-parts-v4_12-20261112/`.
Gồm input/prompt/schema/raw/status/token, source hashes, summary gốc, analysis phân loại 503, CLI verification và manifest SHA-256. Không sửa evidence V4.11 hoặc nhãn kỳ vọng.

## Trạng thái và bước kế tiếp

Chưa kích hoạt V4.12, chưa thay default V3, chưa deploy. Sửa code đã được kiểm chứng ở mức hợp đồng và regression; chưa tuyên bố cải thiện ngữ nghĩa bằng AI thật.

Khi provider sẵn sàng, chạy một experiment mới có giới hạn trên hai input đã đóng băng, giữ nguyên candidate/model và ghi riêng lần chạy. Chỉ sau khi bắt đúng năm lỗi và không loại nhầm positive mới kiểm tra QUESTIONS trên dialogue cũ, bộ grade đã lưu và full flow. Không cần sinh lại dataset để chẩn đoán hai lỗi này.

Phân biệt kiểm tra schema và kiểm tra ý nghĩa theo [tài liệu structured output của Gemini](https://ai.google.dev/gemini-api/docs/structured-output); dùng dữ liệu có nhãn để kiểm chứng reviewer theo [hướng dẫn Langfuse](https://langfuse.com/docs/evaluation/evaluation-methods/llm-as-a-judge).
