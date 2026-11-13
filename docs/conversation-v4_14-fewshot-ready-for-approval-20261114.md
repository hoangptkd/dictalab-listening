# Few-shot Conversation AI — V4.14 đã kiểm thử, chưa kích hoạt

Ngày: 14/11/2026. Người dùng đã xác nhận chạy test. Code checks đạt; đối chiếu AI thật chưa chứng minh lợi ích và còn lỗi reviewer. Giữ local V4.13, không kích hoạt V4.14. Xem `conversation-v4_14-fewshot-evaluation-20261114.md` để đọc kết quả và giới hạn phép đo.

## Phát hiện từ V4.13

V4.13 gửi system prompt trực tiếp từ `selected.INSTRUCTIONS[purpose]`. EXTRACT, GENERATE, TRANSLATE và REVIEW đã kế thừa sáu ví dụ từ bộ personalized. QUESTIONS, REVIEW_QUESTIONS và GRADE được viết lại theo hợp đồng meaning/requiredParts nên không còn các cặp input/output few-shot. Hai câu minh họa bằng văn xuôi trong GRADE không thay thế được ví dụ đầu ra theo schema. Hai bước repair cũng chưa có ví dụ.

Không thể lấy nguyên ví dụ QUESTIONS/REVIEW_QUESTIONS cũ: chúng dùng hai câu hỏi mỗi activity, chưa có meaning/requiredParts và khác projection của reviewer hiện tại.

## Bộ ví dụ đã chuẩn bị

V4.14 giữ nguyên chỉ dẫn, schema, validation, chính sách chấm và workflow V4.13; chỉ bổ sung demonstrations cùng metadata để theo dõi. Tổng cộng **25 ví dụ: giữ sáu, thêm 19**.

| Bước | Số ví dụ | Nội dung chính |
| --- | ---: | --- |
| EXTRACT | 2 | Listener/participant; giả định và dữ kiện; TOEIC không tự đổi vai người học thành nhân viên công ty. |
| GENERATE | 2 | Yêu cầu dịch vụ chưa được xác nhận; thảo luận quan điểm kết thúc tự nhiên, không cần đạt thỏa thuận. |
| QUESTIONS | 3 | Chỉ hỏi địa điểm dù câu hỏi đã cho lý do; hỏi cả giờ và nơi; suy luận từ tiền đề nghe được khi profile cho phép. |
| REVIEW_QUESTIONS | 7 | Hai ca hợp lệ; năm ca lỗi: đáp án sai nguồn, mục tiêu chứa thông tin đã cho, đề xuất bị coi là đồng ý, câu hỏi trùng ý, parts thiếu một ý được hỏi. |
| TRANSLATE | 2 | Giữ điều kiện, khả năng và số đoạn; không biến kết quả gợi ý thành bằng chứng chắc chắn. |
| REVIEW | 2 | Một bài phù hợp và một bài sai vai/sai trạng thái hoàn thành; đủ năm rubric. |
| GRADE | 5 | Đúng nơi/sai giờ; chỉ chấm nơi; đáp án chưa chọn; recap thiếu ý; trả lời một phần và để trống. Bao gồm Việt, Anh và kết hợp. |
| REPAIR_QUESTIONS | 1 | Sửa đúng đáp án của câu hỏi được phép, không sửa meaning hay transcript ngoài phạm vi. |
| REPAIR_TRANSLATIONS | 1 | Sửa mức chắc chắn đúng segment, giữ các phần khác. |

Các nhãn do agent biên soạn, được ghi rõ `agent-authored-reference-not-human-gold`; chưa phải dữ liệu chuẩn do người dùng duyệt hoặc nhãn đã đo độ chính xác.

### Cách hạn chế độ dài và ảnh hưởng lệch

- Mỗi bước chỉ nhận ví dụ của chính bước đó; không đưa cả 25 ví dụ vào mọi lời gọi.
- Sáu ví dụ đang có được tái sử dụng, không nối thêm lần nữa.
- Ví dụ mới cho QUESTIONS/reviewer/translation thường chỉ trình bày item liên quan. Có nhãn `scope` nói rõ đây là trích đoạn, không phải output đủ số lượng của cả bài. Runtime vẫn phải trả đủ mọi item theo schema.
- GRADE có input đúng projection thực tế: targets, câu hỏi liên quan và câu trả lời người học. Không đưa transcript/đáp án tham chiếu vào ví dụ GRADE.
- Giữ phản ví dụ từ chối và ví dụ được chấp nhận để tránh dạy reviewer mặc định một loại kết quả. Hai lợi ích chung chủ thể hoặc một câu hỏi nhiều ý không tự bị coi là lỗi.
- Không bổ sung dữ liệu vào tập benchmark. Khi test phải dùng đầu vào khác các tình huống few-shot; không coi chạy lại chính ví dụ là bằng chứng tổng quát hóa.

## Tích hợp và truy vết

- Candidate `SCENARIO_MEANING_ASSESSMENT_V4_14`, dùng cơ chế pin/approved hash hiện có; chưa thay cấu hình local hay production.
- Prompt hash sau sửa fixture recap trong lần kiểm chứng đã duyệt: `66f97b88e087e528f71b6e1f7bcec1224361b2edea94623ce93e4b5c1cf1189b`.
- Example-set hash: `bbe6e79430ae52346ff009b24aea445f9e48adb6fce31004e0b5612fcbf47a88`.
- V4.13 giữ hash `b1f627bce2367904664a68ca2460e5ffd96868e1eb7fa93b244762673d6d3087` và nội dung cũ; không sửa bài đã lưu.
- Hash candidate bao gồm chỉ dẫn đã ghép ví dụ, version/hash/IDs của bộ ví dụ. Khi thay ví dụ sẽ đổi pin, không lặng lẽ thay nội dung dưới hash cũ.
- Trace của candidate có version/hash và ID ví dụ theo phase; GENERATE liệt kê cả hai bước repair có thể dùng. `calls` ghi ID ví dụ của từng lời gọi đã thực hiện để phân biệt bước thực chạy với bước chỉ được cấu hình.
- Giữ 5 lượt AI cho sinh bài bình thường và ngân sách 9 lượt có sửa/retry. Không thêm audit, retrieval, chọn ví dụ bằng AI, dependency hoặc cấu hình mới.
- Fallback, nhiệt độ, output-token budget, TTS và tính điểm không đổi. Few-shot là ngữ cảnh lúc gọi model, không phải SFT hoặc cập nhật trọng số.

Few-shot làm tăng đầu vào. Tổng system prompt của chín bước là 72.552 byte UTF-8, nhưng được gửi tách theo bước. Năm bước sinh bài bình thường dùng tổng 53.840 byte system prompt, so với 28.081 byte ở V4.13; chưa gồm input thực tế/schema. GRADE tăng từ 2.048 lên 7.408 byte. Đây là dung lượng văn bản; số token thực tế được ghi riêng trong báo cáo kiểm thử.

## Phần đã kiểm tra và phần chưa chạy

Đã đọc code nối prompt, schema, projection, code tổng hợp điểm và nhánh harness; đã đối chiếu thủ công ví dụ với chúng. Kiểm tra tĩnh Python syntax, JSON load, tính hash, UTF-8 và diff whitespace đạt. Các bước này không phải kết quả unit/integration test hoặc benchmark AI.

Đã chạy **244 tests Python và 129 tests Java**, đều đạt. Trong lần chạy đầu, sửa ví dụ recap thiếu mục tiêu backend bắt buộc và tên bước repair trong test. Các kiểm tra gồm:

- Kiểm tra đủ ID/stage, giới hạn dung lượng, hash lịch sử, ví dụ chỉ xuất hiện đúng bước.
- Validate output theo schema tương ứng với `scope`; chứng minh trích đoạn không được chấp nhận làm toàn bộ output QUESTIONS/REVIEW_QUESTIONS.
- Kiểm tra bằng chứng, quan hệ meaning/part/question, nhãn review, exact quote người học và các trạng thái chấm.
- Kiểm tra message thực của EXTRACT, năm bước GENERATE, GRADE và hai bước repair bằng provider giả lập.
- Kiểm tra thông tin few-shot trong tracing, không sửa metadata dùng tính fingerprint lịch sử.
- Backend: pin chính xác, local-only approval, ngân sách 5/9, bài mới/hoàn thành/chấm lại theo pin đã lưu.

## Quy trình test đã thực hiện sau khi xác nhận

1. Chạy tests code liên quan; sửa lỗi nếu có. Không suy ra chất lượng AI từ provider giả lập.
2. Chạy A/B **V4.13 với V4.14** trên cùng corpus độc lập đã có, cùng model/config/input. V4.13 đã có few-shot ở bốn bước, nên đây không phải so sánh zero-shot với few-shot hoàn toàn.
3. Với QUESTIONS/reviewer/GRADE, dùng cùng transcript hoặc targets/câu trả lời đã cố định để phân biệt tác động của few-shot với sự khác nhau do sinh hội thoại mới. Tách kiểm tra này khỏi chạy toàn pipeline.
4. Lưu input, raw output, prompt/example hashes, model thực tế, fallback/retry, tokens, thời gian và verdict. Chỉ so chất lượng theo cặp cùng model thực tế; lượt fallback khác model được báo riêng, không gộp thành thắng/thua do prompt.
5. Đọc thủ công các ca reviewer bỏ sót/đánh sai và GRADE chấm quá hoặc thiếu. Đếm riêng chất lượng nguồn, đúng phạm vi, độ đầy đủ đáp án và chính xác PARTIAL; không dùng tỷ lệ JSON hợp lệ làm chỉ số chất lượng nội dung.
6. Sau kết quả và quyết định của người dùng mới kích hoạt. Kết quả hiện tại không đủ để thay bản đang chạy. Giao code candidate và báo cáo trên nhánh task sau checks; không thay cấu hình local/production.

Tham khảo: [Langfuse về version và quy trình kiểm chứng trước triển khai](https://langfuse.com/docs/prompt-and-skill-management/concepts), [Gemini về thiết kế prompt và ví dụ](https://ai.google.dev/gemini-api/docs/prompting-strategies). Áp dụng vào versioning và cách đóng gói ví dụ; không đổi dịch vụ Langfuse hoặc SDK.

## File cần xem

- `services/ai-generation/app/scenario_assessment_fewshot_v1.json`: 19 ví dụ mới.
- `services/ai-generation/app/scenario_prompts_meaning_v4_14.py`: ghép ví dụ đúng bước và tái sử dụng sáu ví dụ cũ.
- `services/ai-generation/tests/test_scenario_assessment_fewshot.py`: các kiểm tra candidate đã chạy sau khi được duyệt.
