# Kế hoạch chuẩn hóa phân loại CEFR cho Conversation AI

Ngày: 21/10/2026
Phạm vi: luật phân loại nội dung và logic Conversation AI.
Ngoài phạm vi: audio, TTS, tốc độ phát, căn chỉnh đoạn audio, voice room và microphone.

Tài liệu này dành cho model thực hiện code. Model phải đọc toàn bộ tài liệu trước khi sửa, giữ nguyên các thay đổi khác trong working tree và chỉ triển khai những bước đã được đánh dấu là được phép trong từng giai đoạn.

## Mục tiêu

Chuẩn hóa cách phân loại bài nghe theo hai lớp riêng:

1. **Năng lực CEFR:** người học cần xử lý loại nội dung, quan hệ ý và mức suy luận nào.
2. **Trạng thái sản phẩm:** Conversation AI đã hỗ trợ mức nào, với giới hạn số từ, số slot và cách chấm nào.

Không dùng số từ đơn độc để tuyên bố một task đạt CEFR. Số từ chỉ là một ràng buộc tạo nội dung của sản phẩm.

## Phạm vi

### Trong phạm vi

- Sửa mô hình mô tả và sinh nội dung cho B1.
- Bổ sung thiết kế phân loại B2, C1 và C2.
- Tách các loại nhiệm vụ: thông tin trực tiếp, ý chính, quan điểm/thái độ, quan hệ nguyên nhân-kết quả, thay đổi, điều kiện, suy luận và dự đoán.
- Bổ sung trường hoặc cấu trúc cần thiết để mô tả bằng chứng trực tiếp và bằng chứng suy luận.
- Cập nhật validation, prompt, schema, API contract, frontend level list và migration khi mức mới được bật.
- Tạo bộ kiểm tra hồi quy cho B1 và bộ benchmark riêng cho B2/C1/C2.

### Ngoài phạm vi

- Không sửa audio hoặc TTS.
- Không thay đổi tốc độ đọc, giọng đọc, số lần nghe hoặc căn chỉnh audio.
- Không kết luận trình độ CEFR của người học từ một vài task.
- Không bật B2/C1/C2 trên giao diện production trước khi benchmark và review đạt yêu cầu.
- Không dùng “C2 = B1 dài hơn” hoặc “C2 = C1 nhiều từ hơn”.

## Hiện trạng phải giữ làm baseline

Conversation AI hiện chỉ nhận A1, A2, B1.

- Backend: [ConversationService.java:24](../src/main/java/com/dictalab/service/ConversationService.java:24) có LEVELS = Set.of("A1", "A2", "B1").
- Catalog: [ConversationService.java:147](../src/main/java/com/dictalab/service/ConversationService.java:147) trả về ba mức.
- Frontend: [conversation.js:101](../src/main/resources/static/js/conversation.js:101) chỉ hiển thị ba mức.
- Python schema: [schemas.py:16](../services/ai-generation/app/schemas.py:16) chỉ nhận A1|A2|B1.
- OpenAPI: [conversation-generation-openapi.yaml:84](conversation-generation-openapi.yaml:84) và [conversation-generation-openapi.yaml:139](conversation-generation-openapi.yaml:139) chỉ khai báo ba mức.
- Database: [V37__conversation_scenario_catalog.sql:8](../src/main/resources/db/migration/V37__conversation_scenario_catalog.sql:8) giới hạn difficulty ở ba mức.

### Luật hiện tại

| Mức | Khoảng từ của task chính | Số slot/fact | Mục tiêu hiện tại |
|---|---:|---:|---|
| A1 | 8–35 | Đúng 2 | Thông tin trực tiếp, quen thuộc, câu ngắn |
| A2 | 18–55 | 2–3 | Chi tiết nối tiếp, ý chính, lý do hoặc thay đổi đơn giản |
| B1 | 30–90 | 2–3 | Ý chính, mục đích, lý do, hướng dẫn hoặc điều kiện |

Các khoảng này nằm trong [ConversationService.java:722](../src/main/java/com/dictalab/service/ConversationService.java:722). Mọi task còn chịu kiểm tra chung 8–90 từ tại [ConversationService.java:1038](../src/main/java/com/dictalab/service/ConversationService.java:1038). Phiên legacy không có curated starter dùng khoảng 20–65 từ. Câu warm-up đầu tiên dùng khoảng 8–55 từ và không đại diện đầy đủ cho độ khó A2/B1.

Prompt hiện tại quy định:

- A1: mệnh đề ngắn, từ quen thuộc, hiện tại đơn, số/giờ cụ thể; tránh mệnh đề quan hệ, distractor và self-correction.
- A2: câu ngắn có liên kết, quá khứ/kế hoạch/because quen thuộc; tối đa một thay đổi rõ ràng.
- B1: lời nói liên kết về chủ đề quen thuộc, mục đích/lý do/hướng dẫn/điều kiện; tương phản là tùy chọn.

Chi tiết nằm trong [ConversationGenerationPrompt.java:168](../src/main/java/com/dictalab/service/ConversationGenerationPrompt.java:168).

## Căn cứ CEFR cần áp dụng

Council of Europe mô tả CEFR bằng các can-do descriptors và Companion Volume 2020 đã mở rộng các mô tả, trong đó có Identifying cues and inferring. [CEFR descriptors](https://www.coe.int/en/web/common-european-framework-reference-languages/cefr-descriptors), [Companion Volume 2020](https://rm.coe.int/cefr-companion-volume-with-new-descriptors-2020/16809ea0d4)

Theo Companion Volume:

- B1 có thể dùng connector và ngữ cảnh để theo dõi lập luận hoặc chuỗi sự kiện, suy ra nghĩa của từ/câu chưa hoàn toàn trực tiếp trong chủ đề quen thuộc, đồng thời nghe một truyện ngắn và dự đoán điều xảy ra tiếp theo.
- B2 dùng nhiều chiến lược để hiểu, tập trung vào ý chính và kiểm tra hiểu bằng dấu hiệu ngữ cảnh.
- C1 dùng dấu hiệu ngữ cảnh, ngữ pháp và từ vựng để suy ra thái độ, tâm trạng, ý định và dự đoán phần tiếp theo.
- C2 không có mô tả suy luận riêng trong thang này và được quy chiếu về C1; C2 khác biệt chủ yếu ở độ dễ dàng, độ rộng, độ chính xác, nội dung khẩu ngữ/chuyên môn và sắc thái.

Cambridge B1 Preliminary Listening có các dạng kiểm tra thông tin chính, gist, thông tin cụ thể, thái độ và quan điểm người nói. Vì vậy B1 của sản phẩm không nên chỉ hỏi hai dữ kiện trực tiếp. [B1 Preliminary Listening](https://www.cambridgeenglish.org/exams-and-tests/qualifications/preliminary/format/?skill=grammar%2Creading)

Các bài thi Cambridge ở mức cao hơn cũng cho thấy ranh giới nhiệm vụ:

- B2 kiểm tra feeling, attitude, opinion, purpose, agreement, gist, detail và specific information. [B2 First Listening](https://www.cambridgeenglish.org/exams-and-tests/qualifications/first/format/?level=independent&skill=listening)
- C1 thêm speaker purpose, function, agreement, course of action, interpreting context và hội thoại nhiều người dài hơn. [C1 Advanced Listening](https://www.cambridgeenglish.org/exams-and-tests/qualifications/advanced/format/)
- C2 thêm inference, implication, function, addressee, tone, attitude, context và nội dung cần hiểu với độ dễ dàng cao. [C2 Proficiency Listening](https://www.cambridgeenglish.org/exams-and-tests/qualifications/proficiency/format/)

Các nguồn Cambridge là ví dụ về thiết kế đánh giá ở từng mức, không phải quy định rằng Conversation phải sao chép nguyên dạng đề thi.

## Ma trận năng lực B1–C2

| Trục | B1 | B2 | C1 | C2 |
|---|---|---|---|---|
| Ý chính | Hiểu ý chính trong lời nói rõ, chuẩn, chủ đề quen thuộc | Hiểu ý chính của nội dung phức tạp hơn, gồm chủ đề trừu tượng hoặc chuyên môn trong lĩnh vực quen thuộc | Hiểu ý chính và cấu trúc của bài dài, phức tạp | Hiểu gần như mọi loại lời nói với độ dễ dàng cao |
| Chi tiết | Lấy chi tiết quan trọng, không cần mọi chi tiết | Theo dõi chi tiết và quan hệ giữa nhiều ý | Theo dõi chi tiết phân tán, chuyển ý và các quan hệ không luôn được nói rõ | Nắm chi tiết, sắc thái và thay đổi nhỏ trong ý nghĩa |
| Suy luận | Suy ra nghĩa từ ngữ cảnh quen thuộc; theo dõi connector; dự đoán bước tiếp theo trong truyện ngắn | Dùng nhiều dấu hiệu ngữ cảnh để kiểm tra hiểu; bắt đầu xử lý quan hệ không trực tiếp | Suy ra thái độ, tâm trạng, ý định; dự đoán diễn biến từ dấu hiệu ngữ cảnh/ngữ pháp/từ vựng | Hiểu hàm ý, ám chỉ, hài hước, chơi chữ và quan hệ xã hội/văn hóa tinh tế |
| Lập luận | Theo dõi chuỗi lý do đơn giản, mục đích, điều kiện và trình tự | Theo dõi lập luận nhiều bước, tương phản, đánh giá và quan điểm | Theo dõi lập luận dài, phức tạp, có quan hệ logic ẩn hoặc chuyển hướng | Tái cấu trúc lập luận và phân biệt sắc thái rất nhỏ giữa các cách diễn đạt |
| Quan điểm/thái độ | Nhận biết quan điểm hoặc thái độ được thể hiện tương đối rõ | Xác định opinion, attitude, feeling, agreement và purpose | Phân biệt attitude, intention, feeling, function, agreement và course of action trong tương tác | Hiểu tone, irony, allusion, implicit stance và thay đổi sắc thái theo ngữ điệu/ngữ cảnh |
| Tương tác | Thường là một người nói hoặc hội thoại ngắn, rõ | Có thể theo dõi trao đổi tự nhiên hơn và nhiều quan điểm | Theo dõi nhiều người nói, chồng lượt, digression và chuyển lượt tự nhiên | Theo dõi tương tác tự nhiên, khẩu ngữ, hàm ý và thay đổi vai trò người nói |
| Từ vựng/ngữ pháp | Quen thuộc, chủ đề đời sống | Rộng hơn, có trừu tượng và thuật ngữ trong lĩnh vực quen thuộc | Rộng, linh hoạt, học thuật/nghề nghiệp, thành ngữ và khẩu ngữ | Rất rộng, gồm thuật ngữ lạ, thành ngữ, khẩu ngữ, giọng vùng miền và cách nói không được đơn giản hóa |
| Bằng chứng cần chấm | Dữ kiện trực tiếp + một số suy luận đơn giản | Ý chính/chi tiết + quan điểm, lý do, quan hệ và suy luận có căn cứ | Quan điểm/thái độ/ý định + quan hệ ẩn và diễn giải ngữ cảnh | Hàm ý, tone, ám chỉ, sắc thái và tổng hợp nhiều tín hiệu |

## Định nghĩa sản phẩm đề xuất

Đây là định nghĩa để code và benchmark. Không bật mức mới trên UI chỉ vì đã thêm enum.

### Quy tắc đọc tài liệu và giới hạn của bản thiết kế

Các con số về số bước suy luận, số dấu hiệu, số slot và ngưỡng đạt benchmark dưới đây là giả thuyết thiết kế sản phẩm, không phải quy định CEFR. Các mục tiêu liệt kê cho B2/C1/C2 là yêu cầu bao phủ của bộ benchmark; không bắt mọi task đơn lẻ phải chứa toàn bộ hoặc một kỹ năng đặc trưng. Bài C1/C2 vẫn có thể hỏi thông tin trực tiếp. Thái độ, quan điểm, mục đích và suy luận không phải kỹ năng độc quyền của một mức.

Các dòng Cambridge dùng từ “thêm” ở trên chỉ mô tả nội dung được nguồn đó liệt kê, không chứng minh kỹ năng đó vắng mặt ở mức thấp hơn. Riêng implication/tone trên trang C2 có cả phần Reading; không được lấy danh sách Reading làm chuẩn Listening. Listening C2 liệt kê gist/detail/function/purpose/feeling/attitude/opinion và inference. Ranh giới mức cần đánh giá đồng thời độ phức tạp văn bản, mức hiển ngôn, quan hệ diễn ngôn và yêu cầu câu hỏi.

Giữ kiểm tra exact substring cho mọi trích dẫn bằng chứng, kể cả bài suy luận. Chỉ bỏ yêu cầu **kết luận phải được nói nguyên văn**. Một đoạn trích có thể chứa đủ nhiều dấu hiệu; không bắt hai câu hoặc hai segment khi một segment đã đủ. Không thay hệ thống chấm nghĩa hiện có bằng so khớp đáp án nguyên văn: hệ thống đã chấp nhận paraphrase.

Trong giai đoạn chưa sửa audio, chỉ triển khai câu hỏi giải được từ transcript, giữ một người nói như luồng hiện tại. Nhiều người nói, chồng lượt, sắc thái chỉ nghe được qua giọng và giọng vùng miền là phạm vi nghiên cứu/làm sau. C2 có thể thử nghiệm hàm ý và sắc thái thể hiện bằng từ ngữ; chưa được tuyên bố bao phủ đầy đủ năng lực nghe C2.

Tài liệu này là bản để chủ dự án review. Các trạng thái SHADOW/PLANNED, kiến trúc trường mới và ngưỡng benchmark là đề xuất. Trước khi giao code cần chốt schema evidence cụ thể, khoảng từ thử nghiệm của mức mới và rubric C2; model code không tự coi các câu hỏi cuối tài liệu là đã được duyệt.

### B1 sau khi chuẩn hóa

B1 vẫn giữ 30–90 từ và 2–3 slot/fact, nhưng loại slot được mở rộng:

- details: thông tin cụ thể quan trọng.
- gist: thông điệp chính hoặc mục đích chung.
- reason: nguyên nhân hoặc mục đích.
- change: thay đổi cuối cùng so với kế hoạch cũ.
- instructions: hành động hoặc thứ tự hành động.
- condition: điều kiện cần có trước khi hành động.
- inference: ý nghĩa hoặc bước tiếp theo có thể suy ra từ ít nhất hai dấu hiệu rõ ràng trong bài; các dấu hiệu có thể nằm trong cùng một câu hoặc đoạn trích.
- attitude: thái độ hoặc quan điểm được thể hiện bằng từ ngữ rõ, không yêu cầu đọc tone audio ở giai đoạn này.

Giới hạn B1:

- Suy luận phải có ít nhất hai dấu hiệu trong transcript, ví dụ connector + sự kiện hoặc nguyên nhân + kết quả.
- Không yêu cầu kiến thức bên ngoài.
- Không dùng hàm ý quá tinh tế, irony, chơi chữ hoặc phụ thuộc vào ngữ điệu audio.
- Không biến mọi bài B1 thành bài suy luận; mỗi task chỉ cần một mục tiêu chính và tối đa một mục tiêu phụ.
- Question phải cho phép trả lời bằng ý nghĩa, không bắt người học lặp nguyên văn.

### B2

B2 phải tăng độ khó ở lập luận và quan điểm, không chỉ tăng độ dài.

Nội dung được phép:

- Lời nói dài hơn về chủ đề quen thuộc, xã hội, học tập, công việc hoặc chuyên môn vừa phải.
- Quan điểm, lý do ủng hộ/phản đối, so sánh, tương phản, đánh giá và quyết định.
- Một phần thông tin có thể được suy ra từ ngữ cảnh, nhưng các bước suy luận phải kiểm chứng được.
- Hội thoại hai người hoặc nhiều người ở mức vừa phải; không yêu cầu chồng lượt quá dày.
- Có thể có một vài cách nói tự nhiên, nhưng chưa dùng mật độ thành ngữ hoặc giọng vùng miền cao.

Bộ benchmark B2 phải bao phủ các năng lực sau; không bắt mọi task đơn lẻ phải hỏi một mục tiêu đặc trưng:

    main argument / supporting reason / contrast / opinion / attitude / agreement

Không được gọi là B2 nếu task chỉ có ba dữ kiện trực tiếp giống B1 và thêm từ vựng khó.

### C1

C1 phải tăng độ phức tạp của quan hệ ý và hàm ý.

Nội dung được phép:

- Bài nói dài, phức tạp, học thuật, nghề nghiệp hoặc xã hội.
- Quan hệ giữa các ý có thể không được nói hết bằng because, therefore hoặc connector rõ ràng.
- Nhận biết thái độ, ý định, mức chắc chắn, đồng tình/không đồng tình và hướng hành động.
- Hội thoại nhiều người, chuyển lượt, phản hồi, ngắt mạch và digression.
- Cách nói tự nhiên, khẩu ngữ và thành ngữ ở mức có kiểm soát.

Bộ benchmark C1 phải có bằng chứng cho các mục tiêu sau; task hỏi trực tiếp vẫn hợp lệ khi tổng thể nội dung và yêu cầu phù hợp:

    implicit relation / speaker intention / attitude / stance / context interpretation

Không được chấm C1 chỉ bằng exact substring của một fact. Cần lưu các evidence segment hoặc rationale ngắn giải thích vì sao kết luận được suy ra từ bài.

### C2

C2 không phải phiên bản dài hơn của C1.

Nội dung C2 có thể gồm:

- Bài giảng hoặc thảo luận chuyên môn có thuật ngữ chưa quen.
- Khẩu ngữ tự nhiên, thành ngữ, ám chỉ, hài hước, chơi chữ và hàm ý văn hóa.
- Tốc độ tự nhiên và thay đổi sắc thái nhỏ trong thái độ hoặc mục đích.
- Nhiều người nói, thông tin phân tán và quan hệ không được báo hiệu rõ.
- Nhiệm vụ yêu cầu tổng hợp, tái cấu trúc lập luận hoặc phân biệt các cách hiểu gần nhau.

Bộ benchmark C2 sau này cần bao phủ các năng lực sau, theo rubric riêng:

    implication / tone / allusion / humour / sociocultural meaning / fine-grained stance

Nếu hệ thống chưa có rubric cho các mục tiêu này thì C2 phải giữ trạng thái planned, không đưa vào lựa chọn người học.

## Mô hình dữ liệu cần bổ sung

Model code nên dùng một level profile tập trung thay vì rải literal ở nhiều prompt.

Profile tối thiểu:

    level
    status: SUPPORTED | SHADOW | PLANNED
    minWords
    maxWords
    minSlots
    maxSlots
    allowedSkills
    allowedTaskTypes
    maxInferenceDepth
    requiresExplicitEvidence
    requiresInferenceEvidence
    multiSpeakerAllowed
    implicitMeaningAllowed

Giá trị ban đầu:

| Mức | Status ban đầu | min/max slot | Inference | Evidence |
|---|---|---:|---|---|
| A1 | SUPPORTED | 2/2 | Không | Trực tiếp |
| A2 | SUPPORTED | 2/3 | Suy luận rất đơn giản nếu được nói rõ bằng ngữ cảnh | Trực tiếp, có thể một quan hệ đơn giản |
| B1 | SUPPORTED sau khi sửa rubric | 2/3 | Một bước suy luận hoặc dự đoán có căn cứ | Trực tiếp + suy luận có transcript evidence |
| B2 | SHADOW | 2/3 | Một đến hai bước, lập luận/quan điểm | Direct + relation/stance evidence |
| C1 | SHADOW | 2/3 hoặc task-specific | Quan hệ ẩn, thái độ, ý định | Segment evidence + rationale |
| C2 | PLANNED | Task-specific | Hàm ý, tone, ám chỉ, sắc thái | Multi-segment evidence + rationale |

Số từ chính thức cho B2/C1/C2 chưa được chốt. B2/C1 shadow tạm dùng cùng khung 30–90 từ với B1 để so sánh độ phức tạp diễn ngôn và yêu cầu câu hỏi trong format ngắn hiện có. Đây là khung thử nghiệm, không chứng minh khả năng theo dõi bài nói dài B2/C1 và không được coi là giới hạn CEFR. C2 chưa có khoảng từ hoặc format sinh bài.

## Kế hoạch triển khai theo thứ tự

### Tiến độ thực hiện ngày 21/10/2026

- **Bước 1 hoàn tất:** `ConversationLevelProfile` là nguồn chung cho danh sách mức được hỗ trợ, khoảng từ, số slot và mô tả mức ở backend. Catalog, context sinh bài, few-shot và schema local đều dùng profile này.
- Giữ A1 8–35 từ/2 slot, A2 18–55 từ/2–3 slot, B1 30–90 từ/2–3 slot; legacy 20–65 từ và warm-up 8–55 từ. B2/C1/C2 vẫn bị từ chối khi tạo phiên.
- Kiểm tra local: 98 test Java pass, 1 test race có điều kiện chưa bật; 28 test JavaScript pass; kiểm tra UTF-8 và whitespace pass. Ca phát lại audio từng lỗi 503 ở lần chạy đầu; chạy riêng và chạy lại toàn nhóm đều pass, không sửa code audio.
- Workflow AI Platform đã bổ sung test profile mới. Kết quả local không thay thế benchmark sinh bài bằng model thật.
- **Đã triển khai code bước 2–4:** B1 bổ sung thái độ/quan điểm và suy luận/dự đoán; giữ gist và các mục tiêu cũ. Prompt sinh bài, reviewer độc lập và hướng dẫn chấm nghĩa đã được đồng bộ. Chưa xác nhận chất lượng sinh bài đạt cổng benchmark.
- Fact mới có `taskType` riêng với level. A1 cho phép `direct_detail`; A2 thêm `gist`, `reason_purpose`, `change_revision`, `instruction_sequence`, `condition`; B1 thêm `opinion_attitude`, `inference_prediction`. Fact của phiên cũ không có trường mới vẫn đọc được.
- `inference_prediction` bắt buộc có `reasoningEvidence: {segmentsEn, rationaleEn}`. Có 1–4 trích dẫn khác nhau, mỗi đoạn tối đa 300 ký tự; ít nhất một đoạn chứa nguyên văn `evidenceEn`; rationale tối đa 300 ký tự. Một đoạn có thể chứa nhiều dấu hiệu. Reviewer kiểm tra kết luận và quan hệ giữa các dấu hiệu, không mặc nhiên tin rationale. Mỗi bài B1 tối đa một mục tiêu suy luận. Các giới hạn này là luật sản phẩm.
- Metadata evidence được giữ qua dịch, lưu phiên và chấm; không được gửi như đáp án trong màn hình trước khi người học trả lời. Grader chấp nhận diễn đạt tương đương và không bắt nhắc lại rationale nếu câu hỏi không hỏi lý do.
- **Đã sửa validator độ dài Python:** lấy min/max từ context do Java gửi; request độc lập dùng profile mặc định A1/A2/B1. Legacy có thể truyền 20–65. Lỗi độ dài kích hoạt đúng một lần repair với lỗi thực tế và output bị từ chối; nếu vẫn lỗi thì dừng. Kiểm tra lại 30 bài cũ bắt đúng 10 lỗi: A1 1/12, A2 3/9, B1 6/9. Đây không phải kết quả sinh lại 30 bài.
- Python tự dựng `turn_brief` từ profile, gồm vai người nói/người nghe và mục tiêu giữa khoảng từ (B1 60 từ, legacy 42 từ). Không phụ thuộc vào instruction Java bị server bỏ qua. Log validation có mã lỗi cụ thể và số từ; không ghi transcript vào log lỗi.
- Bundle `conversation-cefr-b1-v7-20261021` đồng bộ instruction/schema/few-shot/profile với Java bằng test contract. Bundle cũ được giữ nguyên. Bundle mới ở `development`, `releaseAllowed=false`. Cần cập nhật Java và Python cùng phiên bản khi triển khai; thay prompt Java không tự cập nhật bundle Python đang chạy.
- Dataset độc lập 10 tình huống × 3 seed (17/43/71) ở `services/ai-generation/tests/fixtures/evaluation/cefr-b1-v7-10x3.json`, có ba mục tiêu B1: attitude, inference/prediction và gist. Chưa chạy đủ benchmark 30 bài mới và semantic review.
- Kiểm tra giai đoạn này: 105 test Java pass, một test race chưa bật; 177 test Python và 28 test JavaScript pass; smoke Java → Python → Ollama giả pass. UTF-8 và whitespace pass.
- **Sinh thật chưa đạt cổng chất lượng.** Smoke hai bài đầu: một bài qua cấu trúc nhưng dùng vai khách/nhân viên thiếu nhất quán; một bài bị chặn sau repair. Chẩn đoán bài thứ hai phát hiện 26 từ và trích dẫn ngắn nằm trong segment dài bị kiểm tra quá chặt. Đã sửa phép chứa trích dẫn và thêm mục tiêu số từ; lần thử lại cùng tình huống vẫn bị chặn. Output chẩn đoán còn gắn nhãn inference cho một hành động nói trực tiếp. Bằng chứng tổng hợp ở `artifacts/reports/ai-platform-cefr-b1-v7-smoke-20261021.json`. Cần tiếp tục chỉnh chất lượng generation và review trước khi dùng B1 làm baseline cho B2.
- **Đã bổ sung code bước 5–6:** profile B2/C1 ở SHADOW, schema và instruction riêng, runner offline dùng lại pipeline sinh/dịch/validate/repair. `argument_relation` yêu cầu evidence cho support/contrast/concession/agreement; C1 thêm `implicit_intention`. Mỗi reasoning có 1–4 đoạn exact quote và rationale, ít nhất một đoạn chứa `evidenceEn`. Một đoạn có thể có nhiều dấu hiệu. B2/C1 cho tối đa hai bước suy luận như giả thuyết sản phẩm; reviewer phải xác nhận độ sâu và quan hệ ý, validator cấu trúc không tự chứng minh được điều này.
- Bundle bất biến `conversation-cefr-shadow-v1-20261021` chứa profile/schema/few-shot/rubric, `evaluationOnly=true`, `releaseAllowed=false`. Không thay bundle B1 mặc định. CLI bắt buộc `--shadow`, chỉ dùng Ollama ngoài production, không có cloud fallback; profile do bundle quyết định. C2 bị chặn trước khi gọi model. Context của bundle được reset sau cả thành công và lỗi.
- Dataset `cefr-shadow-v1-10x3.json` có 10 tình huống B2 và 10 C1, mỗi tình huống ba seed 17/43/71, gồm tiếp diễn và lượt 4 độc lập. Dataset benchmark độc lập với ví dụ trong bundle. Báo cáo shadow kiểm tra hash dataset/bundle, model, config, loại câu hỏi và bằng chứng; giữ đủ mẫu trong mẫu số kể cả timeout hoặc thiếu capture. Review semantic thiếu/sai kiểu boolean tính fail. Cần review thủ công có tham chiếu cho từng task type trong bộ thử nghiệm.
- **Bước 7 đã có rubric C2 dự thảo** ở phần dưới. C2 giữ PLANNED: chưa có dữ liệu được review để hiệu chuẩn rubric, chưa chốt format/độ dài, không có generation hoặc UI. Các bài hàm ý chỉ dựa trên transcript sau này sẽ không được coi là bao phủ toàn bộ năng lực nghe C2.
- **Bước 8 đồng bộ theo trạng thái:** Java có status riêng và vẫn lọc SUPPORTED khi tạo phiên/catalog. Python HTTP/queue và OpenAPI request vẫn chỉ A1/A2/B1; lớp request shadow chỉ dùng offline. Database/frontend chưa mở mức mới nên không cần migration. Header `X-AI-Mode: shadow` có sẵn là cơ chế so sánh provider cho mức đã hỗ trợ, không mở B2/C1. Audio không đổi.
- Theo yêu cầu tiếp tục code trước, **chưa chạy generation benchmark B2/C1, chưa chạy lại 30 bài B1**, chưa semantic review hoặc hiệu chuẩn rubric C2. Các mức không được tự chuyển SUPPORTED theo kết quả test kỹ thuật.
- Kiểm tra kỹ thuật cho bước 5–8: 107 test Java pass, một test race có điều kiện chưa bật; 213 test Python pass, gồm cách ly shadow, grounding, repair, pin config/dataset và báo cáo fail-closed. Kiểm tra UTF-8/biên dịch Python và whitespace pass. Đây không phải kết quả benchmark CEFR hoặc sinh bài bằng model thật.

### 1. Tập trung hóa profile

- Tạo một nguồn profile dùng chung ở backend.
- Cho ConversationService, ConversationGenerationPrompt và client generation dùng cùng profile.
- Loại bỏ các danh sách A1/A2/B1 lặp lại khi không làm thay đổi behavior hiện tại.
- Giữ tương thích với phiên legacy: nếu không có profile mới, dùng khung 20–65 như hiện tại.

### 2. Sửa B1 trước

- Bổ sung inference, attitude và gist vào tập mục tiêu hợp lệ.
- Sửa prompt B1 để cho phép suy ra bước tiếp theo, nghĩa từ ngữ cảnh, ý chính, thái độ và quan điểm được thể hiện rõ.
- Giữ giới hạn B1: không dùng irony, tone phụ thuộc audio hoặc suy luận kiến thức ngoài bài.
- Sửa review prompt để phân biệt câu hỏi dữ kiện trực tiếp, câu hỏi quan hệ nguyên nhân/kết quả và câu hỏi gist/attitude/inference.
- Không bắt B1 trả lời bằng từ nguyên văn nếu ý nghĩa đúng.

### 3. Sửa mô hình fact/evidence

- Giữ facts cho câu trả lời trực tiếp.
- Thêm loại evidence có cấu trúc cho mục tiêu suy luận, ví dụ evidenceSegments hoặc reasoningEvidence.
- Mỗi suy luận phải trỏ tới các đoạn transcript làm căn cứ.
- Không cho phép inference nếu không có evidence segment.
- Không dùng một evidenceEn duy nhất để giả vờ chứng minh một kết luận được tạo từ nhiều câu.

### 4. Tách loại task khỏi level

Level mô tả độ phức tạp tổng thể; taskType mô tả người học phải làm gì.

Task type tối thiểu:

    direct_detail
    gist
    reason_purpose
    change_revision
    instruction_sequence
    condition
    opinion_attitude
    inference_prediction
    argument_relation
    implicit_intention
    implication_tone

Không để model tự gắn nhãn theo từ khóa. Validator phải kiểm tra task type với cấu trúc evidence.

### 5. Bổ sung B2 ở chế độ shadow

- Cho backend và AI service nhận B2 trong một nhánh shadow hoặc test-only.
- Chưa hiển thị B2 trên frontend.
- Sinh benchmark B2 theo các mục tiêu: lập luận, tương phản, quan điểm, thái độ, agreement và gist.
- Kiểm tra hai điều riêng: nội dung có đúng B2 không và validator có chấm được mục tiêu đó không.
- Chỉ chuyển B2 sang SUPPORTED khi benchmark đạt ngưỡng đã định.

### 6. Bổ sung C1 ở chế độ shadow

- Thêm task có implicit relation, speaker intention, stance và context interpretation.
- Bổ sung bằng chứng nhiều đoạn và rationale ngắn.
- Đợt shadow hiện tại giữ một người nói và câu hỏi giải được bằng transcript. Hội thoại nhiều người, overlap và tone audio để sau theo giới hạn phạm vi đã nêu.
- Không dùng exact substring validator cho toàn bộ C1.

### 7. Thiết kế C2 như một loại nội dung riêng

- Không mở C2 bằng cách kế thừa profile C1 rồi tăng maxWords.
- Xác định trước task type được phép: implication, tone, allusion, humour, sociocultural meaning.
- Xây rubric riêng để chấm hàm ý và sắc thái.
- Nếu chưa có rubric và dữ liệu benchmark đủ lớn, giữ C2 ở PLANNED.

### 8. Đồng bộ các lớp contract

Khi một mức được phép bật, cập nhật đồng bộ:

- ConversationService.LEVELS và catalog.
- ConversationGenerationPrompt và profile.
- ConversationLocalClient schema.
- ConversationPythonClient payload.
- services/ai-generation/app/schemas.py.
- services/ai-generation/app/validation.py.
- OpenAPI.
- Database CHECK constraint bằng migration mới.
- Frontend level selector và nút easier/harder.
- Test fixtures và regression dataset.

Không sửa migration cũ đã chạy; thêm migration mới.

## Rubric C2 dự thảo — PLANNED

Rubric này phục vụ chuẩn bị dữ liệu review, chưa được hiệu chuẩn bằng benchmark. Không kế thừa schema sinh C1 để bật C2. Danh sách năng lực dựa trên ma trận và nguồn CEFR/Cambridge ở trên; cách chấm, format và ngưỡng dưới đây là thiết kế sản phẩm cần kiểm chứng.

| Mục tiêu | Cần phân biệt | Bằng chứng tối thiểu về ý nghĩa | Trường hợp phải loại |
|---|---|---|---|
| `implication` | Điều người nói hàm ý so với điều nói trực tiếp | Trích dẫn phát ngôn và ngữ cảnh tạo ra hàm ý; giải thích liên kết | Chỉ đổi nhãn cho thông tin đã nói rõ hoặc suy đoán tùy ý |
| `tone` | Sắc thái trong cách dùng từ và lập trường | Các từ/cấu trúc thể hiện mức dè dặt, nhấn mạnh hoặc thay đổi sắc thái | Câu chỉ giải được bằng ngữ điệu; phần này cần rubric audio ở đợt sau |
| `allusion` | Ý nghĩa của lời ám chỉ trong ngữ cảnh | Transcript tự cung cấp đủ ngữ cảnh cho ám chỉ | Cần biết lịch sử, tác phẩm hay sự kiện không được cung cấp |
| `humour` | Tác dụng giao tiếp của tương phản hoặc cách dùng từ | Cả hai cách hiểu và dấu hiệu chọn cách hiểu phù hợp phải có trong transcript | Chỉ hỏi nhận biết một câu đùa, hoặc phụ thuộc giọng đọc |
| `sociocultural_meaning` | Ý nghĩa xã hội trong tình huống cụ thể | Vai trò, quan hệ và quy ước liên quan được thiết lập trong bài | Gán khuôn mẫu văn hóa hoặc yêu cầu kiến thức ngoài bài |
| `fine_grained_stance` | Mức cam kết, phạm vi và thay đổi nhỏ của lập trường | Tích hợp hedging, concession, phạm vi phát biểu và ngữ cảnh | Tóm tắt thành tích cực/tiêu cực làm mất khác biệt cần kiểm tra |

Mỗi mẫu dự thảo phải kèm: mục tiêu, câu trả lời chấp nhận được theo ý nghĩa, các exact quote liên quan, rationale, ít nhất một cách hiểu gần nhưng sai và dấu hiệu loại cách hiểu đó. Không bắt nhiều segment khi một segment đã chứa đủ dấu hiệu; cũng không bỏ qua dấu hiệu phân tán chỉ vì có một câu chứa từ khóa. Chưa chốt số slot hoặc giới hạn segment cho format C2.

Review theo sáu trục: (1) độ phức tạp nội dung/yêu cầu phù hợp, (2) bằng chứng và liên kết, (3) phân biệt cách hiểu gần nhau, (4) không cần kiến thức ngoài bài, (5) chấm giữ đúng sắc thái nhưng nhận paraphrase, (6) giải được bằng transcript trong đợt không audio. Mỗi trục chấm 0 = sai/thiếu, 1 = chưa đủ chắc, 2 = đủ bằng chứng. Mẫu chỉ được đề xuất vào bộ hiệu chuẩn khi mọi trục đạt 2; đây chưa phải ngưỡng release. Bất đồng reviewer phải được lưu và giải quyết trước khi dùng làm chuẩn.

C2 chỉ được rời PLANNED khi chủ dự án chốt format/giới hạn, có tập dữ liệu đại diện từng mục tiêu với đối chứng C1, có review độc lập và thủ công, hiệu chuẩn được độ nhất quán của rubric và cập nhật đồng bộ contract. Phạm vi nghe đầy đủ còn cần thiết kế audio riêng.

## Quy tắc validation sau khi triển khai

### Validation cấu trúc

- Level phải là giá trị được profile cho phép.
- Slot và fact phải cùng số lượng.
- A1 vẫn đúng 2 fact.
- evidenceEn trực tiếp phải là substring chính xác.
- Evidence suy luận phải bao phủ các dấu hiệu dùng để kết luận; một segment có thể chứa nhiều dấu hiệu. Không bắt hai segment chỉ vì cần hai dấu hiệu.
- Không có fact hoặc evidence trùng nghĩa.
- Không có answer leak trong questionVi hoặc taskVi.
- Không có câu hỏi yes/no, ngoài kiến trúc task mới được duyệt riêng.

### Validation theo mức

- A1: direct detail, không inference bắt buộc.
- A2: connected detail, gist/reason/change đơn giản.
- B1: gist, reason, instruction, condition, attitude hoặc một inference/prediction có căn cứ.
- B2: argument, contrast, opinion, attitude, agreement hoặc relation.
- C1: implicit relation, intention, stance, context interpretation.
- C2: implication, tone, allusion, humour hoặc sociocultural meaning.

### Validation continuity

- Lượt 1–3 giữ cùng cuộc gặp và đối tác.
- Lượt 4 là encounter mới trong cùng nhóm tình huống.
- Không hỏi lại đáp án cũ.
- Không lặp task bằng cách chỉ đổi tên, số hoặc từ đồng nghĩa.
- Không dùng audio behavior để quyết định pass/fail trong đợt này.

## Bộ kiểm tra bắt buộc

### Test contract

- Kiểm tra level hợp lệ và level bị từ chối.
- Kiểm tra profile A1/A2/B1 không đổi ngoài phần B1 đã chủ động mở rộng.
- Kiểm tra B1 inference có evidence.
- Kiểm tra B1 attitude/gist không bị ép thành direct fact.
- Kiểm tra B2/C1/C2 chỉ đi qua test/shadow khi chưa được bật production.

### Test generation

- Mỗi mức dùng nhiều tình huống, nhiều turn và nhiều seed.
- Có cả direct detail, gist, reason, change, condition và inference.
- B1 phải có ca suy ra bước tiếp theo và ca nhận diện thái độ/quan điểm được nói rõ.
- B2 phải có ca argument/contrast/opinion.
- C1 phải có ca implicit relation/intention/stance.
- C2 chỉ test khi rubric C2 đã tồn tại.

### Ngưỡng đề xuất trước khi bật mức mới

- 100% output qua structural validation.
- 0 task có evidence không căn cứ.
- 0 task hỏi thêm ý không có trong transcript.
- Ít nhất 95% task nằm trong khoảng số từ đã định cho profile.
- Ít nhất 90% task đạt semantic review độc lập.
- Có review thủ công một mẫu đại diện cho mỗi task type mới.
- Regression test hiện có vẫn pass.

Mẫu 10×3 trước đây có 10/30 lỗi độ dài, trong đó B1 có 6/9 lỗi. Vì vậy B1 phải được sửa và chạy lại trước khi dùng kết quả làm baseline cho B2.

## Rủi ro cần tránh

- Dùng số từ để thay cho độ khó CEFR.
- Gọi một câu có từ vựng khó là B2 dù quan hệ ý vẫn là A2/B1.
- Gọi một bài dài, nhiều câu trực tiếp là C1/C2.
- Dùng exact substring để chấm suy luận hoặc thái độ.
- Cho model tự thêm nguyên nhân, kết luận hoặc hàm ý không có bằng chứng.
- Mở B2/C1/C2 trên UI khi database, Python schema hoặc prompt vẫn chỉ hiểu A1/B1.
- Vô tình sửa luồng audio trong cùng một thay đổi.
- Trộn quy tắc của Conversation AI với quy tắc của thư viện Listening/Dictation khác.

## Tiêu chí hoàn thành kế hoạch

Kế hoạch được xem là hoàn thành khi:

1. B1 có mô tả và validator cho gist, attitude/opinion và inference/prediction có căn cứ.
2. B2 và C1 có profile, task type, evidence model và benchmark ở chế độ shadow.
3. C2 có quyết định rõ ràng: triển khai bằng rubric riêng hoặc giữ PLANNED.
4. Tất cả contract Java, Python, OpenAPI, database và frontend đồng bộ với trạng thái level.
5. Bộ test chứng minh không có regression A1/A2/B1.
6. Audio vẫn chưa bị thay đổi trong diff của giai đoạn này.

## Câu hỏi cần chốt trước khi bật production

- B2 và C1 có được phép dùng transcript text để chấm inference/attitude trong giai đoạn đầu không, hay phải chờ rubric audio?
- C2 có thuộc cùng format Conversation 2–3 slot hay phải là một content type riêng?
- Có muốn giữ khoảng số từ B2/C1/C2 riêng không, hay để benchmark xác định sau khi chốt task type?
