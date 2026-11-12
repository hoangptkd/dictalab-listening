# Kiểm chứng chất lượng V4.11

Ngày: 12/11/2026. **Kết luận: chưa đạt; chưa đủ điều kiện thay V3.**

## 1. Phạm vi và phiên bản

Đây là candidate `SCENARIO_MEANING_ASSESSMENT_V4_11`, không phải bộ prompt GENERATE V4/V5 cũ.

- Prompt hash: `cc88eb362c99c0af4b480ce14d1caf9c3f990d192b8c970fba0b3c2bc7f0e6cd`.
- Model thực tế: `gemini-3.5-flash-lite`, temperature 0; không retry, không fallback.
- Dùng lại hai meaning/question maps đã đóng băng: một map chứa năm lỗi F1–F5 và một map đã sửa đúng. Mỗi map có ba activity, chín meanings và chín questions.
- Chỉ đo chuỗi AUDIT_MEANINGS → AUDIT_QUESTIONS → REVIEW_QUESTIONS. Không sinh lại hội thoại/dataset, không chạy training hoặc đánh giá audio/UI.
- Default V3 và hash V4.7 lịch sử giữ nguyên; không restart dịch vụ, kích hoạt candidate hoặc deploy.

Đây là phép kiểm chứng chẩn đoán trên một hội thoại đã lưu, không phải ước lượng tỷ lệ đúng trên mọi chủ đề.

## 2. Kiểm thử code

| Hạng mục | Kết quả |
| --- | --- |
| Python: assessment, audit, parts, harness, workflow, contracts, jobs, provider fallback/gateway | **411 passed** |
| Java: ScenarioAssessmentTest, ScenarioHarnessTest, ScenarioEngineIntegrationTest, ScenarioContentTest, AiUsageBudgetIntegrationTest | **134 tests; 0 failure/error/skipped; BUILD SUCCESS** |
| Candidate pin, default V3, hash V4.7, GRADE giữ nguyên | Qua kiểm thử |
| Quote/ID sai hoặc thiếu, verdict không được nâng kết quả audit, repair dựng lại ledger | Qua kiểm thử hợp đồng bằng mock |
| UTF-8 trên 13 tệp task, Python compile và `git diff --check` | Đạt |

Test code đạt chỉ chứng minh luồng kiểm tra hoạt động theo thiết kế; không chứng minh model hiểu đúng ngữ nghĩa.
Log Java: `data/conversation-full-audit-20261112/v411-java.log`.

## 3. Kết quả AI thật

Chốt tối đa sáu POST; thực tế dùng **năm POST, đều HTTP 200**. Negative dừng ở stage thứ hai vì đầu ra vi phạm hợp đồng; positive chạy đủ ba stage. Không có 429/503 hoặc lỗi tài khoản trong phép đo này.

| Input | Kỳ vọng | Kết quả thực tế |
| --- | --- | --- |
| Negative | Chỉ đúng F1–F5 và trả một review hợp lệ | `INVALID_CONTRACT`: AUDIT_QUESTIONS trả COMPLETE nhưng `answerQuotesVi=[]` ở hai part; chưa tới final review |
| Positive | Review hợp lệ, chấp nhận map đã sửa | Hợp đồng hợp lệ, nhưng sau chuẩn hóa `valid=false`; hai meanings bị đánh dấu trùng sai |

**Gate tổng thể không đạt.** Không lấy việc chặn một output thiếu quote làm bằng chứng đã hiểu đúng cả năm lỗi; cũng không cộng số cờ true thành tỷ lệ chất lượng.

### Từng lỗi đã biết

| Lỗi | Quan sát trên V4.11 | Kết luận |
| --- | --- | --- |
| F1 — a1/q1 hỏi lý do, target còn chấm cả sở thích đã cho | Audit nhận ra part sở thích NOT_REQUESTED, nhưng vẫn trả COMPLETE với quote rỗng | Có nhận ra phạm vi; đầu ra audit chưa đạt hợp đồng, chưa có kết luận cuối hợp lệ |
| F2 — a2/q3 hỏi lợi ích, target còn chấm hành động/thời lượng đã cho | Audit nhận ra part hành động NOT_REQUESTED, nhưng vẫn trả COMPLETE với quote rỗng | Cùng lỗi với F1; chưa chứng minh kiểm tra đáp án đầy đủ |
| F3 — biến “có thể tụt lại” thành chắc chắn | Final review không chạy vì stage trước lỗi | **Chưa đo được**, không kết luận đạt hoặc bỏ sót |
| F4 — parent có agreement nhưng parts thiếu agreement | Final review không chạy | **Chưa đo được** |
| F5 — gộp hai lợi ích độc lập vào một part | Final review không chạy | **Chưa đo được** |

### Các lỗi trên mẫu đúng

| Dataset input | Expected output | Actual output |
| --- | --- | --- |
| Positive a2/m2: ba part riêng gồm giải pháp đọc chung, hỗ trợ thành viên kém tự tin, thảo luận dễ hơn | Các part không trùng; `distinctMeaning=true` | Blind audit gộp hai lợi ích thành c2. Final review gắn cả p2 và p3 vào c2 với EQUIVALENT. Code thấy c2 dùng hai lần → `AUDIT_DUPLICATE:a2/m2/c2`, `distinctMeaning=false` |
| Positive a2/m3: hai lựa chọn đọc ở nhà hoặc đọc chung trong buổi họp | Hai lựa chọn là thông tin riêng; `distinctMeaning=true` | Blind audit để cả hai lựa chọn trong một c1. Final review gắn p1 và p2 vào c1. Code thấy c1 dùng hai lần → `AUDIT_DUPLICATE:a2/m3/c1`, `distinctMeaning=false` |

Trên positive, final reviewer thô trả toàn bộ cờ true. Backend hạ hai cờ distinctMeaning dựa trên ánh xạ claim/part; chín question checks còn true. Vì vậy đây là false rejection của **toàn chuỗi audit + normalization**, không phải final model tự phát hiện hai lỗi thật trong map.

## 4. Nguyên nhân gốc và hướng xử lý tiếp

### P1 — Quyết định coverage chưa tuân thủ bằng chứng

Prompt đã nói COMPLETE cần quote chứng minh toàn part. Model vẫn dùng bối cảnh câu hỏi để coi part là đủ, đồng thời không tìm được quote trong đáp án. Validator chặn đúng tại `validate_question_coverage`; không được bỏ yêu cầu quote để bài qua.

Cần kiểm chứng lại việc tách hai quyết định “được hỏi” và “được đáp án trả lời”. GIVEN/NOT_REQUESTED không tự làm answerStatus thành COMPLETE. Đây là vấn đề hiểu ngữ nghĩa, không phải chỉ thiếu một trường JSON.

### P1 — Audit độc lập chưa tách được các ý độc lập

`AUDIT_MEANINGS` chưa làm đúng giả định mà normalizer cần: một claim phải là một ý có thể nhớ độc lập. Final reviewer lại xác nhận EQUIVALENT cho cả hai part với cùng một claim rộng. `to_review` coi một claim được hai part dùng là trùng; quy tắc đó biến lỗi decomposition thành lỗi của bài đúng.

Cần sửa/kiểm chứng decomposition và quan hệ tương đương trước khi dùng số lần tham chiếu claim làm bằng chứng trùng ý. Không sửa bằng cách xóa cờ distinctMeaning hoặc bỏ mọi kiểm tra trùng lặp. Hai diagnostics mới `uncoveredMeaningEn`/`bundledPartIds` chưa chứng minh khắc phục được dependency này.

### Thứ tự kiểm chứng tiếp

1. Giữ nguyên raw, nhãn và candidate/hash đã đo; thay đổi tiếp phải có phiên bản/hash mới.
2. Giải quyết hai nguyên nhân trên; kiểm tra lại cả map lỗi lẫn map đúng trước khi chạy sinh bài.
3. Chỉ khi gate reviewer đạt mới kiểm tra QUESTIONS trên dialogue cũ và bộ grade đã lưu: thiếu ý → PARTIAL, đủ ý được hỏi → CAPTURED, tiếng Anh/Việt/trộn đều hợp lệ.
4. Sau đó mới mở rộng chủ đề, kiểm tra EXTRACT/GENERATE/full flow và quyết định có đáng giữ hai audit bổ sung hay không.

Lần này chỉ kiểm chứng; chưa chỉnh prompt để đuổi theo một lần PASS.

## 5. Chi phí, độ trễ và tracing

| Chỉ số | Giá trị |
| --- | --- |
| Provider calls | 5/6 tối đa; không retry/fallback |
| Input tokens | 11.236 |
| Output tokens | 10.569 |
| Tổng tokens | **21.805** |
| Negative elapsed | 9,103 giây đến lỗi hợp đồng |
| Positive elapsed | 22,646 giây cho ba audit/review |
| Chi phí tiền | Chưa xác minh đơn giá/thanh toán; không suy từ token thành tiền |

Langfuse SDK experiment: `DictaLab V4.11 frozen reviewer verification`, run `v411-20261112-six-post`.
Đã đọc lại qua CLI và xác nhận có đủ năm generation observations:

- [Negative: hai generation observations](https://jp.cloud.langfuse.com/project/cmvx6vyks000iad0j2ph4qdbu/traces/ccdaffd79eae95a483d0ccfffb8e19b9).
- [Positive: ba generation observations](https://jp.cloud.langfuse.com/project/cmvx6vyks000iad0j2ph4qdbu/traces/ff13c1623b23460cddd60fff8814b056).

Việc cần đối chiếu reviewer với dữ liệu gán nhãn, thay vì chỉ tin cờ valid, cũng phù hợp với [hướng dẫn hiệu chỉnh LLM judge của Langfuse](https://langfuse.com/docs/evaluation/evaluation-methods/llm-as-a-judge).

## 6. Hồ sơ có thể kiểm tra lại

Evidence riêng, không đưa lên Git:
`data/conversation-meaning-assessment-20261106/evidence-v4_11-20261112/`.

- `evaluation-started.json`: pin, model, source hashes, giới hạn calls và input hash.
- `frozen-inputs.json`: giữ nguyên negative/positive gốc; expected labels không gửi vào prompt.
- `*-*.started.json` / `*-*.result.json`: input đã project, system prompt, schema, raw response, HTTP, token và duration từng stage.
- `negative.result.json`, `positive.result.json`, `summary.json`: kết quả gốc, không ghi đè.
- `analysis.json`: phân tích lại kết quả đã lưu; không có provider calls mới.
- `*-langfuse-observations.json`: phản hồi CLI đã xác minh ở `body.data`.
- `sha256-manifest.json`: hash các tệp evidence để đối chiếu sau.
- `evaluate.py`, `check_traces.py`, `analyze.py`: công cụ riêng của phép đo. Không chạy lại evaluate trong cùng thư mục; nó dùng exclusive writes để tránh ghi đè hoặc tái gọi mù.

Hai lỗi của công cụ báo cáo được tách riêng khỏi chất lượng model:

1. Sau khi experiment kết thúc và lưu summary, in phần trang trí console gây UnicodeEncodeError trên Windows. Không mất raw/summary và không gọi lại provider.
2. Checker CLI ban đầu đọc nhầm envelope (`data` thay vì `body.data`); đã xác minh bằng phân tích offline chính phản hồi đã lưu. Không cần gọi AI hoặc tạo trace mới.

## 7. Quyết định

**Chưa thay V3 bằng V4.11.** Bằng chứng mới cho thấy việc thêm audit/quote đã chặn được output sai hợp đồng, nhưng chưa tạo ra reviewer đáng tin hơn đủ để phát hành: mẫu lỗi chưa hoàn thành kiểm tra, mẫu đúng bị hạ cờ sai.

Chưa chạy giai đoạn sinh bài/chấm/full UI tiếp theo vì gate chẩn đoán không đạt. Không công bố V4.11 tốt hơn V3 hoặc V4.9 trên nội dung tổng thể; các phép đo cũ khác phạm vi/model và lần này chỉ có một hội thoại.
