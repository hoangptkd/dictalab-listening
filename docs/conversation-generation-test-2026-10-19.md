# Kiểm tra tạo câu hội thoại local — 19/10/2026

## Kết luận

**Chưa đủ tin cậy để bật pilot local cho người học.** Đã chạy 15 ca với model thật, tổng cộng 25 lần sinh: 8 ca qua code, 7 ca bị chặn. Cả hai test chất lượng model thật đều thất bại theo tiêu chí hiện có.

Rà nội dung 8 ca qua code thấy 7 ca có lỗi rõ về câu hỏi/đáp án, kiểm tra lại ý cũ hoặc lẫn ngôn ngữ; 1 ca A2 cần rà thêm về mạch hội thoại và độ tự nhiên. Không coi bất kỳ ca nào là đã được chứng nhận CEFR hay reviewer cloud chấp nhận.

Không có timeout ở 25 lần gọi này. Mỗi ca mất 26,03–87,48 giây, đã gồm lần sửa nếu có. Có 10 ca cần sửa; chỉ 3 bản sửa qua code, chưa đồng nghĩa đúng nghĩa. Trong lần chạy này, tăng timeout đã cho phép ca chậm hoàn thành nhưng không giải quyết các lỗi nội dung.

## Phạm vi và cách đọc kết quả

- Code nền: `3a16b8d`; prompt `CONVERSATION_GENERATION_V2`.
- Model thật: `hf.co/Qwen/Qwen3-4B-GGUF:Q4_K_M`, Ollama trên máy HP; `/api/ps` báo context 8192, `size_vram=0`.
- Timeout local 180 giây/lần gọi, tổng luồng tối đa 450 giây; tối đa một lần sửa nội dung, không retry lỗi mạng.
- Dữ liệu tổng hợp, không dùng phiên hoặc câu trả lời của người học thật; không gọi API trả phí.
- Model thật được gọi ở ba tình huống: quán, hỏi đường và khách sạn. Các tình huống còn lại chỉ được kiểm tra câu khởi động có sẵn, không phải đã đo đủ 24 tình huống với LLM.
- “Qua code” chỉ nghĩa là qua `validateNextPrompt`: cấu trúc, grounding, số từ/fact, skill và quy tắc chống lặp hiện tại. Đây không phải điểm chất lượng bài tập.
- Nhận xét nội dung bên dưới là kiểm tra thủ công của Codex; không phải chứng nhận CEFR hay kết quả reviewer cloud.
- Không đổi code sản phẩm, prompt, model hoặc provider; không bật local và không deploy.

## Kiểm tra tự động bảo vệ luồng

74 test Java đạt, 1 test race opt-in không chạy; 37 test JavaScript liên quan hội thoại đạt.

Kiểm tra hồi quy cuối bằng `-Pci-unit verify`: 115 test đạt, 5 test opt-in được bỏ qua và build thành công. Hai test model hội thoại nằm trong nhóm opt-in nhưng đã được chạy riêng ở trên và đều thất bại; build xanh không thay thế cổng chất lượng model. Kiểm tra UTF-8 đạt cho cả ba tệp test/tài liệu thay đổi.

Các ca đã chạy gồm:

- Câu khởi động 24 tình huống × 3 mức, không cần gọi LLM; xoay vòng nhánh và tải lại không bốc lại nội dung.
- Câu trùng nguyên văn, gần giống, bộ đáp án cũ, fact lặp ở lượt 4; vẫn cho phép dùng một chi tiết làm bối cảnh.
- JSON sai/thừa trường, grounding, giới hạn số từ, fact/skill, sao chép few-shot.
- Chỉ sửa một lần; lỗi mạng không tự retry hoặc chuyển sang cloud; thất bại không làm mất phiên.
- Reviewer bị giả lập từ chối/lỗi/sai schema thì không công bố câu chưa được kiểm tra. Không đo chất lượng reviewer thật trong đợt này.
- Deadline local, khóa phiên, timeout giao diện và hủy request khi rời trang.

## Model thật: luồng tuần tự

Giữ bộ smoke test trước đây để đối chiếu: khách sạn, A1/A2/B1, lượt 2–4. Dừng từng mức ngay khi một lượt không hợp lệ; không giả vờ người học có thể đi tiếp sau lỗi.

| Mức/lượt | Qua code | Giây, gồm lần sửa | Kết quả |
| --- | --- | ---: | --- |
| A1/2 | Không | 49,84 | Lặp nguyên câu khởi động cả hai lần |
| A2/2 | Có | 26,55 | Hỏi lý do chọn phòng nhưng đáp án là số phòng; kiểm tra lại thông tin cũ |
| A2/3 | Có | 46,32 | Lần đầu chép lượt trước; bản sửa vẫn hỏi lại nơi ăn sáng, cách hỏi và đáp án chưa khớp rõ |
| A2/4 | Không | 59,40 | Lần đầu chép ví dụ nhận vé; bản sửa có evidence khác hoa/thường so với utterance |
| B1/2 | Có | 37,73 | Hỏi nơi đặt phòng nhưng bài nghe không có thông tin đó; fact thứ hai không trả lời câu hỏi |
| B1/3 | Không | 56,28 | Lặp nguyên bài nghe lượt trước, kể cả sau sửa |

Tổng: 6 lượt thực sự chạy, 10 lần gọi model, 3 lượt qua code. Ba lượt mục tiêu còn lại không chạy vì mức tương ứng đã bị chặn. Test yêu cầu đủ 9 lượt đạt nên **thất bại**, không hạ tiêu chí để làm xanh. Không lần gọi nào hết timeout.

## Model thật: các ca độc lập dùng context của backend

Bộ bổ sung lấy input từ chính `promptContext`, có `sessionBlueprint`, history và evaluation. Các lượt 3–4 dùng lịch sử tổng hợp đã dựng sẵn, không được tính là phiên tuần tự hoàn thành. Ca lịch sử 10 phiên dùng 40 nhiệm vụ khởi động tổng hợp cùng tình huống/mức; không đại diện cho worst-case lịch sử B1 dài.

| Ca | Qua code | Giây, gồm lần sửa | Nhận xét nội dung |
| --- | --- | ---: | --- |
| Quán A1, lượt 2 | Có | 26,03 | Chỉ hỏi cần làm gì nhưng đáp án yêu cầu cả tổng tiền 12 đô la |
| Hỏi đường A2, lượt 2 | Không | 48,53 | Chép câu khởi động; bản sửa có evidence sai hoa/thường |
| Khách sạn B1, lượt 2 | Có | 32,34 | Dịch lẫn chữ Trung Quốc; nối lý do đóng phòng ăn với việc dẫn khách vào phòng không hợp lý; hỏi lại lý do cũ |
| Khách sạn A2, lượt 3 | Có | 61,54 | Có thông tin mới nhưng cần rà: quay lại chuyện nhận phòng, chưa nêu rõ sự thay đổi; tiếng Anh thiếu tự nhiên |
| Khách sạn A2, lượt 4 | Có | 27,56 | Hỏi cách nhận phòng nhưng không có đáp án tương ứng; fact giúp mang hành lý không được hỏi |
| Quán A1, phiên tiếp theo | Không | 38,25 | Có nhiệm vụ AI của phiên trước trong recentPractice; hai lần sinh giống nhau, grounding lỗi và nội dung vẫn gần phiên trước |
| Quán A1, lịch sử 10 phiên | Có | 87,48 | Lần đầu quá ngắn; bản sửa hỏi tên món nhưng không nêu tên món, lặp câu hỏi và giờ quen thuộc trong lịch sử |
| Hỏi đường A2, mục tiêu luyện riêng | Không | 55,32 | Có practiceFocus trong input nhưng hai lần đều chép nguyên bài nghe mở đầu |
| Khách sạn B1, vận dụng sau bỏ sót ý | Không | 69,89 | Hai lần đều chép nguyên bài nghe hành lý trong few-shot, không tạo encounter khách sạn mới |

Tổng: 9/9 ca đã chạy, 15 lần gọi model, 5 ca qua code. Test yêu cầu đủ 9 ca đạt nên **thất bại**, không bỏ ca lỗi hoặc hạ yêu cầu. Không lần gọi nào hết timeout. Context của ca 10 phiên thực sự chứa 10 phiên/40 nhiệm vụ; input JSON khoảng 7.034 ký tự, chưa phải kiểm tra giới hạn tối đa token của lịch sử B1.

## Ví dụ cần chú ý

1. Câu hỏi: “Bạn cần làm gì sau khi gọi món?” Bài nghe: “Please confirm your order. Your total is $12.00. Thank you.” Có hai fact `xác nhận đơn hàng` và `12 đô la`, nhưng chỉ một slot được hỏi. Đáp án phụ có thể khiến chấm thiếu ý một cách bất công.
2. Câu hỏi: “Khách muốn mang đi món gì và lấy lúc mấy giờ?” Bài nghe: “Please take your order away. Your collection time is twelve.” Bài nghe không nói món gì; `Món mang đi` không phải tên món để trả lời slot đó.
3. Bản dịch B1 có chuỗi “biển指示”. Đây là lỗi lẫn ngôn ngữ do model sinh, không phải file nguồn bị lưu sai UTF-8.
4. `evidenceEn="the total is twelve dollars"` không phải substring nguyên văn của `The total is twelve dollars.`. Validator hiện yêu cầu khớp hoa/thường; model vẫn giữ nguyên lỗi sau khi được yêu cầu sửa.

## Những vấn đề cần ưu tiên trước khi bật local

1. Khớp chính xác slot câu hỏi với fact: không thêm giá tiền/lý do/địa điểm mà câu hỏi không hỏi, không hỏi thông tin bài nghe không nói.
2. Cải thiện khả năng làm theo input thật: không sao chép history/few-shot, giữ stage và tạo ý mới; practiceFocus và MISSED hiện chưa được tuân thủ đáng tin cậy.
3. Đo hiệu quả sửa nội dung theo từng lỗi. Trong mẫu này có nhiều lần model trả lại chính output bị từ chối; chỉ thêm thời gian không giúp nó sửa đúng.
4. Giữ reviewer độc lập và đánh giá reviewer thật trên các mẫu sai đã thu được. Không chuyển nhận xét thủ công thành tuyên bố production đã chặn hết.

Đây là các mục đề xuất từ test, chưa triển khai sửa logic/prompt trong đợt này. Không đề xuất nới grounding/chống lặp để tăng tỷ lệ qua code.

## Cách chạy lại

```powershell
mvn --batch-mode --no-transfer-progress '-Dtest=ConversationLocalEvaluationTest#exerciseLevelsContinuityAndTransferWithRealLocalModel' '-Dconversation.evaluate=true' test
mvn --batch-mode --no-transfer-progress '-Dtest=ConversationLocalEvaluationTest#exerciseIndependentCasesWithProductionContext' '-Dconversation.evaluate=true' '-Dconversation.matrix=true' test
```

Có thể đổi timeout bằng `-Dconversation.timeout-seconds=180`, model bằng `-Dconversation.model=...`. Bộ matrix chỉ chạy khi bật cả hai cờ; test thông thường không tự tải model hoặc gọi LLM.

Bằng chứng JSON local, được Git bỏ qua:

- `target/conversation-local-evaluation.json`: toàn bộ input và output từng lần của luồng tuần tự.
- `target/conversation-local-matrix-evaluation.json`: context backend, fixture, output, lỗi validation và thời gian từng lần gọi của matrix; lưu sau mỗi ca.

## Giới hạn

Không chạy reviewer cloud thật, grading/TTS thật, trình duyệt production hoặc đánh giá CEFR độc lập. Đây là mẫu nhỏ với sampling, không phải tỷ lệ thành công ổn định hay benchmark so sánh model. Kiểm tra Java chạy cùng một phần lượt tuần tự nên số giây chỉ mô tả lần chạy này, không phải phép đo hiệu năng có kiểm soát.

Không nới validator để nhận câu sai và không sửa prompt/model trong đợt test. Những ca qua code nhưng sai ngữ nghĩa vẫn cần reviewer độc lập; chưa chứng minh reviewer production sẽ bắt hết. Không đủ bằng chứng để bật pilot local cho người học chỉ dựa vào timeout mới.
