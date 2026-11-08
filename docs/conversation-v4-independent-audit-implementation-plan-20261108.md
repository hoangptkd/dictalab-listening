# Plan: sửa năm lỗi kiểm duyệt meaning assessment V4

Ngày: 08/11/2026. Architect: GPT-6 Astra. Coding agent: GPT-6.1 Sol, reasoning high.
Baseline: commit `19b64bda43820d6055826a4f49953bb2bdff5a6f`, nhánh
`codex/fix-scenario-provider-503-20261106`.

## 1. Mục tiêu và phạm vi

Sửa khả năng phát hiện và sửa có giới hạn năm lỗi đã quan sát trong meaning map,
required parts và ngân hàng câu hỏi. Dùng một candidate mới `SCENARIO_MEANING_ASSESSMENT_V4_7`.
Giữ mặc định V3 trong vòng này; hoàn thành code không đồng nghĩa đủ bằng chứng kích hoạt.

- Trong phạm vi: audit độc lập, tổng hợp verdict bằng code, phạm vi repair, hợp đồng số
  lần gọi AI giữa Python/Core, kiểm thử và bằng chứng có thể tái sử dụng.
- Ngoài phạm vi: đổi model/provider, UI/audio, migration/regrade bài cũ, đổi aggregation,
  đổi độ khó/dataset, train, tạo 20/30/50 bài, bật production hoặc tắt máy tính.
- Không sửa các prompt/hash V1–V4.6 đã commit. Không lấy kết quả của một hash để công nhận hash khác.
- Giữ reviewer ở chế độ advisory đối với lỗi ngữ nghĩa: `qualityPassed=false` và findings
  phải trung thực. Không khôi phục quy tắc từ chối mọi bài chỉ vì reviewer trả cờ false.
  Schema/ID/evidence không hợp lệ vẫn là lỗi hợp đồng, không được giả thành PASS.

## 2. Current flow và bằng chứng

1. `scenario_assessment.execute` gọi `GENERATE`, sau đó chuyển sang
   `scenario_assessment_harness.generate`.
2. Harness gọi `QUESTIONS`, kiểm tra cấu trúc và quote bằng `validate_questions`, rồi
   `REVIEW_QUESTIONS`; sau đó `TRANSLATE` và `REVIEW`.
3. `QuestionReview` chứa meaning/question flags. `review_status` dựa vào các cờ này,
   `valid`, `issues` và độ phủ ID để quyết định PASS/FAIL/UNKNOWN.
4. `repair` chỉ sửa item được cho phép; `merge_patch` bảo toàn item khác;
   `reconcile` giữ lỗi chưa sửa. Hiện repair có thể thiếu câu hỏi phụ thuộc khi meaning đổi.
5. Core `ScenarioEngine.accept` và `validateHarnessCalls` kiểm tra pin, provider/model,
   thứ tự calls, 5–9 calls và harness v1. GRADE phải có đúng một call thật.
6. GRADE V4 chấm required parts; `derive_grade` tổng hợp trạng thái bằng code.
   Kết quả này vẫn phụ thuộc chất lượng meaning map/parts được tạo trước đó.

Các file đã kiểm tra:

- `services/ai-generation/app/scenario_assessment.py`: schema, `prompt_hash`,
  `project_input`, `validate_questions`, `execute`.
- `services/ai-generation/app/scenario_assessment_harness.py`: `review_status`,
  `generate.review`, `generate.repair`, `merge_patch`, `reconcile`.
- `services/ai-generation/app/scenario_prompts_meaning_v4_5.py`, `...v4_6.py`.
- `src/main/java/com/dictalab/scenario/ScenarioEngine.java`: `accept`, `validateHarnessCalls`.
- `src/main/java/com/dictalab/scenario/ScenarioAssessment.java`: pins, policy, grades.
- `src/main/java/com/dictalab/scenario/ScenarioRelease.java`: V3 admission giữ nguyên.

### Năm lỗi phải sửa

| Mã | Bằng chứng cố định | Sai lệch | Kết quả cần đạt |
|---|---|---|---|
| F1 | a1/q1 | Câu hỏi chỉ hỏi lý do; meaning yêu cầu cả sở thích và lý do | `questionMatchesFact=false`; sửa câu hỏi/đáp án để thật sự hỏi đủ target |
| F2 | a2/q3 | Câu hỏi cho sẵn hành động/thời lượng rồi chỉ hỏi lợi ích; target vẫn chấm phần cho sẵn | Phần cho sẵn là GIVEN, không phải REQUESTED; `questionMatchesFact=false` |
| F3 | a1/m2 | Nguồn nói có thể xảy ra; meaning/part biến thành chắc chắn | `meaningSupported=false`; giữ mức chắc chắn của nguồn trong mọi target |
| F4 | a3/m2 | Meaning có sự đồng ý nhưng required parts bỏ mất ý đó | `groundingSupported=false`; không được tính đủ meaning khi phần bắt buộc vắng mặt |
| F5 | a2/m2 | Một part gộp hai lợi ích có thể nhớ/thiếu độc lập | `distinctMeaning=false`; tách các claim độc lập thành parts, không bắt câu hỏi chỉ có một ý |

Nguồn bằng chứng private, không commit nguyên gói:
`data/conversation-meaning-assessment-20261106/required-parts-v4_4-collected/harness.json`;
`consistency-projection-v3_2-v4_6/{acceptance-summary.json,corrected-map.json}` cùng các
raw result `review-frozen-original-fields--SCENARIO_MEANING_ASSESSMENT_V4_6.json`.
V4.5 và V4.6 đã đo đều bỏ sót 5/5 loại lỗi. V4.6 sửa `formatFeedback` có hash khác và chưa đo AI.

## 3. Quyết định kiến trúc

### 3.1 Ba bước audit, với hai đầu vào thật sự độc lập

Giữ GENERATE, QUESTIONS, TRANSLATE, REVIEW và GRADE hiện tại của candidate V4.6.
Thay riêng reviewer V4.7 bằng chuỗi:

`AUDIT_MEANINGS -> AUDIT_QUESTIONS -> REVIEW_QUESTIONS`.

- `AUDIT_MEANINGS`: chỉ nhận `{activityId, meaningId, meaningEn}`. Phân rã parent thành
  các claim có thể được nhớ hoặc bỏ sót độc lập. Không nhận requiredParts, câu hỏi, đáp án,
  transcript, profile hoặc kết quả reviewer trước.
- `AUDIT_QUESTIONS`: chỉ nhận `{activityId, questionId, promptVi}`. Diễn giải thông tin
  người học thực sự phải cung cấp (`requestedEn`) và thông tin đề đã cho (`givenEn`).
  Không nhận meaning, parts, đáp án, transcript hay kết quả audit meaning.
- `REVIEW_QUESTIONS`: nhận hai kết quả đã cố định, source segments, meaning/parts,
  câu hỏi/đáp án và grounding/profile hiện có. Đối chiếu từng claim/part, thay vì chỉ
  xuất các cờ tổng quát. Backend tính verdict chuẩn hóa cho harness hiện tại.

Không gộp hai bước đầu vào một request: để chung parent và câu hỏi vẫn cho model nhìn
thấy target khi diễn giải câu hỏi, lặp lại điểm yếu của V4.6. Mỗi bước xử lý toàn bộ
ba activity trong một call; không gọi riêng từng câu hỏi. Chạy tuần tự để giữ deadline,
thứ tự calls và model-fallback state hiện có.

Hai call bổ sung là chi phí chủ động của thiết kế. Không đổi model hoặc tăng deadline
240 giây. Cần đo latency/tokens thực tế trước khi xem xét admission.

### 3.2 Schema nội bộ; không đổi schema bài học đã lưu

Tạo `scenario_assessment_audit.py` cho schema và đối chiếu này. Import từ
`scenario_assessment.py` theo cách lazy trong các hàm cần dùng để tránh vòng import.
Không sửa các lớp schema lịch sử đang tham gia hash cũ.

Các schema mới dùng StrictModel và giới hạn rõ:

1. `MeaningAudit`: `meanings` gồm đúng một row cho mỗi meaning được đưa vào.
   Row có `activityId`, `meaningId`, `claims` (1–6); claim gồm `id=c1..cN`,
   `textEn` tối đa 300 ký tự. Claim phải giữ actor, phủ định, modality, condition,
   speech act và quan hệ lý do. Không tách một proposition thành tên người/từ/ngày rời rạc.
2. `QuestionAudit`: `questions` gồm đúng một row cho mỗi câu hỏi; row gồm
   `activityId`, `questionId`, `requestedEn` (1–200 ký tự), `givenEn` (0–400 ký tự).
3. `AuditedQuestionReview`: mở rộng schema review riêng cho candidate:
   - Mỗi meaning check giữ các cờ hiện có và thêm `claimChecks`, `partChecks`.
   - Claim check: `claimId`, `sourceSupport=SUPPORTED|UNSUPPORTED|UNCERTAIN`,
     `evidence` (0–3 quote cùng schema Evidence hiện có).
   - Part check: `partId`, `claimIds` (0–6),
     `relation=EQUIVALENT|MISMATCH|UNCERTAIN`.
   - Mỗi question check giữ trường hiện có và thêm `partChecks`: một row cho MỖI
     `(meaningId, partId)` thuộc các meaning mà câu hỏi tham chiếu; row gồm
     `meaningId`, `partId`, `requestStatus=REQUESTED|GIVEN|NOT_REQUESTED|UNCERTAIN`,
     `answerStatus=COMPLETE|INCOMPLETE|CONTRADICTED|UNCERTAIN`.
   - Giữ `reasonVi` ở cấp meaning/question, tránh lặp đoạn giải thích ở mọi relation.

Giới hạn wire array vẫn dùng cơ chế bỏ nested array bounds hiện có; Pydantic và kiểm tra
coverage phải thi hành đầy đủ giới hạn local. Không giảm validation để model dễ pass.

### 3.3 Tổng hợp bằng code

`validate_*` kiểm tra đúng tập ID, không trùng, không thiếu/thừa row, không tham chiếu
cross-activity; quote phải là substring chính xác của source segment cùng activity.
SUPPORTED phải có evidence; UNSUPPORTED/UNCERTAIN không được giả thành supported vì có quote.
Model vẫn chịu trách nhiệm phán đoán ngữ nghĩa; code chỉ kiểm tra cấu trúc và tổng hợp.

`to_review` tạo `QuestionReview` lịch sử để tái dùng harness:

- Claim sourceSupport khác SUPPORTED làm `meaningSupported=false` và
  `groundingSupported=false` (F3).
- Parent claim không được part nào ánh xạ, hoặc relation khác EQUIVALENT làm
  `groundingSupported=false` (F4). Part không có claim tương ứng cũng không đạt.
- Một part ánh xạ nhiều claim độc lập, hoặc một claim bị lặp trong nhiều part, làm
  `distinctMeaning=false` (F5). Các proposition đơn như “dời họp sang thứ Sáu” vẫn là một claim.
- Bất kỳ required part nào không REQUESTED hoặc answerStatus không COMPLETE làm
  `questionMatchesFact=false` (F1/F2). `CONTRADICTED` còn làm `answerSupported=false`.
- Không nâng bất kỳ cờ false nào do reviewer trả về thành true. Các kiểm tra khác
  (skill, tiếng Việt, speech act, không lộ đáp án...) tiếp tục được giữ.
- `requestedMeaningEn` của review chuẩn hóa lấy từ QuestionAudit, không lấy bản model
  chép lại trong comparison. Giữ raw comparison để xem khác biệt.
- `valid` được tính lại từ toàn bộ cờ và issues, không tin `valid=true` của model.
  Khi code hạ cờ, thêm lý do có mã lỗi + ID/claim liên quan; không để reason nói “đạt”.

Không dùng regex “might/will/and” để tự kết luận ngữ nghĩa. Không hardcode tên người,
nội dung câu lạc bộ đọc sách hay các ID lỗi vào production.

### 3.4 Repair và thông tin độc lập sau repair

- Khi issue thuộc meaning: phạm vi sửa gồm meaning đó và mọi câu hỏi tham chiếu nó.
  Khi issue chỉ thuộc question: phạm vi sửa là question đó. Chỉ mở rộng trong cùng activity.
- Không thêm/xóa ID trong patch; dùng `merge_patch` và hash bảo toàn item ngoài phạm vi.
- Một lần sửa meaning có thể tách requiredParts, sửa modality hoặc thu hẹp meaning vào
  một target quan trọng có thật; câu hỏi liên quan phải khớp target sau sửa.
- Sau patch hợp lệ, chạy lại cả hai audit độc lập trên bản candidate hiện tại rồi so sánh.
  Không tái dùng ledger cũ sau khi meaning/question thay đổi.
- Audit độc lập không nhận prior findings/answer/parts; comparison và repair được nhận
  repairContext. `formatFeedback` được giữ chỉ cho stage đang sửa định dạng.
- Giữ `reconcile`: item không đổi không được tự mất một lỗi cũ; target đổi không được
  làm các cờ từng đạt chuyển thành lỗi. Không bỏ gate để chấp nhận patch.

### 3.5 Calls, ngân sách và provenance

Candidate mới có `scenario-meaning-harness.v2`; policy mới chứa tên/order các audit stages,
`maxExtraCalls=4`, một patch pass mỗi stage, một format retry cho cả chuỗi question audit,
deadline gốc, reviewer advisory. Tính hash mới từ policy thực tế.

- Luồng chuẩn: `GENERATE, QUESTIONS, AUDIT_MEANINGS, AUDIT_QUESTIONS,
  REVIEW_QUESTIONS, TRANSLATE, REVIEW` (7 calls).
- Semantic repair QUESTIONS cần dự trữ đủ 4 extra calls: patch + hai audit + comparison.
  Không đủ budget thì giữ findings, không bắt đầu patch nửa chừng.
- Structural repair trước review cũng ghi đủ 4 repair calls theo cách tính State hiện có;
  sau thành công không gọi lặp initial review. Các call base bị thay thế không được giả lập.
- Format retry chỉ gọi lại stage có output/schema không hợp lệ, giữ stage đã hoàn thành.
  Tối đa một retry định dạng cho cả chuỗi audit; retry dùng extra budget. Không retry provider
  timeout/unknown hoặc tự đổi model trong phép đo chất lượng.
- Nếu ledger/comparison vẫn thiếu coverage/schema sau format retry: dừng candidate bằng lỗi
  hợp đồng; không tạo verdict PASS hoặc tiếp tục bằng ledger một phần.
- Translation giữ grammar/budget cũ. Tổng số call thành công của GENERATE tối đa 11;
  GRADE/EXTRACT vẫn đúng một call.
- Core chỉ chấp nhận grammar 7–11 và policy v2 cho hash V4.7 mới. Tất cả hash cũ giữ grammar cũ.
- Manifest fingerprint thêm audit schema, response schema, projection version, derivation version,
  instructions, output tokens và policy. Đóng băng hash trước phép đo AI thật.
- Mức output tokens ban đầu: AUDIT_MEANINGS 6000, AUDIT_QUESTIONS 4000,
  REVIEW_QUESTIONS 16000; các stage khác giữ nguyên. Không tăng token budget động khi lỗi.

## 4. Execution tasks — giao GPT-6.1 Sol High

### T1. Đóng băng regression cases (trước production code)

Files: `tests/fixtures/scenario-independent-audit-regressions.json`,
`tests/test_scenario_assessment_audit.py` mới trong ai-generation.
Lưu trích đoạn hội thoại giả lập tối thiểu từ năm lỗi đã quan sát, đối chiếu corrected map.
Mỗi case có negative, positive sửa tối thiểu, expected flags và nhãn nguồn. Không sao chép
credentials/runtime metadata/account data; không đưa expected labels vào input gửi AI.
Thêm positive cho câu hỏi nhiều ý hợp lệ, paraphrase, proposition có thời gian và câu hỏi
“vì sao” được gắn đúng target lý do. Không dùng boolean mock PASS để tuyên bố chất lượng AI.

Acceptance: năm lỗi có expected riêng trước khi sửa; regression có thể chạy offline.

### T2. Implement schema, projections và derivation

Files/symbols: module audit mới; `scenario_assessment.py` tại `prompt_hash`,
`selected_release`, `project_input`, `execute.call`; prompt `scenario_prompts_meaning_v4_7.py` mới.
Thực hiện mục 3.1–3.3, giữ mọi hash cũ và schemas persisted. GRADE lấy nguyên V4.6 và chỉ nhận
learner projection hiện có; không đưa đáp án/source trở lại grading.

Tests: input-blindness qua capture gateway, duplicate/missing/cross-activity IDs, unanchored
quotes, parent coverage, bundle detection, REQUESTED/GIVEN, incomplete answer, recalculated valid,
không nâng false flag, historical hashes và đúng một call GRADE.

### T3. Nối harness và dependency-aware repair

Files/symbols: `scenario_assessment_harness.py` tại `generate.review`, `repair`,
`structural` nếu cần; giữ `merge_patch`/`reconcile` semantics. Tái dùng `h.State`, không tạo
retry framework mới. Chỉ branch theo release protocol của V4.7.

Tests: luồng chuẩn 7 calls; semantic patch 11; structural patch; budget không đủ;
format retry ở từng audit/comparison nhưng tối đa một lần; exact formatFeedback;
regenerate ledger sau patch; dependent question scope; unrelated item immutability;
invalid patch; không làm mất lỗi chưa sửa; UNKNOWN/503 không được coi là PASS.

### T4. Đồng bộ Core pin và grammar

Files/symbols: `ScenarioAssessment.java` pins/policy; `ScenarioEngine.accept`,
`validateHarnessCalls`; tests `ScenarioAssessmentTest`, `ScenarioHarnessTest`,
`ScenarioEngineIntegrationTest`. Dùng nhánh validation riêng theo hash V4.7, giữ nguyên parser cũ.

Tests: exact hash/version pairs; normal/repair/retry sequences hợp lệ; thiếu audit, reorder,
duplicate, sai extraCalls, sai policy/hash, vượt 11 calls bị từ chối; old V3/V4.6 vẫn nhận
payload cũ; persisted lesson/grade pins giữ nguyên qua reload và admission changes.
Không sửa `ScenarioRelease` default, file workflow-v3.sha256, database hoặc public API.

### T5. Quan sát và chẩn đoán

Tái dùng ModelGateway/observability hiện có: trace hai audit purposes, comparison và repair,
cùng job/prompt/program/model pins. Lưu hash ledger/comparison trong `qualityHarness.artifacts`;
giữ raw qua trace/evidence theo cấu hình redaction hiện hành. Không tạo evaluator online,
annotation queue, prompt-management migration hay nâng Langfuse SDK trong task này.
Lý do: cần sửa ứng dụng và đo frozen failures; infrastructure tracing hiện có đủ dùng.

Private receipts cần: original input, projected input, exact messages/schema, output/raw,
normalized flags, expected labels tách riêng, duration/tokens/error, prompt/policy/source hashes.
Marker bắt đầu phải exclusive; summary chỉ tính result files, không đếm marker thành call.

### T6. Kiểm thử và đo lại có giới hạn

1. Python focused: assessment/audit/parts và harness; sau đó suite liên quan contracts,
   workflow, jobs, observability. Không gọi provider trong unit tests.
2. Java: `ScenarioAssessmentTest,ScenarioHarnessTest,ScenarioEngineIntegrationTest,ScenarioContentTest`.
3. Kiểm tra UTF-8 toàn bộ file thay đổi, compilation và `git diff --check`.
4. Sau khi code và hashes đóng băng, chạy đúng hai chuỗi audit thật: original negative
   chứa năm lỗi và corrected positive đã lưu. Mỗi chuỗi tối đa ba calls, tổng tối đa sáu;
   cùng configured model, temperature 0, không fallback, không retry, không tạo hội thoại mới.
   Lưu đủ raw và expected trước call. HTTP 503/timeout/schema gap là unavailable, không là pass.
5. Gate: negative phát hiện cả F1–F5 đúng item/flag; positive không bị false rejection;
   independent inputs đúng whitelist; không chấp nhận unsupported/uncertain như supported.
   Nếu chưa đạt: ghi nguyên lỗi, không thêm vòng tuning hay bật candidate trong task này.

Các ca adjacent trong T1 là kiểm chứng offline của thuật toán. Hai phép đo AI thật không
chứng minh general accuracy hoặc chất lượng của mọi bài mới; full generation/learning flow
và ca mixed-language grading còn là gate riêng trước admission sau này.

### T7. Acceptance và GitHub delivery

Architect kiểm diff và raw mismatch, đặc biệt leakage giữa các audit, deterministic rollup,
dependency closure, budget, pin và prior-error retention. Coding agent không tự commit/push.
Parent chốt kết quả, cập nhật báo cáo kế hoạch cũ section 12 bằng link tới kết quả vòng này,
commit task-only và push nhánh hiện tại; verify remote commit và Actions. Không merge/deploy main.

## 5. Điều kiện dừng/escalation

- Cần đổi schema persisted, policy grade, mặc định V3, account/provider/model hay deadline:
  dừng thay đổi đó và báo Architect; không suy rộng authorization.
- Data/file không khớp frozen hashes, ambiguous label hoặc không phân biệt được claim độc lập:
  giữ raw, báo ca cụ thể; không sửa expected sau khi thấy output để biến fail thành pass.
- Cần thêm hơn sáu provider calls hoặc retry kết quả unknown: không thực hiện tự động.
- Không đủ schema compiler budget: báo raw lỗi; không xóa validation local hoặc đổi model.
- Hết budget/deadline repair: giữ findings và kết quả thật. Không báo “đã fix chất lượng” chỉ
  từ unit tests hoặc từ việc candidate được Core chấp nhận.

## 6. Nguồn phương pháp

Thiết kế dựa trên các raw failures và flow code ở trên. Langfuse được dùng để định hướng
đánh giá: tái dùng labeled examples, tách unavailable khỏi semantic failure, so sánh theo
version và giữ input/output quan sát được. Không dùng nguồn này như bằng chứng candidate sẽ đạt.

- https://langfuse.com/docs/evaluation/evaluation-methods/llm-as-a-judge
- https://langfuse.com/docs/evaluation/experiments/datasets
- https://langfuse.com/docs/prompt-and-skill-management/concepts
- https://langfuse.com/guides/cookbook/error-analysis-llm-applications

## 7. Open questions

Không có câu hỏi chặn triển khai. Nếu semantic audit mới vẫn bỏ sót lỗi, giữ V3 và báo kết quả;
đổi model/reviewer hoặc thay kiến trúc thêm phải là quyết định tiếp theo có bằng chứng.

## 8. Implementation results — 2026-11-08

GPT-6.1 Sol implemented T1-T5 and the offline part of T6. Production sources were frozen
before the parent-controlled provider measurement. No default admission, persisted lesson/grade
schema, historical prompt, aggregation, UI, audio, migration or workflow resource was changed.

### Frozen candidate and protocol

| Pin | Value |
|---|---|
| Prompt version | `SCENARIO_MEANING_ASSESSMENT_V4_7` |
| Prompt hash | `60b2c0ca9be856e3e5f7aa8f2f9626f305d7772e7908e0860cd126e7a3574868` |
| Harness version | `scenario-meaning-harness.v2` |
| Harness policy hash | `6feb75d68b466277e3bc3a9b1c636716569a0b21b55b3aa44a143b18f06d817d` |
| Blind projection | `blind-parent-and-prompt-ledgers.v1` |
| Relation derivation | `source-parent-parts-request-answer.v1` |

The candidate adds independent parent-only and prompt-only ledgers, strict relation coverage and
exact same-activity evidence validation. Code lowers the historical flags from those relations,
copies `requestedMeaningEn` from the blind question ledger, retains false reviewer signals and
uses explicit failure reasons. The comparison keeps the existing separated question/target
projection and receives both frozen ledgers. GRADE reuses V4.6 instructions and learner projection.
All historical hashes, wire schemas and default V3 are preserved.

The harness uses seven base calls and at most eleven actual successful calls. A semantic question
repair reserves four extras before starting. A structural question repair uses eight actual
calls with `extraCalls=4`: its patch and three audit calls replace the initial audit chain, in
the existing State accounting convention. One format retry is shared across the three initial
audit stages, retaining prior successful ledgers. A format retry leaves insufficient budget for
semantic question repair. Invalid ledger/comparison contracts fail the candidate; semantic false
flags remain advisory. Meaning repairs include every dependent question in the same activity.
Rechecks rebuild both ledgers, preserve unrelated items and retain prior unresolved flags.

The existing ModelGateway traces both audit purposes and the comparison. Reported artifact hashes
include the candidate question-artifact digest in their keys, so a rejected repair's ledger cannot
be confused with the retained original. No new online evaluation infrastructure was added.

### Offline verification

The committed fixture contains five sanitized fictional negative/minimal-positive pairs and four
adjacent positive shapes. F4 specifically omits the parent's agreement claim from its parts; F2
uses an explicit recommendation and reason. Tests verify relation normalization and contracts,
not the model's ability to infer those relations from language.

- Final frozen-pin Python assessment/audit/parts/harness verification passed 174 tests in 121.32
  seconds with one existing dependency deprecation warning. It includes the explicit HTTP 503
  no-retry case. Parent command from `services/ai-generation`:
  `python -m pytest tests/test_scenario_assessment_audit.py tests/test_scenario_assessment.py tests/test_scenario_assessment_parts.py tests/test_scenario_harness.py tests/test_scenario_harness_v9.py -q`.
- Final frozen-pin Java verification passed 115 tests: assessment 10, harness 6, engine integration
  88 and content 11, with zero failures, errors or skips. The integration test includes V4.7 lesson
  and grade pin persistence through reload and admission changes. Command:
  `mvn -q '-Dtest=ScenarioAssessmentTest,ScenarioHarnessTest,ScenarioEngineIntegrationTest,ScenarioContentTest' test`.
- The expanded related Python run completed with 408 passed and one pre-existing failure:
  `test_contract.py::test_schema_and_health_contract`. Installed FastAPI rejects the existing
  nested scenario discriminator mapping while constructing OpenAPI. The identical failure was
  reproduced from a clean external archive of baseline `19b64bda43820d6055826a4f49953bb2bdff5a6f`;
  it was not changed in this task. The run had 83 existing dependency/deprecation warnings.
- Python compilation, UTF-8 validation of the eleven implementation/test files and both report files, and
  `git diff --check` passed. The ignored private helper also compiled and passed UTF-8 checks.

### Bounded measurement preparation and remaining activation gates

The ignored private helper `data/conversation-meaning-assessment-20261106/independent-audit-v4_7/evaluate.py`
reuses the exact stored negative and corrected-positive inputs. It freezes labels separately from
provider inputs, hashes source/input/projection/schema provenance, stores exact messages and raw
responses, and uses actual production validators/normalization. Its `--check` path makes no provider
request or start marker. The actual run is exclusive, capped at one HTTP POST per stage and six
total, with no fallback or format retry and one original 240-second deadline per audit chain.
It verifies the protected QA identity and reads the local emergency-stop switch before each stage;
provider quota/authentication remain in ModelGateway. This read-only diagnostic guard is not full
Core API admission. The coding agent prepared the helper without running provider calls. The parent
ran `--check` successfully and executed the one authorized bounded diagnostic after source freeze.
The helper and all original receipts remain unchanged after that measurement.

| Frozen chain | Stage | Observed result |
|---|---|---|
| Original negative | AUDIT_MEANINGS | HTTP 200; valid meaning ledger |
| Original negative | AUDIT_QUESTIONS | HTTP 503; chain stopped |
| Corrected positive | AUDIT_MEANINGS | HTTP 200; valid meaning ledger |
| Corrected positive | AUDIT_QUESTIONS | HTTP 200; valid question ledger |
| Corrected positive | REVIEW_QUESTIONS | HTTP 503; chain stopped |

There were five actual provider POSTs, three HTTP 200 responses that passed their ledger contracts,
and two explicit HTTP 503 gaps. Negative comparison was never called. Neither chain produced a
normalized semantic verdict; detection of F1-F5 and positive semantic acceptance are **unavailable**.
The error receipts retain `httpStatus=503`; their response hook subsequently raised `AttributeError`
on the unexpected response body shape. The raw HTTP status is the evidence for provider unavailability.
The helper was not edited or replayed to repair that diagnostic error. No fallback, retry, extra
prompt-tuning round, new dialogue or additional provider call followed. Private evidence stays in
the ignored `independent-audit-v4_7/` directory; `summary.json` counts result files, not start markers.

**Do not activate V4.7.** Offline contract and call-protocol checks passed, but neither paid semantic
gate completed. Existing V4.5/V4.6 measured failures remain unchanged; this candidate has no completed
five-class detection result. Full generation/learning flow and mixed-language grading gates also
remain separate. The parent owns final acceptance and branch delivery; no production deployment
or local product full-flow/audio run was performed in this implementation task.

**Activation remains blocked by call-cost admission accounting as well as quality gates.**
`ScenarioOperations.create` currently reserves five calls for GENERATE;
`AiUsageBudget.reserveScenario` accepts envelopes only up to seven calls; and
`src/main/resources/db/migration/V49__scenario_learning_engine.sql` constrains `calls_reserved`
to 1-7. That existing accounting cannot represent V4.7's eleven-call envelope (and predates the
older nine-call harness). No budget admission or database migration was changed here. Resolve and
verify that accounting in a separately authorized task before admitting V4.7 product jobs. The
default V3 pin remains unchanged. Two frozen audit measurements do not establish general accuracy,
full generation/learning-flow quality, mixed-language grading quality or production readiness.
