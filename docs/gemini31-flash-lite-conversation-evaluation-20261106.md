# Kiểm thử Conversation AI — Gemini 3.1 Flash Lite

Ngày: 06/11/2026. Source kiểm thử: `8977628cb4afa842a14e4f8168335f2a9bb17722`.

## 1. Kết luận

**Gemini 3.1 Flash Lite chạy được pipeline hiện tại và ổn định hơn Gemini 3.5 trong lượt thử này. Chưa có bằng chứng đủ để kết luận nội dung tốt hơn hoặc chuyển model mặc định.**

- 3.1 tạo được **6/6 bài**, gồm 18 activity, 36 câu hỏi và 42 segment/bản dịch; 38/38 HTTP model call trả 200, không cần xoay nguồn vì 429.
- 3.5 cùng lượt thử tạo được **0/6 bài hoàn chỉnh**: 6 HTTP 503, 4 HTTP 200; không retry 503 hoặc replay phase đã chạy.
- Một bài riêng đã đi qua API local tới `COMPLETED`: EXTRACT → GENERATE → TTS → trả lời → GRADE → hoàn thành cả ba activity → mở lại. Tuy nhiên, nhánh trả lời sai kèm bỏ trống gặp lỗi GRADE thật, nên **không đánh dấu toàn bộ hành vi đạt**.
- Đọc nội dung phát hiện lỗi bằng chứng và nhãn kỹ năng mà reviewer 3.1 bỏ sót. Bộ 3.5 lịch sử cũng có câu hỏi lặp ý và lỗi dịch.
- Đã khôi phục local về `gemini-3.5-flash-lite`; registry, production, prompt và harness không thay đổi. Hai health endpoint trả 200 sau phục hồi.

## 2. Điều kiện so sánh

Model thử là bản stable **`gemini-3.1-flash-lite`**, metadata Google trả 200, version `3.1-flash-lite-05-2026`. Đã đối chiếu [tài liệu model chính thức](https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-lite).

Giữ nguyên prompt `SCENARIO_PERSONALIZED_FEWSHOT_V9_HARNESS_V2_GRADE_V5`, hash `df832a8cb71a8229b95d02be4acaacd6a56b78ed7a9f747b51c4a222864cc303`; temperature 0; token budget, schema, validator, few-shot, quota và các bước repair có sẵn. Không sửa prompt để giúp model mới vượt bài thử.

Sáu input và `learningContext` lấy nguyên từ corpus happy case 50 bài đã chạy trước đó, không tạo corpus mới:

| Case | Mục đích / mức mục tiêu | Input | 3.1 hiện tại | 3.5 hiện tại |
|---|---|---|---|---|
| 01 | Giao tiếp CEFR A1 | Hỏi thủ thư cách mượn sách nấu ăn | Hoàn tất | 503 tại EXTRACT |
| 02 | Giao tiếp CEFR A2 | Hỏi bạn cùng nhà cách chăm sóc cây | Hoàn tất, 2 call repair bổ sung | 503 tại QUESTIONS sau khi đã sinh hội thoại |
| 19 | TOEIC Listening 200 | Hỏi đồng nghiệp nội dung cuộc họp dự án | Hoàn tất | 503 tại EXTRACT |
| 20 | TOEIC Listening 350 | Hỏi cách đăng ký đào tạo nội bộ | Hoàn tất | 503 tại GENERATE |
| 35 | IELTS Listening 6.5 | Hỏi dịch vụ hỗ trợ sinh viên mới | Hoàn tất | 503 tại GENERATE |
| 36 | IELTS Listening 8 | Hỏi bạn học cách tìm tài liệu cho bài luận | Hoàn tất | 503 tại EXTRACT |

Điểm TOEIC/band IELTS là mục tiêu người dùng khai báo, không phải chứng nhận nội dung đã được hiệu chuẩn theo đề thi.

Do 3.5 hiện tại không hoàn tất, phần nhận xét nội dung dùng **sáu output 3.5 lịch sử ngày 04/11 trên đúng input/context**. Prompt và source harness khớp sau chuẩn hóa xuống dòng; workflow hiện tại thêm logging lỗi. Gateway lịch sử có chính sách retry khác, nên không dùng thời gian lịch sử để kết luận model nào nhanh hơn. Đây không phải A/B đồng thời đầy đủ về nội dung.

## 3. Chất lượng nội dung

Đây là nhận xét do coding agent đọc output, không phải nhãn giáo viên hoặc điểm của một judge đã hiệu chuẩn. `SUCCEEDED` và reviewer `valid=true` chỉ là bằng chứng pipeline chấp nhận, không chứng minh bài hoàn hảo.

| Case | Nhận xét 3.1 | So với 3.5 lịch sử |
|---|---|---|
| 01 | Gọn, đúng vai, trình tự làm thẻ hợp lý; câu hỏi/đáp án rõ. 68 từ, 6 segment. | 3.5 có 131 từ/12 segment, thêm vị trí sách, hạn mượn và gia hạn. Phong phú hơn nhưng không tự động tốt hơn cho A1. |
| 02 | Lời khuyên nối tiếp tự nhiên, đáp án rõ. Bằng chứng câu hỏi ý chính `How do I take care of it?` cần ngữ cảnh trước để biết `it` là cây. | Hai bản đều dùng được; 3.5 có câu hỏi nhắc lại chính đề xuất vị trí gần cửa sổ, giá trị kiểm tra thấp. |
| 19 | Nêu rõ chủ đề, lý do tăng ngân sách và tài liệu cần mang. Không thấy mâu thuẫn lớn. | 3.5 a2 hỏi hai lần về phí phần mềm; câu “Những khoản phí phần mềm nào…” chỉ có đáp án “phí phần mềm được cập nhật”, ít giá trị học. 3.1 tốt hơn ở case này. |
| 20 | Nội dung đúng chủ đề; **nhãn kỹ năng sai hoặc chưa phù hợp**: hỏi việc cần làm được gắn `reason`; thông tin HR nêu trực tiếp lại gắn `supported-inference`. Evidence a2/q1 chỉ có `Yes, that is a required step.`, thiếu câu hỏi trước để xác định bước đó là gì. | 3.5 lịch sử có lý do xin phê duyệt rõ hơn, nội dung phong phú hơn. Không thấy lợi thế nội dung của 3.1 ở case này. |
| 35 | Lịch sự, mạch lạc, dịch nhìn chung giữ nghĩa; lời thoại hơi công thức, ít chi tiết thực tế hơn bản lịch sử. Nhãn suy luận cho đề nghị hướng dẫn đăng ký còn dễ tranh luận. | 3.5 có hướng dẫn cụ thể về lịch tư vấn, đăng ký và thời gian chờ; trải nghiệm nghe giàu thông tin hơn. |
| 36 | Từ vựng học thuật, mạch thảo luận tốt; **trích dẫn không đủ hỗ trợ đáp án** ở a1/q2 và a2/q1. Reviewer vẫn đánh dấu toàn bộ checks đúng. | 3.5 lịch sử cũng còn lỗi dịch: `sorting ... from ...` thành “phân loại ... với ...”, và để nguyên `seminar`. Không có bản thắng rõ ràng. |

### Lỗi bằng chứng rõ nhất — case 36

- a1/q2 trả lời rằng thư viện vừa lọc thông tin nhiễu vừa cung cấp bài đã bình duyệt, nhưng `quoteEn` dừng ở `It filters out the noise`, bỏ phần bình duyệt.
- a2/q1 hỏi khi nào nên ưu tiên tài liệu cũ, đáp án nói về khung lý thuyết, nhưng evidence chỉ có **`It depends on the topic.`**.
- Thông tin đáp án có trong **toàn bộ transcript**. Lỗi nằm ở phạm vi bằng chứng được lưu, không phải model bịa toàn bộ đáp án.
- `REVIEW_QUESTIONS` trả `valid=true`, `groundingSupported=true` cho các câu này; harness không sửa. Validator hiện kiểm tra trích dẫn tồn tại, nhưng điều đó chưa đủ kiểm tra trích dẫn có hỗ trợ trọn đáp án hay không.

### Thời gian và lượng nội dung

- GENERATE của 3.1, gồm questions/review/translate và repair khi có: median **25,7 giây**; EXTRACT + GENERATE: median **29,8 giây**.
- Tổng số từ tiếng Anh sáu bài: **68 / 95 / 68 / 111 / 139 / 323**. Đây là thống kê, không phải gate loại bài theo số từ.
- Các phản hồi HTTP 200 của 3.1 báo tổng **143.790 token** cho sáu pipeline, gồm input/output; không tính là giá tiền và chưa suy ra tiết kiệm chi phí.
- 3.1 có 2 call repair bổ sung; 3.5 lịch sử có 8. Reviewer của model mới vẫn có false acceptance, nên ít repair không chứng minh chất lượng cao hơn.

## 4. Full flow qua API local

Bài bổ sung dùng input chăm sóc cây, **profile B1 theo preference hiện có của tài khoản QA**. Không thay preference để ép thành A2; đây là test tích hợp riêng, không được gộp vào sáu cặp chất lượng phía trên.

Session: `e455d123-d3da-4056-857f-c8a3f91f79b5`.

Mở trên local: [bài đã hoàn thành](http://127.0.0.1:18082/conversation/e455d123-d3da-4056-857f-c8a3f91f79b5).

| Bước | Kết quả thực tế |
|---|---|
| Đăng nhập + CSRF + catalog/preference | Thành công; giữ nguyên cơ chế xác thực và idempotency |
| EXTRACT + xác nhận draft + GENERATE | Thành công; provenance ghi 3.1 cho mọi model call |
| Che đáp án trước khi hoàn thành | API không trả đáp án cho activity chưa hoàn thành |
| TTS cả 3 activity | 6 media thật, WAV PCM16 mono 24 kHz hợp lệ, tổng 35,7 giây |
| Trả lời tiếng Việt + tiếng Anh | Hai câu activity 1 đều `CAPTURED`, bằng chứng là substring của câu người học |
| Trả lời sai + một câu bỏ trống | **FAILED / GENERATION_REJECTED**, xem nguyên nhân bên dưới |
| Gửi câu trả lời đã sửa | Thành công bằng attempt mới, không replay tác vụ thất bại |
| Chấm đúng các activity còn lại | Tổng 6/6 câu trả lời đúng được `CAPTURED` |
| Hoàn thành, mở lại và lịch sử | Session và cả 3 activity `COMPLETED`; GET mở lại giữ nguyên dữ liệu |

Lần đầu script thử đọc `factEn` từ lesson đã chuẩn hóa, trong khi trường này không tồn tại. Dừng ở runner trước khi nộp câu trả lời. Đã tiếp tục **chính session cũ**, dùng evidence tiếng Anh làm đáp án mẫu; không sinh lại EXTRACT/GENERATE. Các lần sửa assertion của runner không được tính là lỗi sản phẩm hoặc bài thử model mới.

### Lỗi GRADE phải xử lý trước khi coi full flow đạt

Operation `b799fb5d-1e47-4a27-b847-40235bf7a3fa` trả:

```json
{"facts":[
  {"questionId":"q1","status":"INCORRECT","evidenceVi":""},
  {"questionId":"q2","status":"MISSED","evidenceVi":""}
]}
```

`validate_grade` tại `services/ai-generation/app/scenario_workflow.py` yêu cầu `CAPTURED`, `PARTIAL`, `INCORRECT` có evidence người học không rỗng. Kiểm tra offline bằng chính payload/output đã lưu tái hiện `missing learner evidence`, không gọi model lại.

Model đã trả output không đạt validator. Đồng thời prompt đang nói để evidence rỗng khi không có câu trả lời liên quan, nên trường hợp một câu sai hoàn toàn dễ bị diễn giải không thống nhất. Cần đồng bộ prompt/few-shot với hợp đồng backend; không bỏ validator để cho output này qua.

### Giới hạn audio

Runtime Core hiện không cấu hình `ttsRecipe` đa giọng cho session mới. Cả sáu tác vụ là legacy `kind=tts`, dùng một giọng Cartesia `sonic-3.6`. Đã kiểm tra audio của cả hai nhân vật, **chưa chứng minh hai giọng khác nhau**. Không đổi cấu hình TTS trong benchmark model này.

Đây là kiểm thử end-to-end qua HTTP API và file audio thật, với người học được script mô phỏng từ đáp án đã lưu. Chưa phải một lượt audit thao tác Chrome, nghe bằng tai hay đánh giá UX mới.

## 5. Tracing và hồ sơ kiểm tra lại

Đã xác minh Langfuse bằng CLI: HTTP 200, **106 observation / 21 root trace** cho hai lượt model so sánh; 38 generation 3.1 và 10 generation 3.5. Giữ nguyên tracing tích hợp, không tạo judge/score giáo viên giả.

Ví dụ [trace case 36 EXTRACT](https://jp.cloud.langfuse.com/project/cmvx6vyks000iad0j2ph4qdbu/traces/680b6ad23c6a8c6b58fdffc3529dc58a).

Evidence ngoài Git tại `data/gemini31-evaluation-20261106/` trong checkout kiểm thử:

- `manifest.json`, `cases.json`: input, prompt hash, source commit, model, cấu hình và chính sách retry.
- `gemini-3.1-flash-lite/`, `gemini-3.5-flash-lite/`: request/schema, raw response, usage, thời gian, HTTP status và outcome từng phase, kể cả phase thất bại.
- `lessons/`, `paired-content.md`: sáu bài 3.1 và sáu bài 3.5 lịch sử để kiểm tra thủ công.
- `public-flow*/`: request registration/outcome, feedback, audio, provenance và biên nhận phục hồi runtime. Giữ nguyên lỗi ban đầu.
- `verification.json`: tái hiện offline lỗi GRADE, loại tác vụ TTS, cấu hình model sau phục hồi và health.
- `langfuse-audit.json`, `langfuse-observations.json`: bằng chứng tracing remote.
- `gemini31-review-20261106.zip`: gói xem lại gồm bài sinh, so sánh, thống kê và sáu audio; không chứa cookie, khóa API hoặc cấu hình bí mật.

## 6. Đề xuất tiếp theo

1. Đồng bộ hợp đồng GRADE cho câu sai hoàn toàn, bỏ trống và PARTIAL; tái dùng output lỗi đã lưu làm regression, sau đó chạy đối chiếu có kiểm soát.
2. Kiểm tra semantic grounding **trên evidence đã chọn**, không chỉ toàn bộ transcript; giữ đúng các ví dụ case 20/36 để đánh giá lại, không sinh corpus mới.
3. Sửa nhãn kỹ năng lệch với câu hỏi; tránh coi câu hỏi thông tin trực tiếp là suy luận chỉ vì profile cần suy luận.
4. Sau các sửa độc lập đó, chạy lại cặp model khi 3.5 ổn định hoặc chọn baseline khả dụng đã được phê duyệt. Chưa dùng 6/6 thành công làm lý do tự đổi production.

Không sửa source ứng dụng hoặc triển khai production trong tác vụ này.
