# Kế hoạch tích hợp few-shot vào pipeline cá nhân hóa

> Phạm vi chốt lại theo yêu cầu mới: **chỉ sửa code và kiểm tra offline; chưa gọi model, chưa sinh dữ liệu/benchmark, chưa restart local hoặc deploy**. P3 và phần kích hoạt runtime của P4 bên dưới là kế hoạch lần sau, không phải công việc đã chạy.

## 1. Mục tiêu và quyết định thiết kế

Tích hợp few-shot vào đường xử lý `/conversation` đang dùng `scenario-job.v2`, gồm EXTRACT, GENERATE, QUESTIONS, REVIEW_QUESTIONS, TRANSLATE và REVIEW. GRADE giữ nguyên V4. Người dùng không cần chọn một tính năng hay route khác.

Kết quả cần đạt là **prompt có ví dụ thực sự đến model, đúng schema hiện tại, có thể tái hiện và đối chiếu chất lượng**. Không coi việc thêm file ví dụ, test mock pass hoặc tăng tỷ lệ gate là bằng chứng đủ về chất lượng nội dung.

Đây là nâng cấp prompt và hợp đồng phiên bản giữa Python/Core. Không train/SFT model, không thay model, không giảm gate, không thêm vector database, embedding, LLM chọn ví dụ hoặc dependency. Chưa triển khai production; chương trình luyện vẫn giữ trạng thái DRAFT.

## 2. Bằng chứng hiện có

- Bộ few-shot cũ: `scenario_prompts_fewshot.py` + `scenario_fewshot_examples_v1.json`, gồm 10 cặp ví dụ theo bước, chỉ được chọn bởi benchmark v1.
- Pipeline cá nhân hóa hiện tại dùng `scenario_prompts_personalized.py`; EXAMPLES vẫn ghi zero-shot.
- Lượt đo ngày 03/11: 20 hội thoại, 16 bộ câu hỏi, 12 bộ dịch, 11 bài qua gate, 9 bị chặn. Có lỗi lọt reviewer: sai nghĩa đáp án, câu hỏi ghép, nhãn kỹ năng sai, mất vai người học.
- Ví dụ cũ thiếu learnerMode/genre, learningContext, supportKind/inferenceReasonEn và rubric cá nhân hóa. Không được nối nguyên xi vào prompt mới.
- Core kiểm đồng thời hash/version/kết quả của job. Chỉ đổi Python sẽ làm Core từ chối hoặc khiến job cũ không xử lý được.

Tham chiếu: [báo cáo 20 bài](scenario-personalized-20-quality-20261103.md). Bản cũ được giữ nguyên để đối chiếu, không sửa lịch sử benchmark.

## 3. Luồng sau tích hợp

```text
Core tạo job mới với pin few-shot
  → Python kiểm hash + learningContext
  → chọn đúng phiên bản prompt theo pin
  → chỉ đưa ví dụ của bước đang chạy
  → model trả JSON theo schema
  → các gate hiện tại kiểm tra và có quyền từ chối
  → Core kiểm lại hash/version của chính job đó

Job đã gửi bằng pin zero-shot cũ
  → tiếp tục dùng đúng prompt zero-shot cũ, không đổi prompt giữa chừng
```

Giữ prompt zero-shot cá nhân hóa và bộ few-shot thử nghiệm v1 bất biến. Tạo bộ few-shot cá nhân hóa riêng, nạp một lần khi tiến trình khởi động. Hash tính từ nội dung prompt đã biên dịch và schema; thay một ví dụ làm đổi pin. Không chọn ví dụ ngẫu nhiên hoặc âm thầm rơi về zero-shot.

## 4. Thiết kế bộ ví dụ phiên bản đầu

Tái sử dụng ý tưởng/văn bản phù hợp từ 10 ví dụ cũ, tạo tài sản riêng đã chuyển schema. Dự kiến 13 cặp input/output theo bước:

| Bước | Số ví dụ | Hành vi cần thể hiện |
| --- | ---: | --- |
| EXTRACT | 3 | Mục tiêu nghe → listener; vai nhân viên → participant; vai học viên/bạn cùng lớp được giữ dù chọn TOEIC |
| GENERATE | 3 | A1 ngắn, tự nhiên; B1 ba phần liên kết; mức cao đủ từ và kết quả chưa xác nhận vẫn được giữ chưa xác nhận |
| QUESTIONS | 2 | Ý chính/chi tiết/lý do/mục đích đúng nhãn; một nghĩa mỗi câu; agreement có proposal + acceptance cùng activity |
| REVIEW_QUESTIONS | 2 | Một bộ đạt; một bộ có lỗi nội dung phải trả valid=false, tách rõ các đơn vị đáp án và đánh đúng cờ lỗi |
| TRANSLATE | 1 | Đủ segment; thuật ngữ đúng ngữ cảnh; giữ điều kiện, ý định và độ chắc chắn |
| REVIEW | 2 | Một bài nhất quán; một bài sai vai hoặc dịch phải bị từ chối dù JSON/hội thoại trôi chảy |
| GRADE | 0 | Chỉ dẫn V4 giữ nguyên từng byte |

### Quy tắc soạn ví dụ

1. Ví dụ là tình huống hư cấu; metadata ghi agent-authored, chưa phải human gold.
2. Tách biệt với 20 brief benchmark: không sao chép input/output lỗi của bộ đo vào prompt. Thể hiện lớp lỗi bằng bối cảnh khác.
3. Input đúng hình dạng production của bước đó; expectedOutput đúng schema hiện hành. Không đưa metadata giải thích vào JSON mà model cần trả.
4. Ví dụ EXTRACT phải giữ nguyên trích dẫn người dùng; khai báo riêng suy luận/giả định. Listener không gán learnerCharacterId.
5. Ví dụ GENERATE chỉ chứa hội thoại, đủ ba activity, 2–6 segment/activity, đúng cả hai speaker và ngân sách từ thực tế. Nhắm giữa khoảng, không sát ngưỡng tối thiểu.
6. QUESTIONS có đúng sáu câu, ít nhất một main-idea trong ngữ cảnh mặc định. Không dùng nhãn purpose/reason cho câu hỏi chỉ hỏi vật/người/giờ. Evidence là quote chính xác, cùng activity.
7. Reviewer có cả ví dụ đạt và không đạt. Mẫu từ chối minh họa lỗi chất lượng thông thường, không thêm bộ tấn công/bảo mật.
8. reasonVi ngắn, nêu lỗi cụ thể và không vượt giới hạn schema. Không dùng lời giải thích dài để biện hộ cho valid=true.
9. Chỉ lắp ví dụ của bước đang gọi; không đưa toàn bộ 13 ví dụ vào mọi request. Đặt giới hạn kích thước section trong test để tránh prompt phình không kiểm soát.
10. Lời dẫn nhắc model học cấu trúc/hành vi, không chép tên, vai, bối cảnh hay mức khó từ ví dụ sang input mới.

Không dùng truy xuất động trong phiên bản đầu. Khi có đủ ví dụ đã chấm và số liệu chứng minh cần thiết, có thể chọn xác định theo stage/profile/genre; phiên bản selector và danh sách ID phải nằm trong fingerprint. Không thêm trước khi đo được lợi ích.

## 5. Các phần code phải nối đầy đủ

### A. Tài sản prompt

- Giữ nguyên `scenario_prompts_personalized.py` làm baseline đã đo.
- Thêm `scenario_prompts_personalized_fewshot.py` và JSON ví dụ riêng.
- Biên dịch section EXAMPLES một cách xác định; phát hiện thiếu/trùng stage/ID, thiếu marker hoặc ví dụ GRADE ngay khi nạp.
- Xuất tên phiên bản, hash bộ ví dụ và ID theo stage cho tracing.

### B. Workflow Python

- `scenario_workflow.py`: mặc định job mới dùng few-shot; chấp nhận chính xác hai pin cá nhân hóa đã biết: baseline và few-shot.
- Chọn instructions/version từ pin của payload, không từ một biến global thay đổi khi chạy.
- `personalized_prompt_hash()` trả pin mới mặc định; có cách tính pin baseline để replay và kiểm tra job cũ.
- `metadata()` và `jobs.capability_metadata()` phải nhận pin của job. Fingerprint, kết quả và trace phải nói đúng prompt đã thực thi.
- Giữ schema, OUTPUT_TOKENS, model selection, temperature, deadline, validation và GRADE V4.

### C. Core Java

- Cập nhật `workflow-v2.sha256` sang pin few-shot.
- `ScenarioEngine.accept()`: kiểm chính xác cặp hash/version baseline hoặc few-shot, luôn khớp hash của payload; không chấp nhận một version mới với hash cũ.
- Job đã gửi baseline được settle đúng như trước. Job mới dùng pin release hiện tại.
- Không sửa dữ liệu session, bản dịch, đáp án, learningContext hoặc profile đã lưu. Không cần migration DB.
- Giữ approvedPromptHash: local preview cập nhật pin sau khi kiểm tra; production không tự mở hoặc tự bỏ kiểm duyệt release.

### D. Tracing và bằng chứng

- Langfuse giữ promptHash/version, program/profile, context hash và input/output như hiện có.
- Bổ sung rõ promptMode, exampleSetVersion/hash và ID ví dụ theo stage vào metadata, không đưa credential. Root ghi các bước thuộc phase; nếu gate chặn sớm, chỉ những generation span đã xuất hiện mới là các bước thực sự gọi model. GRADE có promptMode=zero-shot và không có ví dụ.
- Gate pass/fail vẫn là metric kỹ thuật, không ghi thành human quality score.
- Mỗi lượt đo giữ request đầy đủ, output thô trước validator, lỗi, nguồn/model, usage và hash. Không đổi kết quả cũ hoặc sinh lại âm thầm.
- Không cần tạo/ghi đè dataset hay annotation queue trên Langfuse để tích hợp này hoạt động.

## 6. Kế hoạch kiểm thử

### P0 — Bảo toàn baseline

- Pin v1 vẫn `d43a3e228449dc64d7b6c103c2013558ac6e9415feabad8ff50e083900b0ae21`.
- Pin zero-shot cá nhân hóa vẫn `bd0e89b4391becdf84e4389595ff6fa9a4368f4323ac50eebaaf4dffb5b4c2aa`.
- Bộ ví dụ/bản few-shot v1 cũ không thay đổi; GRADE V4 không đổi.

### P1 — Ví dụ và lắp prompt

- Kiểm tất cả output bằng Pydantic hiện hành và các validator liên quan.
- Mẫu đạt phải qua gate; mẫu reviewer từ chối phải khiến gate từ chối.
- Kiểm quote, speaker, số từ thực tế, đủ segment dịch, đủ rubric và độ dài reasonVi.
- Kiểm input/context/profile nhất quán; không trùng brief benchmark.
- Kiểm mỗi request thật của mock gateway chứa đúng EXAMPLES của stage; không chỉ kiểm file tồn tại.

### P2 — Hợp đồng và tương thích

- Test job baseline và few-shot đều dùng đúng instructions/version/hash từ EXTRACT đến REVIEW/GRADE.
- Hash lạ, tráo hash/version hoặc thiếu context bị chặn trước khi gọi AI/commit kết quả.
- Test `execution_fingerprint`/metadata không nhận nhầm job cũ thành prompt mới.
- Test Core chấp nhận kết quả đúng pin; từ chối kết quả khác pin/version.
- Chạy bộ scenario Python, benchmark few-shot cũ và Java scenario integration/content/release liên quan; UTF-8 guard.

### P3 — Đo chất lượng thực tế

**Hoãn theo yêu cầu người dùng. Không tự chạy khi hoàn tất code.**

- Tái dùng 20 input và learningContext của lượt zero-shot đã lưu, cùng model/temperature/gates và nguồn cấu hình hiện tại.
- Mỗi bài một attempt few-shot; chỉ xử lý HTTP 429/503 rõ ràng theo cơ chế đổi nguồn/cooldown hiện có. Không retry để nâng điểm.
- So sánh: số bài qua gate, lỗi nguồn/vai, câu hỏi/đáp án/evidence, nhãn kỹ năng, dịch, độ dài; thêm latency/tokens.
- Đọc riêng bài được gate chấp nhận để tìm lỗi lọt. Nhãn agent và nhãn người dùng tách biệt.
- Baseline đã lưu là đối chứng lịch sử cùng cấu hình, không phải thử nghiệm ngẫu nhiên cùng thời điểm. Không kết luận ý nghĩa thống kê từ 20 bài.
- Không tự gọi 20 output đã thấy là tập holdout. Một lần kiểm định mới chưa từng dùng khi chọn ví dụ là bước sau để xác nhận tổng quát hóa.

### P4 — Đưa vào local và giao mã

Giao mã sau kiểm tra offline trong phiên này; kích hoạt runtime và smoke gọi AI thật hoãn tới lần thử sau.

- Kiểm local không có job đang chạy trước khi restart đúng preview đang dùng; giữ DB/broker/job ledger.
- Cập nhật pin được phép trong cấu hình runtime local, không đưa secret vào source/Git/process arguments.
- Xác minh health/capabilities và ít nhất một request từ đường Core/API dùng pin few-shot; không chỉ gọi trực tiếp Python.
- Commit chỉ file của nhiệm vụ, push nhánh hiện tại, xác minh remote và các workflow được kích hoạt. Không merge/deploy production nếu chưa được yêu cầu.

## 7. Tiêu chí chấp nhận và rollback

**Chấp nhận kỹ thuật:** bộ ví dụ/schema test đạt; đường chạy thực có few-shot; hash/version/trace khớp; job baseline và bài đã lưu không bị phá; GRADE V4 và mọi gate được giữ.

**Chấp nhận chất lượng:** báo cáo được kết quả thật trên cùng 20 case; không chỉ báo số bài qua gate. Muốn tuyên bố cải thiện cần tăng chất lượng nội dung mà không tăng lỗi nghiêm trọng lọt reviewer. Nếu chưa đủ bằng chứng, ghi rõ thử nghiệm/giới hạn, không gắn nhãn “đã đạt chuẩn”.

**Chi phí:** ghi tokens và latency thực tế từng stage. Không thêm model call để chọn ví dụ; không nhồi cả thư viện vào mỗi prompt. Khi chi phí tăng mà chất lượng không cải thiện, cắt ví dụ trùng lặp bằng ablation có lưu phiên bản, không xóa gate.

**Rollback:** dừng phát job mới bằng pin few-shot, hoàn tất/ghi nhận job đang chạy, đưa pin release/approved pin về bản trước bằng quy trình Git bình thường; giữ toàn bộ output mới và lịch sử trace. Không thay nội dung job đã gửi, không sửa hash giả, không tự ghi đè session.

## 8. Hướng mở rộng sau khi có số liệu

1. Người dùng/giáo viên duyệt các ví dụ và ghi chú; chuyển metadata từ agent reference sang human-approved chỉ khi có bằng chứng duyệt.
2. Bổ sung ví dụ giải thích, tranh luận, trải nghiệm và suy luận có căn cứ ở profile cho phép, dựa trên lỗi thật chưa được phủ.
3. Đo 1/2/3 ví dụ mỗi stage để chọn điểm cân bằng chất lượng/chi phí.
4. Có thể quản lý prompt qua phiên bản/label Langfuse khi cần cập nhật độc lập deployment; vẫn pin revision/hash, không tự lấy latest trong production.
5. Chỉ cân nhắc SFT sau khi có tập tham chiếu đã duyệt và baseline few-shot ổn định.

Tài liệu tham khảo: [hướng dẫn few-shot của Gemini](https://aistudio.google.com/docs/prompting-strategies#few-shot) khuyến nghị ví dụ cụ thể, đa dạng và nhất quán; số ví dụ cần thử nghiệm, quá nhiều có thể gây bám mẫu. [Langfuse version control](https://langfuse.com/docs/prompt-management/features/prompt-version-control) hỗ trợ version/label để so sánh và quay lui. Tích hợp lần này giữ Git/hash hiện có làm nguồn prompt, dùng Langfuse để quan sát, không di chuyển nguồn cấu hình.

## 9. Trạng thái thực hiện

### Đã triển khai trong source

- Bộ 13 reference pairs được nối vào sáu bước cá nhân hóa, không chỉ nằm trong công cụ benchmark. Mỗi request chỉ nhận ví dụ của bước đó. Không thêm lời gọi model hay dependency.
- Phiên bản: `SCENARIO_PERSONALIZED_FEWSHOT_V1_GRADE_V4`.
- Pin mới: `54f9fdeda01717f5c8853239357dad6dff2e0fa94df929f6c82e6d2497af52a1`.
- Giữ nguyên file prompt zero-shot cá nhân hóa, bộ few-shot v1 cũ, pin v1, program/profile definitions, GRADE V4 và mọi quality gate.
- Worker chọn prompt bằng pin của từng job; metadata/fingerprint/result không gán nhầm job cũ thành few-shot. Core vẫn nhận đúng kết quả baseline đã gửi và chặn tráo version/hash.
- Langfuse nhận thông tin bộ ví dụ qua tracing hiện có. Kiểm tra SDK dùng exporter trong bộ nhớ, không gửi trace mới lên cloud. Live trace audit hoãn cùng smoke AI thật.
- Đóng gói JSON trong Python wheel; đã build offline và đối chiếu byte của asset/module/workflow trong wheel với source.
- Không chỉnh DB, session, learningContext đã lưu, cấu hình runtime hay quota. Không tự bật cờ approval, không tạo dữ liệu đánh giá mới.

### Kiểm tra có thể chạy lại, không gọi model

Kết quả tại pin mới: **179 test Python** (workflow, few-shot cá nhân hóa, tracing SDK và jobs), **17 test benchmark offline cũ**, **104 test Java** đều đạt. Kiểm tra UTF-8 và `git diff --check` đạt. Cảnh báo deprecation từ Python 3.14/FastAPI không làm test thất bại; chưa thay dependency trong nhiệm vụ này.

Tại thư mục repo:

```powershell
python -m pytest services/ai-generation/tests/test_scenario_personalized_fewshot.py services/ai-generation/tests/test_scenario_workflow.py services/ai-generation/tests/test_observability.py services/ai-generation/tests/test_jobs.py -q
python -m pytest tools/ai-eval/test_scenario_fewshot.py tools/ai-eval/test_scenario_prompt_benchmark.py -q
mvn --batch-mode --no-transfer-progress '-Dtest=ScenarioEngineIntegrationTest,ScenarioContentTest,ScenarioProgramTest,ScenarioIntegrationTest,ScenarioDisabledIntegrationTest' test
git diff --check
```

Các kiểm tra gọi gateway giả lập, cơ sở dữ liệu H2 và exporter trong bộ nhớ. Không nạp file credential runtime. Không đặt `LANGFUSE_TRACING_ENABLED=false` khi chạy test SDK: SDK sẽ bị tắt trước khi fixture gắn exporter trong bộ nhớ, khiến test quan sát thất bại dù code tracing đúng.

### Thao tác bảo trì và lần thử tiếp theo

1. Đọc asset `services/ai-generation/app/scenario_personalized_fewshot_examples_v1.json`; mỗi mục có stage, input, expectedOutput và nguồn nhãn. Đây là reference do agent soạn/chuyển đổi, không phải đáp án chuẩn do giáo viên duyệt.
2. Sau khi phiên bản được phát hành, muốn thay ví dụ thì tạo version mới, giữ artifact cũ cho job đang chờ/replay. Không chỉnh asset rồi giữ nguyên pin, không sửa baseline để có điểm so sánh đẹp hơn.
3. Chạy test schema/gate/context trước. Khi cần pin mới, tính bằng `personalized_prompt_hash()` trong môi trường Python của dịch vụ rồi cập nhật resource Core tương ứng; không đoán hoặc ghi hash giả.
4. Khi được yêu cầu thử thật, triển khai cùng phiên bản Core/AI worker/API; kiểm tra job đang chạy trước khi restart. Sau đó mới cập nhật approved pin của local preview. Chỉ deploy code nhưng giữ approved pin cũ sẽ không tự mở phát bài mới: đó là cơ chế kiểm soát release có chủ đích.
5. Chạy một smoke qua Core để xác minh pin/context và generation messages, rồi đối chiếu trace thực nhận trên Langfuse. Chỉ sau đó mới chạy bộ 20 đối chứng đã lưu, không dùng runner zero-shot cũ nguyên xi vì runner đó khóa pin baseline.
6. Lưu mọi response thô, lỗi và usage ở thư mục riêng ngoài Git; đọc cả bài được gate duyệt để phát hiện lỗi lọt. Chưa có kết quả bước này thì không tuyên bố few-shot mới đã tăng chất lượng, giảm chi phí hoặc đạt chuẩn CEFR/TOEIC/IELTS.

Không thêm RAG, kho vector, dịch vụ chọn ví dụ hay cơ chế train trong lần tích hợp này. Các phần đó chưa cần thiết để ví dụ theo bước đến đúng model và có thể kiểm chứng.

## 10. Sửa hợp đồng reviewer sau khi duyệt thủ công ngày 03/11/2026

Hai mâu thuẫn được xác nhận trong prompt few-shot cá nhân hóa V1:

- REVIEW_QUESTIONS kế thừa Output Contract `all eight boolean checks` từ V5, dù `PersonalizedQuestionCheck` đã có 10 cờ bắt buộc. Hai cờ bổ sung là `skillAppropriate` và `groundingSupported`.
- REVIEW kế thừa yêu cầu đúng ba rubric, nhưng phần cá nhân hóa, schema `PersonalizedReview` và ví dụ đã yêu cầu năm: `SOURCE_ROLES`, `DIALOGUE`, `TRANSLATIONS`, `PROFILE`, `LEARNING_GOAL`.

Đây là lỗi chỉ dẫn prompt, không phải thiếu trường trong schema hoặc thiếu gate backend. Luồng chưa cá nhân hóa vẫn sử dụng đúng 8 cờ/3 rubric. Không sửa schema chung thành 10/5 vì sẽ phá hợp đồng đó.

Bản sửa `SCENARIO_PERSONALIZED_FEWSHOT_V2_GRADE_V4` thay Output Contract thành `all ten boolean checks` kèm danh sách đầy đủ, và `exactly five rubricChecks, one each for SOURCE_ROLES,DIALOGUE,TRANSLATIONS,PROFILE,LEARNING_GOAL`. Phần Personalized Rubric dẫn lại cùng hợp đồng năm mục. Pin mới: `6a857865dddd79783a50c6dbf45bfdd281feeb56b681bdff34abd24c4df5b2e3`.

Chỉ hai instruction reviewer thay đổi. Bộ 13 ví dụ, schema, gate, token budget và GRADE V4 giữ nguyên. Worker/Core nhận cả baseline, few-shot V1 và V2 theo đúng cặp version/hash; job cũ không bị gắn nhãn lại. Resource pin cho job mới chuyển sang V2. Runtime đã chạy cần phát hành đồng bộ Core/worker/API và cập nhật approved pin theo quy trình hiện có; việc đổi source không tự xác nhận runtime đã cập nhật.

Kiểm tra hồi quy đối chiếu Output Contract với schema và ví dụ; từng cờ thiếu/false hoặc rubric thiếu/false/trùng đều phải bị từ chối. Kiểm tra cả job pin cũ, pin mới, sai hash và tráo phiên bản. Snapshot `prompts-fewshot-duyet-thu-cong.md` của lượt 20 bài giữ làm bằng chứng lịch sử; bản sửa được xuất riêng thành `prompts-fewshot-duyet-thu-cong-v2.md`.

Loại bỏ mâu thuẫn giúp model nhận chỉ dẫn nhất quán, nhưng không chứng minh tăng chất lượng ngữ nghĩa hay giảm false acceptance. Không gán kết quả 14/20 của V1 cho V2, không sinh lại bộ 20 bài trong nhiệm vụ sửa hợp đồng này.

Kết quả kiểm tra bản sửa: 216 test Python workflow/few-shot/tracing/jobs, 17 test benchmark offline và 107 test Java liên quan đều đạt; kiểm tra UTF-8 và `git diff --check` đạt. Các test dùng gateway giả lập, không tạo bài AI mới. Chưa restart local hoặc deploy production trong nhiệm vụ này.
