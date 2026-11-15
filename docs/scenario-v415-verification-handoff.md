# V4.15 — kết quả kiểm thử và bàn giao

## Trạng thái và phạm vi

- Ngày 15/11/2026. Đây là đợt phòng lỗi đầu nguồn, chưa phải kết luận đã tăng chất lượng.
- Source: `C:/Users/HP/Desktop/English_dictition`.
- Branch: `codex/scenario-prevention-v415-20261115`.
- Candidate: `SCENARIO_MEANING_ASSESSMENT_V4_15`.
- Prompt hash: `f03dd35a2443d8a2ad69e9d3995de3638116b3749261f666b6f8c19f5b73ff89`.
- Harness policy: `scenario-meaning-harness.v3`, hash `5525e16ed488b6a1fc1277c024ba33781a5a4b90fbe2095a2f44a60169256d83`.
- V4.13 baseline: `b1f627bce2367904664a68ca2460e5ffd96868e1eb7fa93b244762673d6d3087`.
- V4.14: `66f97b88e087e528f71b6e1f7bcec1224361b2edea94623ce93e4b5c1cf1189b`.
- Chủ dự án đã duyệt kiểm thử ngày 15/11/2026. Đã chạy offline, khởi động local và chạy AI thật.
  Chưa browser test, activate hoặc deploy. Pin local cho người học vẫn V4.13.
- Không chạy/xóa file có sẵn `scripts/monitor-codex-shutdown.py`; không đưa vào commit.

## Kết quả kiểm thử ngày 15/11/2026

**Chưa chứng minh V4.15 tăng chất lượng; không kích hoạt candidate.**

| Kiểm tra | Kết quả |
|---|---|
| Python, 7 file test trong bước 1 | 172/172 đạt |
| Python toàn dịch vụ, chạy từ thư mục service | 883 đạt, 27 bỏ qua, 3 lỗi test được xử lý bên dưới |
| Chạy lại file few-shot lịch sử và 2 test import từ root | 75/75 đạt |
| Lệnh Python như CI tại root, gồm tools/model-serving/training | 1.137 đạt, 27 bỏ qua; 1 lỗi assertion few-shot lịch sử |
| Chạy lại file tools few-shot sau sửa assertion | 3/3 đạt |
| Java: ScenarioAssessment, Harness, EngineIntegration, EvaluationStudio | 131/131 đạt |
| Giao diện Evaluation Studio | 4/4 đạt |
| AI thật | Đã chạy 6/30 đầu vào mới × 2 bản = 12/60 bài |
| Sinh bài hoàn tất | V4.14: 4/6; V4.15: 5/6 |
| Cấu trúc đầu ra ban đầu đạt | V4.14: 3/6; V4.15: 4/6 |
| GRADE trên bài V4.15 vừa sinh | 9/12 khớp nhãn kỳ vọng; 3 ca trả lời đủ vẫn bị PARTIAL |

Lần chạy offline đầu phát hiện test Java mới thiếu khai báo IOException và test few-shot
lịch sử so sánh thứ tự khóa JSON sau khi lưu kho lịch sử. Đã sửa khai báo và so sánh giá trị JSON.
Chạy toàn dịch vụ sau đó phát hiện thêm một assertion few-shot cũ cùng lỗi thứ tự khóa,
và hai test teacher không import được `training` khi chạy từ thư mục service. Đã sửa assertion;
chạy lại cả file few-shot và hai test teacher từ repository root như CI: 75/75 đạt.
Không sửa code training, cài thêm dependency hoặc đổi prompt/schema/pin lịch sử để qua test.
Lệnh Python như CI tại root bao phủ thêm tools/model-serving/training: 1.137 đạt, 27 bỏ qua,
còn một assertion few-shot trong tools cũng phụ thuộc thứ tự khóa. Đã sửa so sánh giá trị JSON;
file đó chạy lại 3/3 đạt. Chưa chạy lại toàn bộ suite sau sửa cuối; các lượt full-suite ban đầu
không được gọi là xanh. GitHub run 43680666329 thất bại ở bước Python; log tải qua API trả 403,
nên lỗi assertion đã tái hiện ở local chưa được khẳng định là lỗi duy nhất trên runner GitHub.
Các cảnh báo deprecated asyncio và Java hiện có không làm test thất bại.

Corpus mới có 30 happy cases, chia 10 CEFR, 10 TOEIC, 10 IELTS. Hash:
`eeaa43f1837f6c01b93629582b374f1e083c271d6f007a7a472c5235165ddbc7`.
Đợt đo chỉ đến sáu case CEFR A1/A2/B1; 24 đầu vào còn lại chưa gọi AI, gồm toàn bộ TOEIC/IELTS.
Dừng theo điều kiện lỗi provider trong kế hoạch: V4.14 museum gặp 503 ở REVIEW_QUESTIONS trên
`gemini-3.1-flash-lite`. Trước đó fallback 3.5 → 3.1 do 503 đã được ghi nhận; không phải tắt fallback.
Không replay bài lỗi hoặc thay model/config giữa đợt. Bốn trong sáu cặp khác model thực trả về
ở ít nhất một stage, nên chưa thể quy chênh lệch hoàn tất cho prompt.

### Lỗi đã xác nhận bằng transcript và GRADE thật

| Case / câu hỏi | Phần chấm thừa | Trả lời đúng phạm vi | Thực tế |
|---|---|---|---|
| pottery a1/q1: Vì sao muốn học sáng? | Chọn buổi sáng, đã có trong câu hỏi | Vì phải chăm em buổi chiều | PARTIAL thay vì CAPTURED |
| pottery a2/q2: Mang khăn và tạp dề để làm gì? | Các vật cần mang, đã cho sẵn | Để giữ quần áo sạch | PARTIAL thay vì CAPTURED |
| volunteer a1/q1: Tham gia hoạt động gì vào sáng Chủ nhật? | Thời gian sáng Chủ nhật đã cho sẵn | Dọn dẹp sân khu phố | PARTIAL thay vì CAPTURED |

QUESTIONS vẫn đưa ngữ cảnh đã cho vào requiredParts; REVIEW_QUESTIONS và independent judge đều
bỏ sót ba lỗi. GRADE chấm theo targets đó nên người học mất điểm. Chín ca GRADE còn lại đạt:
Việt/Anh/kết hợp, sai địa điểm, bỏ trống, chưa chọn đáp án và hai ý đúng đủ/thiếu/sai một ý.
Đây là 12 ca do Codex lập nhãn theo transcript; không phải bộ chuẩn do con người duyệt.

Lỗi khác:

- V4.15 museum: không có meaning/question mang skill main-idea như focusSkill yêu cầu;
  gate trả QualityRejected. Đây là lỗi sinh/lựa chọn skill, không phải lỗi 503; chưa có target
  định vị nên bounded repair không tự đoán một item để sửa.
- V4.15 bicycle: hai câu thiếu coverage evidence của meaning, patch câu hỏi/meaning đã sửa qua
  validation. Các chỉ số accepted patch vẫn phải tách khỏi chất lượng ngữ nghĩa.
- V4.15 parcel: reviewer báo nhầm bản dịch đúng “Tôi thấy hộp của bạn…” thành lỗi chủ ngữ;
  repair chỉ thêm từ “đồ”. Không thể tính patch được nhận này là cải thiện nội dung.
- Independent judge từng trả evidence ghép bằng mũi tên không có nguyên văn trong input;
  validator giữ REVIEW_REQUIRED. Judge còn trả toàn bộ true cho artifact thiếu translation
  hoặc đang sai cấu trúc; các artifact đó không được tính PASS.
- V4.14 parcel: q2 thiếu evidence của meaning, patch chưa làm hết lỗi; V4.14 plants có
  mục tiêu giờ tưới lại gắn vào hành động tưới cây. Lỗi phạm vi không chỉ có ở candidate.

Các nhãn PASS tự động cuối là 3 bài V4.14 và 5 bài V4.15, **không phải số bài đã xác nhận đạt**.
Transcript inspection phát hiện lỗi trong các bài PASS; không dùng các nhãn này để kích hoạt.

### Bằng chứng riêng, không cần sinh lại dữ liệu

Thư mục: `C:/Users/HP/.dictalab/evaluations/v415-phase2-20261115/`.

- `cases.json`, `manifest.json`, `catalog.json`: corpus, UUID, pin, runtime/model/fallback và hash nguồn.
- Từng `caseId/v4.14|v4.15/`: request, record nguyên trạng có input/instruction/schema/output theo stage,
  initial artifact, judgment request/response và model thực trả về. Lỗi vẫn giữ nguyên.
- `summary.json`, `report.md`, `review-details.json`, `manual-review.json`: thống kê và đối chiếu transcript.
- `grading/cases.json`, `grading/*/request.json`, `response.json`, `verdict.json`, `summary.json`:
  đầy đủ 12 ca chấm với đáp án người học và kết quả thật.
- Chạy lại `summarize.py` chỉ đọc output đã lưu, không gọi AI. Không chạy lại `run.py`/`grade.py`.
  Runner có admission guard; tiếp tục sau lỗi phải chỉ chọn các input chưa gửi và lưu manifest mới.

Các bước tiếp theo cần ưu tiên phạm vi câu hỏi → requiredParts và độ nhạy reviewer với lỗi đó;
giữ evidence/skill gates. Không sửa prompt giữa benchmark này hoặc đổi pin đang dùng.
Local tiếp tục sẵn sàng tại `http://localhost:18082`; chưa tích hợp corpus tùy chỉnh này vào ledger
Core Studio, nên xem báo cáo riêng. Không tạo dữ liệu ledger giả để hiển thị đủ 30 bài.

## Đã triển khai trước kiểm thử

1. EXTRACT phân biệt nguồn, suy luận và nền hư cấu; không gán vai cho người chỉ muốn nghe.
2. GENERATE giữ một cuộc trao đổi liên tục; diễn biến theo mục tiêu, không ép thỏa thuận.
3. QUESTIONS lấy đáp án từ hội thoại trước khi lập mục tiêu chấm; không chấm thông tin đã cho.
4. Riêng V4.15 dùng 2–6 câu hỏi và meanings/activity. Giữ 3 activity, coverage, evidence, IDs,
   profile và maxQuestionsPerSession. Không dùng giảm số lượng để che mất nội dung quan trọng.
5. QUESTIONS/TRANSLATE nhận specification đã xác nhận; dịch giữ vai, sắc thái và ngữ cảnh.
6. Few-shot chọn lọc, có mẫu ngân hàng đầy đủ 2 câu/activity, nhiều ý, câu hỏi chỉ nơi chốn,
   suy luận, reviewer đúng/sai và lỗi dịch có vị trí. Nhãn là agent-authored, không phải human gold.
7. REVIEW_QUESTIONS kiểm tra đúng phạm vi; REVIEW chỉ rõ lỗi dịch từng segment và rubric tổng.
   GRADE giữ cách tổng hợp bằng code, chấp nhận Việt/Anh/kết hợp.
8. Sửa cuối: cờ tổng không chặn patch dịch đã định vị; scope câu hỏi đóng theo liên kết meaning.
   Ngoài scope bất biến, giữ lỗi nguồn chưa sửa, không ép PASS, không tăng lượt gọi.
9. Candidate đọc được trong evaluation catalog và Core nhận hash/schema/policy riêng.
   Không thay pin đang hoạt động, bài cũ, luật fallback, UNKNOWN hay bảo mật.

## Phase 2 — phạm vi đã sửa theo phê duyệt

Chủ dự án đồng ý các sửa đổi, **trừ A: chưa tối giản QUESTIONS**.
Giữ nguyên cả bốn ví dụ QUESTIONS, gồm mẫu đầy đủ ba activity; chỉ sửa chỉ dẫn có lỗi.

- QUESTIONS: phân biệt phản ứng tích cực, đồng tình quan điểm, chấp nhận hành động và thông báo
  xác nhận có sẵn. Không bắt mọi confirmation phải dựng lại một trao đổi proposal/acceptance.
  Bỏ cách cấm quá rộng việc hỏi về yêu cầu của nhân vật: hỏi giá thì chấm giá; hỏi nhân vật
  yêu cầu trợ giúp gì thì chính yêu cầu đó là mục tiêu hợp lệ.
- REVIEW_QUESTIONS: xác định câu trả lời từ transcript trước khi đối chiếu đáp án/targets;
  tách lỗi đáp án, scope/liên kết, evidence, skill/supportKind và speechAct theo cờ hiện có.
  Khôi phục hai ví dụ: parts thiếu địa điểm dù đáp án đúng; hai ưu điểm cùng chủ thể vẫn hợp lệ.
  Giữ chín cờ boolean của questionCheck, không thêm audit hay schema.
- REPAIR_QUESTIONS: dùng đúng issues/failedOutput thay cho repairContext không tồn tại ở đầu vào
  bước sửa; xác định lỗi rồi sửa đồng bộ các trường phụ thuộc trong allowedTargets.
  Trả đủ replacement nhưng echo nguyên item đã đúng; không sửa mọi item chỉ vì được phép.
  Thêm hai ví dụ: chỉ sửa đáp án sai, và sửa đồng bộ target/parts/câu hỏi khi chấm thừa giờ đã cho.
  Không đổi thông tin trực tiếp thành inference chỉ để vượt validator.
- Đợt Phase 2 không đổi prompt khác, harness, ngân sách, schema, fallback, bảo mật hoặc activation.
  Các thay đổi harness/schema ở phần trên thuộc đợt V4.15 trước, vẫn chưa kiểm thử.
- Hash candidate nháp trước Phase 2 là `419acae740a768635fccbbb31046c78c9adea265c58dd9142092eba8b23e229a`;
  chưa activation/benchmark. Hash mới ở đầu tài liệu thay thế bản nháp đó; không sửa pin V4.13/V4.14.
- Đã bổ sung test offline cho serialization few-shot, chín cờ review, phân biệt lỗi đáp án/target,
  replacement đúng scope, giữ nguyên item ngoài scope và evidence hợp lệ. Đã chạy đạt trong bộ 172 tests.

Lượt sửa trước chỉ kiểm tra tĩnh và đã dừng chờ duyệt. Lượt kiểm thử sau phê duyệt có kết quả
ở đầu tài liệu; không có bằng chứng đủ để kích hoạt hoặc tuyên bố chất lượng AI tăng.

## Chưa triển khai trong đợt này

- User đã cho phép sửa activity gốc và dựng lại dữ liệu phụ thuộc, nhưng chưa bật cơ chế này.
  Cần đo first-pass trước; nếu vẫn cần thì triển khai thành đợt riêng, đồng bộ envelope Python/Core.
- Chưa thêm retry cho schema QUESTIONS/TRANSLATE sai ngay đầu vào, review không định vị được,
  hoặc cơ chế nhiều vòng sửa. Không tăng deadline/chi phí để che lỗi sinh.
- Chưa đổi dashboard metric hay tạo evaluator Langfuse mới.
- Reviewer vẫn advisory: bài có cảnh báo không bị chặn ngữ nghĩa toàn cục.

## Kế hoạch gốc đã được duyệt — giữ để đối chiếu

Chủ dự án đã yêu cầu bắt đầu kiểm thử. Dùng runtime/model/fallback hiện có; không spawn agent.
Không bật candidate cho người học chỉ vì tests code pass.
Kết quả cần tách: đúng cấu trúc, chất lượng nội dung, reviewer chính xác, repair hiệu quả.

## Bước 1 — tests offline, không gọi AI

Đọc diff và AGENTS.md tại source canonical. Không dùng worktree cũ để khởi động local.
Kiểm tra checksum lịch sử, toàn bộ pin cũ, JSON/UTF-8, syntax và fixtures trước.

Trong `services/ai-generation`:

```text
python -m pytest tests/test_scenario_assessment_prevention.py tests/test_scenario_assessment_scoped.py tests/test_scenario_assessment_fewshot.py tests/test_scenario_prompt_consolidation.py tests/test_scenario_evaluation.py
python -m pytest tests/test_scenario_assessment.py tests/test_scenario_assessment_parts.py
```

Tại root:

```text
mvn test "-Dtest=ScenarioAssessmentTest,ScenarioHarnessTest,ScenarioEngineIntegrationTest,AiEvaluationStudioTest"
node --test src/test/js/ai-evaluation.test.cjs
```

Acceptance:

- V4.15 chấp nhận bank 2/2/2 và 2/3/6; legacy vẫn yêu cầu tối thiểu 3.
- Reviewer phải đủ chính xác các IDs; thiếu/trùng ID không thành PASS.
- Bằng chứng bịa, references sai, bank rỗng, sai profile vẫn bị từ chối.
- Grade bài 2 câu hoạt động; đúng nơi sai giờ là PARTIAL; chỉ hỏi nơi không đòi lý do.
- Sửa dịch với TRANSLATIONS=false được dispatch nếu có target; SOURCE_ROLES lỗi vẫn giữ.
- Global-only không đoán target; patch ngoài scope bị loại; dependency closure không vượt activity.
- Core chấp nhận policy/hash mới và đúng thứ tự gọi; không nhận policy cũ giả danh V4.15.
- Tests legacy, fallback, ownership và UNKNOWN không regress.
- Nếu fail, lưu lỗi nguyên gốc; sửa đúng lỗi của đợt này, không giảm validation hoặc đổi pin lịch sử.

## Bước 2 — kiểm tra local và dữ liệu cũ

Sau tests offline đạt, restart bằng `scripts/run-local.ps1` theo tài liệu local, không tạo DB mới.
Giữ mọi operational usage limits off; không bỏ authentication/CSRF/ownership/emergency stop.
Kiểm tra runtime/buildHash thực tế, catalog có V4.13/V4.14/V4.15.
Source đã đổi nên runtime cũ có thể trả stale-build: đây không phải lỗi provider 503.
Không sửa ledger để vượt stale-build; tạo snapshot mới sau restart.

Tái dùng output đã lưu để đánh giá hồi quy, không tái gọi model cho dữ liệu cũ:

- `C:/Users/HP/.dictalab/evaluations/v414-discovery-20261114`
- `C:/Users/HP/.dictalab/evaluations/v414-adaptive-fewshot-20261115-r02`
- `C:/Users/HP/.dictalab/evaluations/v414-codex-curated-20261115`

Đặc biệt xem dịch food/social, đáp án giá thuê thiếu bằng chứng, câu hỏi trùng,
phần chấm thừa lý do, diễn giải trực tiếp bị gắn inference. Không dùng nguyên các case này làm holdout.
Không truy xuất/in log key. Browser learner dùng QA14; admin dùng phiên admin được chủ dự án cho phép.

## Bước 3 — so sánh AI thật, 30 đầu vào mới

- Đóng băng 30 happy cases trước khi chạy: 10 giao tiếp CEFR, 10 TOEIC, 10 IELTS.
- Phân bổ trình độ thấp/trung/cao và thể loại xã hội, giải thích, trải nghiệm, thảo luận, dịch vụ.
- Bao phủ người nghe/người tham gia, input ngắn, kết quả chưa xác nhận, đề xuất/chấp nhận,
  nhiều ý và nội dung suy luận có căn cứ. Không cần tấn công bảo mật hoặc tình huống cố ý vô lý.
- Không trùng few-shot; lưu corpus/hash trước khi sinh. Xác định yêu cầu quan trọng cho từng đầu vào,
  không đặt trước một hội thoại/đáp án duy nhất rồi bắt model khớp.
- Chạy V4.14 và V4.15 trên cùng 30 inputs, model/config/temperature/fallback; V4.13 là đối chứng
  lịch sử, không coi dữ liệu khác input là đối chiếu trực tiếp.
- Lưu model thực trả về từng stage. Các cặp đi fallback khác nhau báo riêng, không kết luận do prompt.
- Cùng chế độ repair hiện hành, lưu output lần đầu và cuối độc lập. Không tắt repair bằng cờ mới.
- Không sửa prompt hoặc ví dụ giữa chừng. Nếu cần đổi candidate, tạo version/hash/run mới.
- Giữ raw inputs, schema, prompts, outputs, events, lỗi và token/time trong private evidence folder.
- UNKNOWN không replay. 503 không phải mục tiêu sửa; thống kê riêng là chưa có kết quả, không tính PASS.
- 30 inputs × 2 phiên bản = 60 bài, khoảng 360 lời gọi cơ sở gồm EXTRACT, chưa tính repair,
  fallback HTTP attempts hoặc GRADE. Không mặc định đây là 30 lời gọi.

## Tiêu chí đánh giá đề nghị

| Ưu tiên | Chỉ số | Bằng chứng và quyết định |
|---|---|---|
| Chính | Tỷ lệ bài dùng được ngay lần đầu | Kiểm nội dung trước repair; tiêu chí chính chọn candidate |
| Chính | Lỗi nghiêm trọng | Sai vai/dữ kiện, đáp án sai, evidence sai, mục tiêu chấm thừa/thiếu; không được tăng |
| Chính | Reviewer bỏ sót/báo nhầm | Đối chiếu transcript thủ công; không dùng chính reviewer làm trọng tài cuối |
| Chính | Bao phủ nội dung quan trọng | Ít câu hơn không được thành mất mục tiêu học; không quy đổi thành “hiểu 80%” |
| Phụ | Bài dùng được sau sửa | Tách với accepted patch; đọc lại lỗi còn sót và regression |
| Phụ | Số gọi/token/thời gian | Đo thực tế theo model và stage, không đoán hóa đơn |

Đọc thủ công tất cả lỗi, mọi bất đồng reviewer và một mẫu bài PASS ở cả ba chương trình.
Chấm GRADE riêng trên câu hỏi đã sinh: đủ ý, thiếu ý, sai một ý, đúng bằng Anh/Việt/kết hợp,
không yêu cầu ngoài câu hỏi; không phát sinh ground truth từ chính lời chấm.
Mục tiêu 95% là mục tiêu thử nghiệm, không phải cam kết hoặc suy rộng từ mẫu nhỏ.
Chỉ đề xuất kích hoạt nếu first-pass tăng, không tăng lỗi nghiêm trọng/false acceptance,
không giảm chất lượng chấm; nếu bằng chứng chưa đủ thì giữ bản đang chạy.

## Bàn giao kết quả

Báo số mẫu dự kiến/hoàn tất/lỗi, first-pass/final, nguyên nhân từng bài lỗi và đường dẫn raw.
Không tuyên bố chất lượng tăng chỉ vì tests hoặc JSON pass. Hiển thị comparison trong Evaluation Studio
nếu runner hiện có hỗ trợ corpus này; nếu cần mở rộng runner, dừng báo phần thiếu trước khi chạy mất phí.
Sau checks đạt: review diff, stage đúng task, commit/push branch và xác minh remote SHA/CI.
Không merge main, deploy, activate hoặc tắt máy. Chờ chủ dự án duyệt kết quả.

## Tài liệu tham chiếu

- [Prompt Engineering Guide](https://github.com/dair-ai/Prompt-Engineering-Guide):
  chỉ dẫn rõ, chaining theo nhiệm vụ, few-shot phù hợp; không phải chứng nhận chất lượng.
- [Few-shot prompting](https://www.promptingguide.ai/techniques/fewshot).
- [Prompt chaining](https://www.promptingguide.ai/techniques/prompt_chaining).
