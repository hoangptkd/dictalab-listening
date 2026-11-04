# Kế hoạch triển khai quality harness cho AI Conversation — 04/11/2026

## Mục tiêu và phạm vi

Triển khai MVP theo tám lớp dưới đây, tái sử dụng workflow Python, ModelGateway,
JobStore/outbox, bộ kiểm tra Java và Langfuse 5.1.0 hiện có. Không thêm framework,
dịch vụ, cơ sở dữ liệu, cache hay hạn mức sử dụng của học viên. Reviewer tiếp tục
là tư vấn: false, lỗi schema hoặc không có kết quả hợp lệ không tự động loại một
bài có cấu trúc hợp lệ. `qualityPassed=false` biểu thị còn vấn đề hoặc chưa biết.

V7 được giữ nguyên để xử lý công việc đã xếp hàng. V8 có fingerprint riêng, giữ
nguyên prompt/few-shot nền V7 và GRADE V5, đóng băng thêm schema patch, hướng dẫn
sửa, ngân sách và chính sách harness trong hash. Bật bản mới bằng quy trình pin và
phê duyệt release hiện có; việc đưa code lên GitHub không xác nhận triển khai.

## 1. Output Contract

- Task công khai giữ `activities`, `translations`, `qualityPassed`; không đưa đáp
  án hoặc báo cáo riêng vào API học viên.
- Câu hỏi chỉ được sửa bằng danh sách `{activityId, question}` với question theo
  schema cá nhân hóa hiện có. Bản dịch chỉ được sửa bằng `{activityId,segmentId,textVi}`.
- Chỉ chấp nhận đúng tập ID được cấp quyền; cấm ID lạ, trùng, thiếu, đổi thứ tự
  hoặc thay nội dung ngoài mục tiêu. Không nhận JSON Patch tùy ý hay cả bài mới.
- Ghi version/hash harness trong provenance riêng cùng kết quả; Java kiểm tra
  đúng pin, version, provider, model, số lượng và thứ tự cuộc gọi của V8.

## 2. Deterministic Validation

Giữ toàn bộ kiểm tra hiện có: schema, ID/thứ tự/số lượng, người nói, trích dẫn
nguyên văn cùng activity, evidence agreement có hai người theo thứ tự, kỹ năng,
inference, focus, rò đáp án và phủ bản dịch. Thêm chẩn đoán nội bộ có `code`,
`stage`, `path`, ID, `severity`, `repairable`. Lỗi toàn cục không xác định được
phạm vi an toàn vẫn là lỗi cuối. Word count chỉ là telemetry/hướng dẫn với V8.

Với câu hỏi sai cấu trúc nhưng parse được, định vị lỗi đầu tiên bằng chính validator
hiện có rồi chạy lại kiểm tra toàn bộ sau merge. MVP chỉ sửa target lỗi đầu tiên
trong nhánh deterministic; nếu còn câu sai khác sau merge thì không nhận candidate.
Nhánh semantic thu thập toàn bộ false flag có ID trong một lượt. Không tự thay evidence hoặc sửa
đáp án bằng regex. Bản dịch thiếu/trùng toàn cục không tự đoán mục cần thêm.

## 3. Semantic Evaluation

Chuẩn hóa hai reviewer hiện có thành PASS/FAIL/UNKNOWN. PASS chỉ khi tất cả gate
hiện có đạt. FAIL là review parse được và phủ đúng đối tượng nhưng có đánh giá
âm; UNKNOWN là schema/phủ đối tượng không hợp lệ hoặc reviewer không sẵn sàng.
Chỉ false flag gắn đúng ID mới cho phép đề xuất sửa; false rubric toàn cục giữ
là tư vấn. Nhận xét reviewer không phải nhãn chuẩn do người xác nhận.

Review lỗi định dạng được gọi lại đúng reviewer một lần với phản hồi schema;
không cắt reasonVi, ép true, sửa nội dung hay tái sinh hội thoại để làm đẹp kết quả.

## 4. Targeted Repair

- Bài tốt vẫn đúng năm cuộc gọi nền: GENERATE, QUESTIONS, REVIEW_QUESTIONS,
  TRANSLATE, REVIEW.
- Tối đa bốn cuộc gọi bổ sung tổng cộng, bao gồm patch, recheck và retry định
  dạng. Mỗi nhóm câu hỏi/bản dịch chỉ có một patch; không vòng lặp sửa lần hai.
- Giữ deadline gốc tối đa 240 giây, không reset deadline khi sửa. Thiếu budget
  để patch và recheck thì không bắt đầu sửa. Timeout/kết quả transport không chắc
  chắn tiếp tục làm job UNKNOWN theo semantics worker hiện có, không chạy lại cả
  job. UNKNOWN do schema/phủ review thuộc kết quả tư vấn của job hoàn tất; hai
  loại UNKNOWN này có phạm vi khác nhau.
- Prompt sửa nhận transcript nguồn, output sai thực tế, issues có vị trí và
  learningContext. Hội thoại và specification bất biến. Sửa bản dịch giữ câu hỏi.
- Merge trên bản sao và xác minh hash các mục ngoài phạm vi. Chạy lại toàn bộ
  validator rồi reviewer phụ thuộc. Chỉ nhận candidate khi review hợp lệ, không
  làm bất kỳ flag trước đó true thành false; target phải hết lỗi. Nếu recheck
  không hợp lệ hoặc suy giảm, giữ bản gốc hợp lệ và cảnh báo. Nếu bản gốc fatal
  và không có candidate hợp lệ sau sửa, tiếp tục loại bài.

- Patch không thay đổi hash được ghi `unchanged_patch`, giữ review ban đầu và
  không gọi recheck. Reviewer đổi ý không được tính là nội dung đã sửa thành công.

## 5. State & Dependency Management

State trong một execution gồm issues, snapshot/hash artifact, trạng thái gate,
attempt, quyết định nhận/từ chối và số extra call. Sửa câu hỏi chỉ vô hiệu hóa
REVIEW_QUESTIONS; sửa bản dịch chỉ vô hiệu hóa REVIEW. Reviewer không sửa bài.
Lưu báo cáo gọn trong envelope riêng hiện đã được Java giữ làm provenance; không
thêm bảng hay engine bền vững. Raw output và artifact input/output dùng cơ chế
capture/redaction hiện có, trace content chỉ bật theo cấu hình được phép.

Không có checkpoint resume sau crash trong MVP. Worker vẫn không tự chạy lại
workflow dở dang. Các lỗi cuối vẫn để lại trace từng attempt nếu telemetry bật.

`ProviderError` và timeout được truyền lên worker, nơi kết quả job là UNKNOWN;
không có fallback hoặc replay trong harness. JSON hỏng trước bước Pydantic cũng
là lỗi provider, không được retry định dạng bởi harness hiện tại. Chỉ object JSON
đã parse nhưng sai schema Pydantic hoặc phủ ID mới có một retry reviewer và có thể
kết thúc DONE với review UNKNOWN, `qualityPassed=false`. Recheck sau patch không
mở retry định dạng hay vòng sửa tiếp theo.

## 6. Evaluation & Regression Testing

Thêm test offline với snippet tổng hợp, tối giản từ bảy loại lỗi đã quan sát:
evidence nhầm activity, speechAct agreement sai evidence, lỗi chính tả bản dịch,
trích câu hỏi thay câu trả lời, sai sắc thái thời gian/thuật ngữ, reason reviewer
quá dài và đáp án Việt không tự nhiên. Kèm bài tốt, false-positive reviewer không
có target, patch vượt phạm vi, regression, budget/deadline, V7 replay, strict Java
protocol. Fixture do agent viết, không phải human-gold, không chép corpus riêng.

Lệnh offline từ `services/ai-generation`:

```powershell
python -m pytest tests/test_scenario_harness.py tests/test_scenario_workflow.py tests/test_scenario_personalized_fewshot.py
```

Java dùng Maven đã cài trên máy (repo không có wrapper), với lệnh PowerShell:
`mvn -q '-Dtest=ScenarioHarnessTest,ScenarioEngineIntegrationTest,ScenarioContentTest' test`.
Không gọi model/cloud
trong các test này. Benchmark 20 case mới chỉ chạy trong một đợt được yêu cầu
riêng, dùng cùng corpus và hàm `phase(folder,payload,settings,budget)` trong
`tools/ai-eval/scenario_quality.py`. Capture CLI đầu file hiện dựng
`scenario-job.v1`, nên không dùng lệnh CLI đó để tuyên bố đã benchmark V8.
Adapter cho đợt sau phải giữ corpus, context cá nhân hóa và thiết lập model của
đợt V7, dựng payload `scenario-job.v2` với `learningContext`, `programHash`,
`profile`, specification đúng schema và pin V8 ghi dưới đây. Trước khi chạy,
chọn `release = workflow.personalized_release(payload.promptHash)` và lưu
`instructions.json` từ `{**release.INSTRUCTIONS, "GRADE": release.GRADE}`;
không lấy `workflow.personalized_fewshot.INSTRUCTIONS` vì alias đó vẫn là V7.
`phase` ghi request theo số thứ tự và purpose thực tế, gồm toàn bộ instruction
của repair/recheck. Cũng lưu source/hash V8 và harness cùng manifest/context.
Ghi riêng structure pass, semantic PASS/FAIL/UNKNOWN, repair accepted/rejected,
harmful edit rate, extra calls, tokens và latency. Không suy diễn cải thiện từ
test tổng hợp. Run V7 thật trước đây có 20 EXTRACT đạt, 18 GENERATE hoàn tất,
2 loại; 13/18 được cả reviewer chấp nhận, 5 cảnh báo/review không hợp lệ.

## 7. Observability

Giữ một trace cho một job; mỗi model call, validation, patch và recheck là một
observation riêng. Giữ từng attempt thay vì ghi đè lịch sử gate. Ghi ngắn gọn
policy/hash, stage, target IDs, issue codes, old/new hashes, decision và lý do;
token/latency theo từng generation phân biệt base/extra. Không propagate dictionary
dài hoặc thông tin bí mật. Langfuse tùy chọn không được thay kết quả hoặc làm
hỏng thao tác. HTTP 200 chỉ chứng minh provider trả lời, không chứng minh chất lượng.

Tham chiếu SDK đã đọc trước triển khai: tài liệu chính thức
[trace best practices](https://langfuse.com/docs/observability/best-practices) và
[experiments](https://langfuse.com/docs/evaluation/experiments/experiments-via-sdk).
Không kích hoạt judge cloud mới hoặc đổi SDK. Export thực tế chỉ được báo đạt
khi có smoke kiểm chứng riêng; offline spy chỉ kiểm tra instrumentation.

## 8. Cost & Latency Optimization, rollout và rollback

Giới hạn repair là ngân sách nội bộ theo job, không phải quota học viên. Bài tốt
không thêm model call; không song song các bước phụ thuộc, không regenerate toàn
bộ. Tái sử dụng model reviewer và HTTP client, deadline/provider policy hiện có.

Rollout qua pin release V8 và phê duyệt engine/program hiện có. Chưa phê duyệt hoặc
chưa deploy thì không gọi là active production. Rollback bằng release process về
pin V7 `defd36c45a53aa6ac5002d0acb50996a5790fd87554646e36f9830dd3a225504`,
không sửa snapshot cũ; job đã có pin vẫn dispatch đúng release lịch sử.

Phase 2 chưa triển khai: semantic enforcement đã hiệu chỉnh với human-gold, sửa
hội thoại toàn cục và vô hiệu hóa mọi artifact phụ thuộc, sửa EXTRACT/GRADE,
durable resume/cache, chạy Q và dịch song song khi đo được lợi ích. Harness không
bảo đảm đúng ngữ nghĩa tuyệt đối; cần benchmark cùng corpus và xem từng mismatch.

## Bằng chứng triển khai và giới hạn kiểm chứng

- Pin V8 thực tế: `a98960f21fabe1f652f68fac72794c41e5c18870a400059cef4b03f50a9a838b`.
  Policy hash: `ac1a80c90c9f24149f14a9221c0c7382e653d94fccbd823b3862c55fa043bc6b`.
- Bộ test mới `tests/test_scenario_harness.py` chạy qua workflow thật với gateway
  giả lập theo stage: giữ payload lỗi thực tế, sửa có phạm vi, retry schema/phủ ID,
  chống regression, no-op, bất biến, ngân sách, deadline, V7 và privacy telemetry.
  False flag reviewer được giả lập; test không chứng minh model phát hiện đúng lỗi.
- Java `ScenarioHarnessTest` kiểm tra đường đi hữu hạn và cách đếm extra của nhánh
  deterministic/semantic. Integration kiểm tra phiên bản, provider/model, hash,
  số extra và job V7 đã xếp hàng.
- Kiểm tra đã chạy: `python -m pytest tests/test_observability.py tests/test_scenario_harness.py -q`
  đạt 38 test; `python -m pytest tests/test_scenario_workflow.py tests/test_scenario_personalized_fewshot.py -q`
  đạt 198 test (tổng hai lượt Python: 236 đạt).
  `mvn -q '-Dtest=ScenarioHarnessTest,ScenarioEngineIntegrationTest,ScenarioContentTest' test`
  đạt 63 test, không failure/error/skip. Encoding guard và Python compile đạt.
- Smoke Langfuse do agent điều phối thực hiện với dữ liệu tổng hợp và model stub:
  export HTTP 200; truy vấn lại thấy 18 observations (11 spans, 7 generations),
  gồm patch câu hỏi được nhận, 2 extra calls, recheck và lịch sử validation.
  [Trace smoke tổng hợp](https://jp.cloud.langfuse.com/project/cmvx6vyks000iad0j2ph4qdbu/traces/57f00af21361c4911f5918950dde6cba).
  Đây là kiểm chứng instrumentation, không phải benchmark chất lượng hay model call thật.
- Gate score cũ vẫn upsert theo ID ổn định; các attempt V8 có span riêng. Khi hoàn
  tất, root trace ghi deterministic PASS, kết quả quality và số repair được nhận,
  đồng thời xóa rejectedGate tạm thời. Hash/lý do cuối được giữ trong metadata
  scalar an toàn; nội dung output tiếp tục theo capture/redaction hiện có.
- Chưa chạy lại 20 case live, chưa đổi pin phê duyệt của engine/program trên máy
  production và chưa triển khai/restart dịch vụ trong đợt này.
