# Prompt V4 → V5: giao thức và báo cáo benchmark

## Quyết định phát hành

V4 tiếp tục hoạt động cho đến khi có bằng chứng đủ mạnh để thay thế. Candidate V5 chỉ được nạp trong tiến trình benchmark. Bảng kết quả và kết luận cuối nằm ở phần kết quả thực nghiệm bên dưới.

**Kết luận cuối: ROLLBACK TO V4.** V5 tăng completion trong lượt full xen kẽ từ 2/27 lên 18/27, nhưng REVIEW tăng false acceptance từ 0/4 lên 1/4 defect probes, lặp lại lỗi sai vai người học. Điều này vi phạm điều kiện phát hành bắt buộc. Candidate chưa từng được kích hoạt nên thực tế là giữ nguyên V4, không reset hay đổi source đang chạy. Đây không phải kết luận mọi prompt V5 đều tệ hơn.

## Baseline và phạm vi

- Baseline Git: `64485f4d08860581a18f3206f06c44140afddec3`.
- Nhánh thử nghiệm: `codex/prompt-v5-benchmark-20261101`.
- Prompt V4: `services/ai-generation/app/scenario_workflow.py`, `INSTRUCTIONS`, `SCENARIO_LISTENING_V4`.
- Pin Core: `src/main/resources/scenario/workflow-v1.sha256`. Không thay pin khi đánh giá candidate.
- Endpoint thực tế: Gemini OpenAI compatibility `v1beta/openai/chat/completions`, không dùng SDK generateContent hay đổi API.
- Model yêu cầu: `gemini-3.5-flash-lite`. Mỗi wire capture còn lưu model do provider báo lại.
- Temperature thực tế của bảy stage: `0`; không có seed bảo đảm deterministic. Max output tokens theo stage: EXTRACT 2600, GENERATE 3200, QUESTIONS 4000, REVIEW_QUESTIONS 3500, TRANSLATE 4000, REVIEW 3500, GRADE 1200.
- Một HTTP call cho mỗi stage, không retry provider và không repair. Workflow dừng ở gate đầu tiên không đạt. Core chỉ polling/đăng ký lại cùng stable job ID; điều đó khác sinh lại nội dung.
- Python `scenario_workflow.py` → `ModelGateway` → `GeminiProvider` → `OpenAiCompatibleProvider`. Stage response được Pydantic và validator Python kiểm tra. `ScenarioContent.java` độc lập kiểm tra trước khi Core nhận bài học/chấm điểm.

## Hợp đồng dữ liệu đã xác minh

| Stage | Schema | Điều kiện code quan trọng |
|---|---|---|
| EXTRACT | Extraction → Specification | 2 nhân vật, learner ID tồn tại; 1–6 provided quotes khi allowed; quote là substring input; tối đa 4 suy luận + giả định; quote nguồn đưa vào constraintsVi |
| GENERATE | Dialogue | a1,a2,a3 đúng thứ tự; mỗi activity 2–6 segments, đủ hai speaker; ID duy nhất trong activity; số từ thực tế và tổng từ đúng profile |
| QUESTIONS | Questions → Content | mỗi activity phải có q1,q2; sáu câu tổng; evidence đúng activity/segment và là substring; agreement cần bằng chứng hai speaker có thứ tự |
| REVIEW_QUESTIONS | QuestionReview | đủ sáu check, mỗi đáp án một meaning unit; không thiếu/trùng meaning; tất cả flag đạt và issues rỗng mới nhận |
| TRANSLATE | Translations | mỗi cặp activity/segment xuất hiện đúng một lần, không thiếu/thừa hoặc chép nguyên câu tiếng Anh |
| REVIEW | Review | ba rubric SOURCE_ROLES/DIALOGUE/TRANSLATIONS; mọi segment có check và flag đạt; verdict và issues nhất quán |
| GRADE | Grade | đủ hai question ID, status thuộc năm enum; evidence là substring nguyên văn learner answer; CAPTURED/PARTIAL/INCORRECT cần evidence |

### Những giới hạn cần báo riêng

1. **Sáu câu hỏi là business rule hiện tại.** Questions schema cho phép shortfall để model báo thiếu, nhưng `validate_questions` và Core từ chối bài đó. REVIEW_QUESTIONS chỉ chạy sau khi đã đủ sáu. V5 phải giải thích shortfall là bài bị từ chối, không bịa dữ kiện để lấp.
2. **Nguồn gốc suy luận:** raw EXTRACT giữ `inferredFacts[].quoteVi`; hợp đồng public làm phẳng thành `assumptionsVi` với tiền tố `Suy luận:` và `Giả định:`. REVIEW phải đọc đúng hai tiền tố và so lại inputVi. Nó không nhận lại bảng private provenance nguyên bản. Muốn giữ bảng nguồn đến mọi stage là thay đổi backend/schema riêng.
3. **CEFR:** ScenarioInput chỉ có profile supported/standard/challenge và số từ, không có nhãn CEFR hoặc band TOEIC/IELTS đã xác minh. Không thể báo CEFR accuracy hoặc điểm thi từ hợp đồng này. V5 dùng profile được cấp, không bịa mapping.
4. **evidenceVi:** tên lịch sử; có thể chứa tiếng Anh/Việt/trộn. Python, Java, JSON lưu trong activity attempts và frontend đều dùng trường này. Giữ tên, giữ quote nguyên văn; không migration.
5. **Structured output:** schema chỉ kiểm soát cấu trúc. Gemini hỗ trợ một phần JSON Schema; độ dài chuỗi/uniqueItems không thay thế validator. Mã hiện tại bỏ minItems/maxItems ở các mảng cấp đầu của hai schema review vì tổ hợp schema từng bị HTTP 400; Pydantic vẫn kiểm tra đủ số phần tử. Cả hai phiên bản dùng đúng cùng schema đã biến đổi.

## Dataset và phương pháp đóng băng

- E2E: 27 input đã có trước thử nghiệm: 20 mục tiêu học đã chạy thật + 7 tình huống thông thường trong fixture hiện hữu. Không sinh thêm input bằng Gemini để tự chấm Gemini.
- Tập development 21, holdout 6; split ghi cố định trong `scenario-prompt-v5-v1.json` trước V5. Smoke bốn development cases; benchmark đầy đủ chạy lại cả 27. Bốn case smoke được lặp lại độc lập trong full để thấy dao động.
- Stage isolation: `scenario-prompt-stages-v5-v1.json`, đầu vào cố định cho cả hai phiên bản; clean và các lỗi nội dung cụ thể để đo reviewer; GRADE có English/Việt/mixed/paraphrase/typo/partial/wrong/blank/acknowledgement/uncertain.
- Nguồn input là dữ liệu tổng hợp của ứng dụng, không có production traffic đã gán nhãn chuyên gia. Expected criteria và probe labels do agent soạn; chỉ gọi là **agreement với nhãn tạm**, không gọi là độ chính xác thực tế.
- V4 capture mới hoàn tất trước khi viết candidate V5. Smoke và full được lưu riêng, độc quyền tạo file; inventory SHA-256 khóa mọi input, request, response nguyên bản, wire output, usage, lỗi và kết quả gate. Phần derived được phép tạo báo cáo mà không sửa raw.
- Thay từng stage theo thứ tự EXTRACT → GENERATE → QUESTIONS → REVIEW_QUESTIONS → TRANSLATE → REVIEW → GRADE. Stage isolation dùng cùng input, schema, token budget, model và validator; không trộn với sự khác nhau của upstream.
- Zero-shot trước. Không tự động thêm few-shot hoặc tối ưu GEPA qua holdout.

## Rubric ngữ nghĩa độc lập

Judge phải nêu căn cứ trong output, rồi mới đưa verdict theo từng tiêu chí. Các tiêu chí không có căn cứ được đánh dấu không đánh giá được, không tự cho điểm đạt. Judge là tín hiệu phụ khi chưa có human labels; cùng nhà cung cấp/model có nguy cơ tự ưu ái. Không dùng reviewer của pipeline như nhãn chất lượng độc lập.

- EXTRACT: giữ actor/facts; provided quote đúng nguồn; inference có hỗ trợ; assumption tách nguồn.
- GENERATE: roles/facts/conditions; progression/goal; naturalness; đề xuất chỉ thành agreement sau acceptance thực sự; deadline chưa đủ thông tin phải còn unresolved.
- QUESTIONS: answer support, evidence support, requested single meaning, khác nghĩa, không lộ đáp án; không chấp nhận proposal như agreement.
- TRANSLATE: từng segment giữ đầy đủ nghĩa, phủ định, thời gian, số lượng, ngữ dụng; thuật ngữ và tiếng Việt tự nhiên.
- Reviewer: agreement với clean/defect probes được định trước; false acceptance và false rejection báo riêng.
- GRADE: agreement theo từng status và loại ngôn ngữ; evidence phải nguyên văn; không dùng keyword hay độ dài thay nghĩa.

## Thay đổi từng prompt và kiểm soát tác động

Các điểm dưới đây là giả thuyết cần đo, không phải lời khẳng định rằng cách diễn đạt mới đã sửa được lỗi. Candidate được đóng băng sau lượt development của từng stage; không sửa tiếp dựa vào holdout.

| Prompt | Rủi ro/căn nguyên cần xử lý | Thay đổi V5 | Quy tắc giữ nguyên và kết quả probe |
|---|---|---|---|
| EXTRACT | Mục tiêu học không nêu bối cảnh dễ bị từ chối; vai và chi tiết bổ sung có thể bị coi là đã xác nhận | Cho phép chọn bối cảnh tối thiểu có nhãn giả định; quy định riêng quote nguồn, suy luận có quote, giả định và vai người học | Hai nhân vật, quotes nguyên văn, tối đa bốn suy luận + giả định, safety, schema và validator giữ nguyên; cả hai đạt 4/4 schema và allowed trong stage isolation |
| GENERATE | Diễn biến dễ thành khuôn giao dịch; nhầm clarification với acceptance; deadline bị biến thành availability | Chọn diễn biến theo mục tiêu; mô tả ranh giới proposal/acceptance/confirmation; giữ outcome pending khi chưa đủ dữ kiện; nhắm giữa writingBudget | Chỉ hội thoại, ba activity, 2–6 segments, hai speaker, số từ do backend kiểm; cả hai đạt 2/2 gate ở input cố định |
| QUESTIONS | Gộp nhiều nghĩa; câu hỏi chứa đáp án; proposal bị hỏi như agreement | Định nghĩa một meaning unit bằng quan hệ cần hỏi; so sáu nghĩa; trích evidence cùng activity và theo thứ tự; thiếu dữ kiện phải trả shortfall để bị từ chối | Sáu câu là điều kiện nhận bài, không bịa để đủ; schema và evidence validator giữ nguyên; cả hai đạt 1/1 gate |
| REVIEW_QUESTIONS | Reviewer tin fact/answer/speechAct của bước trước; dễ bỏ sót answer leakage | Tự suy đáp án từ question + transcript trước; kiểm từng chiều độc lập; giải thích nghĩa được hỏi, không sửa đáp án đầu vào | Tám cờ, sáu checks, issues/verdict nhất quán giữ nguyên; nhãn tạm V4 4/5, V5 5/5; V5 bắt answer leakage |
| TRANSLATE | Dịch cứng theo từ; đổi sắc thái, suy giới/tuổi, để lại từ tiếng Anh | Ưu tiên tương đương ngữ nghĩa, thuật ngữ theo ngữ cảnh, xưng hô trung tính; phân biệt tên riêng với từ thông dụng | Mỗi segment một bản dịch, giữ phủ định/số lượng/thời gian và speech act; cả hai đạt 2/2 coverage/schema |
| REVIEW | Nguồn giả định/suy luận bị gộp; cấu trúc hợp lệ bị hiểu là nội dung đúng | Đọc riêng constraintsVi, `Suy luận:`, `Giả định:`; audit ba rubric và từng bản dịch; không bắt outcome giả để đạt mục tiêu | Fail closed, segment checks và ba rubric giữ nguyên; nhãn tạm V4 5/5, V5 4/5: V5 bỏ sót sai vai, là regression quan trọng |
| GRADE | Keyword, acknowledgement hoặc tiếng Anh dễ bị chấm sai; ranh giới partial/missed chưa nhất quán | Nêu thứ tự MISSED → UNCERTAIN → INCORRECT → PARTIAL → CAPTURED; chấp nhận hai ngôn ngữ/mixed/paraphrase/typo; evidence nguyên văn | Năm enum, hai answers, schema và evidence substring giữ nguyên; cả hai khớp 18/20 status nhãn tạm; chưa cải thiện ranh giới partial |

Không loại bỏ safety hoặc hard constraint. Những phần được rút gọn/diễn đạt lại là hướng dẫn trùng lặp; không bổ sung few-shot, không đổi model/config/schema, không thêm validator vào production. Những quy tắc mới chưa đạt benchmark được giữ trong candidate để phân tích, không đưa vào ứng dụng.

### Trình tự development đã lưu

`data/scenario-local-20261031/prompt-v5-step-01-extract-20261101` đến `prompt-v5-step-07-grade-20261101` ghi lại lần chạy riêng trước khi chuyển stage tiếp theo. Stage probes dùng đầu vào đóng băng; sáu prompt còn lại giữ V4 khi đánh giá một phase. Lượt stage đầy đủ kiểm lại cả bảy candidate. Đây là instruction refactor zero-shot; không có GEPA hoặc vòng tối ưu bằng holdout.

## Tài liệu đã nghiên cứu

- [rsc-harness Prompt Engineering](https://github.com/ericrisco/rsc-harness/blob/main/skills/prompt-engineering/SKILL.md): role/task trước dữ liệu, delimiter, zero-shot, eval trước tối ưu, từng biến một lần.
- [rsc-harness Agent Evaluation](https://github.com/ericrisco/rsc-harness/blob/main/skills/agent-eval/SKILL.md): baseline, deterministic/judge/human, split, failure slices, uncertainty.
- [Gemini prompt design](https://ai.google.dev/gemini-api/docs/prompting-strategies): nhiệm vụ rõ, ngữ cảnh cần thiết, decomposition và iteration.
- [Gemini structured output](https://ai.google.dev/gemini-api/docs/structured-output): subset schema, validators ứng dụng và lỗi ngữ nghĩa vẫn cần xử lý.
- [Promptfoo](https://www.promptfoo.dev/docs/intro/) và [model-graded metrics](https://www.promptfoo.dev/docs/configuration/expected-outputs/model-graded/): ma trận cùng dataset, assertion và semantic rubric. Tái dùng harness Python hiện có, không cài dependency mới.
- [DSPy](https://dspy.ai/): tối ưu instruction cần dataset và metric đủ tin cậy. Chưa triển khai GEPA.
- [Langfuse LLM-as-a-Judge](https://langfuse.com/docs/evaluation/evaluation-methods/llm-as-a-judge): rubric, evidence và score; trace không tự chứng minh chất lượng.

## Kết quả thực nghiệm: baseline và stage isolation

### V4 được chụp trước khi viết candidate

Lượt full V4 độc lập đạt **11/27 (40,7%)** authoring pipeline. Có 108 HTTP 200, provider báo model `gemini-3.5-flash-lite`; 118.647 input + 66.414 output = **185.061 tokens**. Không có retry. Đây là baseline lịch sử của thử nghiệm, không thay thế đối chứng interleaved chạy cùng thời gian với V5.

| Stage baseline full | Đã gọi | Đúng schema | Qua validator/gate stage |
|---|---:|---:|---:|
| EXTRACT | 27 | 27 | 21 |
| GENERATE (chỉ dialogue) | 20 | 20 | 19 |
| QUESTIONS | 19 | 19 | 18 |
| REVIEW_QUESTIONS | 18 | 17 | 12 |
| TRANSLATE | 12 | 12 | 12 |
| REVIEW | 12 | 12 | 11 |

Một EXTRACT qua gate nhưng `allowed=false`, nên chỉ 20 case đi tiếp. GRADE là phép thử riêng sau khi có bài và câu trả lời; không tính vào authoring completion. Trường `GENERATE/outcome.json` của API workflow bao gồm dialogue, questions, question review, translate và review; không nhầm outcome đó với duy nhất call `scenario_generate`.

### So sánh đầu vào cố định cho cả bảy prompt

29 call/phiên bản, tất cả nhận HTTP 200. So sánh đã xác minh cùng input, schema, temperature, token budget, model/API và retry=0. Các mẫu reviewer cố ý có lỗi nội dung thông thường; không phải mẫu tấn công bảo mật.

| Prompt / metric | V4 | V5 | Chênh lệch / diễn giải |
|---|---:|---:|---|
| EXTRACT schema + expected allowed | 4/4 | 4/4 | Không đổi; chưa phải fact accuracy |
| GENERATE schema + gate | 2/2 | 2/2 | Không đổi; CEFR accuracy không đo được |
| QUESTIONS schema + gate | 1/1 | 1/1 | Mẫu quá ít để kết luận question accuracy |
| REVIEW_QUESTIONS phát hiện lỗi | 3/4 | 4/4 | V5 bắt thêm lỗi lộ đáp án |
| REVIEW_QUESTIONS false acceptance | 1/4 | 0/4 | Giảm một probe |
| REVIEW_QUESTIONS false rejection trên clean | 0/1 | 0/1 | Không đổi |
| TRANSLATE coverage/schema | 2/2 | 2/2 | Chất lượng nghĩa được judge riêng |
| REVIEW phát hiện lỗi | 4/4 | 3/4 | **V5 bỏ sót sai vai** |
| REVIEW false acceptance | 0/4 | 1/4 | **Tăng, không đạt điều kiện phát hành** |
| REVIEW false rejection trên clean | 0/1 | 0/1 | Không đổi |
| GRADE khớp status nhãn tạm | 18/20 | 18/20 | Không tăng; cần human adjudication |

Các tỷ lệ trên là agreement với probe labels của agent; **không có human labels để báo human agreement, precision hay accuracy thực tế**. Bảng không gộp thành điểm trung bình.

### Token và thời gian trên cùng stage inputs

| Stage | Tokens V4 | Tokens V5 | Median response giây V4 | Median response giây V5 |
|---|---:|---:|---:|---:|
| EXTRACT (4 calls) | 3.276 | 3.891 | 2,475 | 2,215 |
| GENERATE (2) | 2.165 | 2.751 | 2,274 | 2,801 |
| QUESTIONS (1) | 1.540 | 1.484 | 2,923 | 2,861 |
| REVIEW_QUESTIONS (5) | 13.358 | 14.621 | 3,175 | 3,763 |
| TRANSLATE (2) | 1.672 | 2.128 | 2,024 | 2,167 |
| REVIEW (5) | 9.781 | 11.201 | 2,957 | 3,648 |
| GRADE (10) | 6.638 | 10.471 | 1,293 | 1,593 |
| Tổng 29 calls | **38.430** | **46.547** | Tổng response 66,897 giây | Tổng response 83,708 giây |

V5 dùng thêm **21,1% tokens** trên stage isolation; không thể lấy “prompt dài hơn” làm bằng chứng tốt hơn. Thời gian là quan sát thực ở hai lượt khác nhau, không phải benchmark latency có kiểm soát; không suy ra ý nghĩa thống kê hoặc chi phí USD. Ở pipeline, số call phụ thuộc fail-early và số bài hoàn tất nên tổng token không phải chi phí của cùng một bài thành công.

#### Regression cần giữ nguyên bằng chứng

`review-source-role`: input “Tôi muốn đặt hai ổ bánh không có hạt hôm nay để nhận sáng mai.”, nhưng specification gán learnerRoleVi và cả hai nhân vật thành “Người bán bánh”. V5 vẫn trả `valid=true`, SOURCE_ROLES passed và lý do mô tả có cả người bán lẫn khách hàng — trái với specification thực nhận. V4 từ chối. Lỗi V5 xuất hiện ở cả development và stage full. Candidate không được sửa lại để che kết quả này.

#### Ranh giới chấm điểm chưa đạt

`grade-partial`: hỏi “Khách có thể nhận bánh vào buổi nào?”, learner trả `Tomorrow`: nhãn tạm PARTIAL, cả V4/V5 chấm CAPTURED dù thiếu buổi sáng. Hỏi “Cửa hàng mở lúc mấy giờ?”, trả `It opens.`: nhãn tạm MISSED, V4 chấm PARTIAL, V5 chấm INCORRECT. Cần người đánh giá xác nhận ranh giới no-answer/partial; chưa thay nhãn sau khi xem output. Các probe English/Việt/mixed/paraphrase/typo/incorrect/blank/acknowledgement/uncertain còn lại khớp nhãn tạm; evidence vẫn qua validator nguyên văn.

### Transport failures được lưu riêng

Hai capture `prompt-v5-candidate-v5-smoke-20261101` và `prompt-v5-candidate-v5-full-20261101` có DNS/timeout/ReadError thực, kèm lỗi export Langfuse. Giữ toàn bộ raw failures; không quy lỗi mạng thành lỗi prompt và không đổi outcome thành success. Sau khi DNS/TLS khỏe, chạy một replicate mới được khai báo rõ là `round2`, xen kẽ V4/V5 và đảo thứ tự theo case. Không resume unknown job hoặc retry ẩn.

## Benchmark xen kẽ V4/V5, round2

### Pipeline completion và holdout

| Lượt / tập | V4 | V5 |
|---|---:|---:|
| Smoke: 4 development cases | 2/4 (50%) | 0/4 (0%) |
| Full: tất cả 27 cases | **2/27 (7,4%)** | **18/27 (66,7%)** |
| Full: development | 1/21 (4,8%) | 14/21 (66,7%) |
| Full: holdout | 1/6 (16,7%) | 4/6 (66,7%) |

Full paired delta **+59,3 điểm phần trăm**; case-bootstrap 95% **[+37,0; +77,8] điểm phần trăm**, seed 17, 10.000 resamples. Khoảng này chỉ điều kiện trên một sample/variant/case, không bao gồm toàn bộ cloud sampling variance, không phải CI của semantic accuracy. Holdout sáu mẫu quá nhỏ để kết luận khả năng tổng quát.

V4 baseline full trước candidate đạt 11/27, còn replicate full xen kẽ đạt 2/27. Smoke cũng không dự báo đúng kết quả full của V5. Không bỏ lượt xấu để chọn số đẹp; các lần đo khác nhau được báo riêng. Cùng temperature=0 vẫn không bảo đảm cùng output hoặc provider snapshot bất biến; chỉ xác minh model ID được provider báo lại.

Riêng bốn input xuất hiện trong cả smoke và full: V4 đổi từ 2/4 thành 1/4; V5 từ 0/4 thành 2/4. Không chỉnh candidate giữa hai lần này. Đây là quan sát lặp nhỏ, chưa đủ ước lượng độ ổn định theo từng case.

### Funnel theo từng stage ở lượt full

| Stage | V4 calls / schema / gate | V5 calls / schema / gate |
|---|---:|---:|
| EXTRACT | 27 / 27 / 18 | 27 / 27 / 27 |
| GENERATE (dialogue) | 16 / 16 / 12 | 27 / 27 / 27 |
| QUESTIONS | 12 / 12 / 11 | 27 / 27 / 23 |
| REVIEW_QUESTIONS | 11 / 11 / 3 | 22 / 22 / 19 |
| TRANSLATE | 3 / 3 / 3 | 19 / 19 / 19 |
| REVIEW | 3 / 3 / 2 | 19 / 19 / 18 |

Hai EXTRACT V4 `allowed=false` làm 18 gate-pass chỉ còn 16 bài đi tiếp. Một QUESTIONS V5 qua validator grounding riêng nhưng bị gate nội dung bổ sung chặn vì lộ đáp án, nên chỉ 22 call REVIEW_QUESTIONS. Funnel của hai arm không có cùng upstream content: dùng stage isolation để đối chiếu prompt riêng; không diễn giải tỷ lệ reviewer qua gate trong funnel như defect-detection accuracy.

### Failure modes full — không sửa output

| Điểm dừng đầu tiên | V4 | V5 |
|---|---:|---:|
| EXTRACT thiếu provided facts | 9 | 0 |
| EXTRACT allowed=false | 2 | 0 |
| Dialogue sai giới hạn từ | 4 | 0 |
| QUESTIONS evidence chưa được neo đúng | 1 | 0 |
| QUESTIONS thiếu/sai question IDs | 0 | 4 |
| QUESTIONS lộ đáp án ở gate code | 0 | 1 |
| REVIEW_QUESTIONS từ chối chất lượng | 8 | 3 |
| REVIEW từ chối ngữ nghĩa | 1 | 1 |
| Hoàn tất | 2 | 18 |

V5 có cải thiện đo được ở extraction của mục tiêu học và word budget; sáu câu hỏi có căn cứ vẫn chưa luôn sinh đủ. Các output bị reviewer từ chối chưa phải human-confirmed defect; cần đọc reason/raw transcript thay vì mặc định reviewer luôn đúng. Đặc biệt completion cao không phủ định lỗi false acceptance của REVIEW đã đo bằng probe cố định.

Full V4: **72 HTTP 200**, 71.588 input + 37.936 output = **109.524 tokens**. Full V5: **141 HTTP 200**, 175.392 input + 89.418 output = **264.810 tokens**. Mọi call báo đúng `gemini-3.5-flash-lite`; không có transport unknown trong round2. Các bản summary kiểm cùng corpus/program/workflow/config. So sánh stage còn kiểm request schema/options/maxOutputTokens và user JSON bằng nhau; chỉ system instruction/version khác.

## Judge ngữ nghĩa: 18 call thật, pointwise

Judge chấm outputs của **stage isolation**, không chấm toàn bộ 18 bài V5 đã hoàn tất. Cùng model Gemini, không có human calibration; không dùng làm kết luận phát hành độc lập. Candidate không mang tên V4/V5 trong input judge. Không thực hiện pairwise preference nên không có điểm A/B cần đổi vị trí; pipeline vẫn đảo thứ tự gọi hai variant theo case.

| Prompt / rubric criteria | V4 PASS / tổng | V5 PASS / tổng | Căn cứ cần đọc |
|---|---:|---:|---|
| EXTRACT: 4 outputs × 5 criteria | 20/20 | 20/20 | Fact/source/inference/assumption/role trên bốn probes |
| GENERATE: 2 × 6 | 12/12 | 12/12 | Profile tương đối, không phải CEFR/band |
| QUESTIONS: 1 × 6 | 6/6 | 5/6 | V5 a3q2 hỏi cả phương thức và thời điểm thanh toán |
| TRANSLATE: 2 × 5 | 9/10 | 10/10 | V4 thêm tên loại hạt không có trong nguồn |

Không có NOT_ASSESSABLE hoặc lỗi judge trong 18 call. Các criteria cùng một output không độc lập; không chuyển các tỷ lệ này thành “accuracy tổng” hay suy rộng từ một QUESTIONS sample.

Hai lý do FAIL đã đối chiếu với raw:

- V5 QUESTIONS hỏi “Khách hàng sẽ thanh toán bằng phương thức nào và khi nào?”, đáp án “Thanh toán ngay bây giờ bằng thẻ.”: gộp hai thông tin độc lập, dù schema/evidence hợp lệ.
- V4 TRANSLATE đổi “no nuts” thành “không có hạt dẻ cười hoặc các loại hạt”: tự thêm loại hạt cụ thể. V5 không thêm trong probe này.

## Bằng chứng, tracing và điều kiện còn thiếu

Tất cả đường dẫn dưới đây tính từ repository, nằm trong ignored `data/`; không commit raw/runtime/credentials:

- Baseline: `data/scenario-local-20261031/prompt-v5-baseline-v4-{smoke,full}-20261101`.
- Stage: `data/scenario-local-20261031/prompt-v5-stages-{v4,v5}-20261101`; đối chiếu `prompt-v5-stages-comparison-20261101.json`.
- Paired: `data/scenario-local-20261031/prompt-v5-paired-{smoke,full}-round2-20261101/{v4,v5}`; summary `prompt-v5-paired-{smoke,full}-summary.json`.
- Judge: `data/scenario-local-20261031/prompt-v5-semantic-judge-20261101`; supplementary metrics `prompt-v5-supplementary-metrics.json` giữ lý do từng rubric và timing/token theo stage.
- Human packet: `data/scenario-local-20261031/prompt-v5-human-review-packet.json`, mọi entry **UNREVIEWED**, không giả gán nhãn người.
- Trace audit: `data/scenario-local-20261031/prompt-v5-langfuse-export-audit.json`.

Mọi capture hoàn tất có inventory SHA-256. Offline replay round2: smoke V4 7/7, V5 8/8 phase outcomes khớp; full V4 **43/43**, V5 **54/54** khớp, **0 network calls**. Có thể đọc lại, kiểm lại và chấm người trên cùng output mà không sinh lại dataset.

Langfuse CLI đã xác minh lúc `2026-11-01T10:34:54Z`: full paired 310 observations/97 traces; stage V4 và V5 mỗi bên 58 observations/29 traces; judge 36 observations/18 traces. Số trace root full = 54 EXTRACT + 16 GENERATE V4 + 27 GENERATE V5. ERROR level ở reviewer rejection là tín hiệu gate, không tự động là lỗi transport. Lượt bị mạng ảnh hưởng không có đủ remote trace, raw local vẫn là bằng chứng chính.

- [Trace full paired đã xác minh](https://jp.cloud.langfuse.com/project/cmvx6vyks000iad0j2ph4qdbu/traces/c8514d25b58c21c832e06fe910d876ba)
- [Trace judge đã xác minh](https://jp.cloud.langfuse.com/project/cmvx6vyks000iad0j2ph4qdbu/traces/2fd975312edeaa6e432334f6a00dc2de)

Chưa có human gold, human/judge agreement, CEFR/band mapping hoặc đánh giá chuyên gia trên toàn bộ bài hoàn tất. Không đo factual world accuracy bằng fictional scenarios. Bước tiếp theo cần người gán nhãn các output đã lưu, đặc biệt sai vai, atomicity và partial/missed; tăng số lần đo trên corpus cố định, giữ candidate này nguyên trạng và tách candidate tiếp theo thành thử nghiệm mới. Không triển khai GEPA trước khi metric đáng tin cậy.

## Kết luận và Git

**ROLLBACK TO V4** theo điều kiện B của yêu cầu: REVIEW V5 tăng false acceptance trên probe đã xác định, lặp hai lần. Completion tăng là thành quả có thật; chất lượng reviewer và grading chưa đạt điều kiện để thay thế. Không retune qua holdout, không xóa failures, không tự kích hoạt V5.

- Baseline/nguồn prompt đang sử dụng: `64485f4d08860581a18f3206f06c44140afddec3`.
- Candidate commit: `ca1b4e2e96ec330814f4aedd02353ecef6cbf4a2`.
- Nhánh báo cáo/benchmark: `codex/prompt-v5-benchmark-20261101`.
- Active version: `SCENARIO_LISTENING_V4`; prompt hash `e3b045db46013c6e9694ae450f8beeb168bc1b452cb1548bcbe43a4eb247535b`. Candidate hash `6a3a61aa0762dce1e0898bec5f1cf46b5ae6189dddbe3015828a35667ebe6f8f`.
- V4 source và Core hash pin không có diff so với baseline. Candidate không được production import. Không thay model, database, schema, validator, dependency hay active application behavior.
- Kiểm tra local: **157 tests PASS** (benchmark + capture/replay + scenario workflow + contracts); UTF-8 sáu file PASS; diff whitespace check PASS. Python 3.14 phát cảnh báo tương thích/deprecation từ thư viện, không đổi dependency để che cảnh báo.
- Đây là bàn giao nhánh thử nghiệm; không deploy production. Workflow AI hiện chạy qua workflow_call hoặc dispatch; push nhánh này không tự chứng minh CI đã chạy. SHA remote và kết quả kiểm tra Actions được báo trong thông báo bàn giao.

## Tái lập và rollback

Nạp credential bằng environment được bảo vệ, không đưa vào arguments/file Git. Chạy từ `tools/ai-eval` hoặc đặt Python path tương ứng:

```text
python tools/ai-eval/scenario_prompt_benchmark.py stages --variant v4 --output data/<unique-v4-stage-folder>
python tools/ai-eval/scenario_prompt_benchmark.py stages --variant v5 --output data/<unique-v5-stage-folder>
python tools/ai-eval/scenario_prompt_benchmark.py pipeline --variant v5 --output data/<unique-v5-pipeline-folder>
python tools/ai-eval/scenario_prompt_benchmark.py paired --output data/<unique-paired-round>
python tools/ai-eval/scenario_prompt_benchmark.py compare --v4 data/<v4-stage-folder> --v5 data/<v5-stage-folder> --output data/<unique-report>.json
python tools/ai-eval/scenario_prompt_benchmark.py summarize --v4 data/<paired-round>/v4 --v5 data/<paired-round>/v5 --output data/<unique-pipeline-report>.json
python tools/ai-eval/scenario_prompt_benchmark.py judge --v4 data/<v4-stage-folder> --v5 data/<v5-stage-folder> --output data/<unique-judge-folder>
```

V4 active source và pin không được ghi đè nên không cần reset hoặc checkout để quay lại. Dừng tiến trình benchmark V5 là kết thúc experiment. Không xóa evidence. Nếu sau này muốn bỏ toàn bộ công cụ thử nghiệm trong Git, revert commit task theo quy trình bình thường; không dùng reset hard/clean.
