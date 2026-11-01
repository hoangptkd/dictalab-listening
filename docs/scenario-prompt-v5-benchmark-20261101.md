# Prompt V4 → V5: giao thức và báo cáo benchmark

## Quyết định phát hành

V4 tiếp tục hoạt động cho đến khi có bằng chứng đủ mạnh để thay thế. Candidate V5 chỉ được nạp trong tiến trình benchmark. Bảng kết quả và kết luận cuối nằm ở phần kết quả thực nghiệm bên dưới.

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

## Tài liệu đã nghiên cứu

- [rsc-harness Prompt Engineering](https://github.com/ericrisco/rsc-harness/blob/main/skills/prompt-engineering/SKILL.md): role/task trước dữ liệu, delimiter, zero-shot, eval trước tối ưu, từng biến một lần.
- [rsc-harness Agent Evaluation](https://github.com/ericrisco/rsc-harness/blob/main/skills/agent-eval/SKILL.md): baseline, deterministic/judge/human, split, failure slices, uncertainty.
- [Gemini prompt design](https://ai.google.dev/gemini-api/docs/prompting-strategies): nhiệm vụ rõ, ngữ cảnh cần thiết, decomposition và iteration.
- [Gemini structured output](https://ai.google.dev/gemini-api/docs/structured-output): subset schema, validators ứng dụng và lỗi ngữ nghĩa vẫn cần xử lý.
- [Promptfoo](https://www.promptfoo.dev/docs/intro/) và [model-graded metrics](https://www.promptfoo.dev/docs/configuration/expected-outputs/model-graded/): ma trận cùng dataset, assertion và semantic rubric. Tái dùng harness Python hiện có, không cài dependency mới.
- [DSPy](https://dspy.ai/): tối ưu instruction cần dataset và metric đủ tin cậy. Chưa triển khai GEPA.
- [Langfuse LLM-as-a-Judge](https://langfuse.com/docs/evaluation/evaluation-methods/llm-as-a-judge): rubric, evidence và score; trace không tự chứng minh chất lượng.

## Tái lập và rollback

Nạp credential bằng environment được bảo vệ, không đưa vào arguments/file Git. Chạy từ `tools/ai-eval` hoặc đặt Python path tương ứng:

```text
python tools/ai-eval/scenario_prompt_benchmark.py stages --variant v4 --output data/<unique-v4-stage-folder>
python tools/ai-eval/scenario_prompt_benchmark.py stages --variant v5 --output data/<unique-v5-stage-folder>
python tools/ai-eval/scenario_prompt_benchmark.py pipeline --variant v5 --output data/<unique-v5-pipeline-folder>
python tools/ai-eval/scenario_prompt_benchmark.py compare --v4 data/<v4-stage-folder> --v5 data/<v5-stage-folder> --output data/<unique-report>.json
```

V4 active source và pin không được ghi đè nên không cần reset hoặc checkout để quay lại. Dừng tiến trình benchmark V5 là kết thúc experiment. Không xóa evidence. Nếu sau này muốn bỏ toàn bộ công cụ thử nghiệm trong Git, revert commit task theo quy trình bình thường; không dùng reset hard/clean.
