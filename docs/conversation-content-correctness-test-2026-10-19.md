# Kiểm thử tính đúng của nội dung hội thoại local — 19/10/2026

## Kết quả

Đã gọi model thật **12 lần cho 12 ca**, mỗi ca lấy đầu ra đầu tiên, không sửa và không dùng backend validation để lọc kết quả.

- **12/12 đúng cấu trúc output** theo kiểm tra độc lập: trường, kiểu dữ liệu, giới hạn ký tự, skill, số fact và ID.
- Rà thủ công tính đúng: **7 ca sai rõ, 2 ca khớp thông tin, 3 ca cần xem lại**. Không coi 3 ca mơ hồ là đạt hoặc sai chắc chắn.
- Không có timeout hay lỗi thu thập. Mỗi lần sinh mất 20,28–48,81 giây; tổng thời gian gọi 405,89 giây.
- Một ca có bằng chứng hoàn toàn không tồn tại trong bài nghe: `a salad to go`.

Đây là đợt thử mới, không chấm lại con số 8/15 của đợt trước. Các ca độc lập có lịch sử tổng hợp; không phải 12 phiên người học hoàn thành.

## Phạm vi

Code nền `1be02d5`, prompt `CONVERSATION_GENERATION_V2`, model `hf.co/Qwen/Qwen3-4B-GGUF:Q4_K_M` trên Ollama local. Giữ nguyên system prompt, ví dụ, JSON schema đầu ra và sampling của client sản phẩm: temperature 0.7, context 8192, num_predict 1000, think=false; CPU, size_vram=0. Timeout 180 giây/lần.

Input lấy từ `ConversationService.promptContext` với ba tình huống quán, hỏi đường, khách sạn; A1/A2/B1; lượt 2–4; lịch sử phiên trước, 10 phiên gần nhất, practiceFocus và các fact MISSED. Ca phiên tiếp theo nhận chính output quán A1 của đợt thử này trong recentPractice, không yêu cầu output trước phải qua validator.

Không gọi `validateNextPrompt`, không gửi `validationError`/`previousInvalidPrompt`, không sửa output, không gọi reviewer cloud. Client vẫn giữ giới hạn transport, kiểm tra response hoàn tất và parse JSON; không tắt bảo vệ của sản phẩm. Structured output của Ollama vẫn được giữ vì là cơ chế sinh đang sử dụng.

Tiêu chí nội dung: mọi ý hỏi có thông tin trả lời rõ trong bài nghe; mỗi fact khớp ý hỏi, không thiếu/thừa; bằng chứng chứng minh đáp án; bản dịch đúng nghĩa; không có mâu thuẫn làm thay đổi đáp án. Chưa chấm độ hay, đa dạng, tránh lặp hoặc độ khó CEFR. Không dùng độ dài bài nghe để đánh trượt tính đúng nội dung.

## Tổng hợp từng ca

| Ca | Giây | Cấu trúc | Nội dung |
| --- | ---: | --- | --- |
| cafe-a1-followup | 46,75 | Đạt | Sai: hỏi món gì nhưng đáp án là quầy; không nêu món |
| directions-a2-followup | 32,91 | Đạt | Cần xem lại: thông tin đích/phương tiện/lý do khớp nhưng câu hỏi đổi vai người học |
| hotel-b1-followup | 31,62 | Đạt | Sai: hỏi lý do nhưng đáp án là địa điểm |
| hotel-a1-followup | 29,53 | Đạt | Khớp: số phòng và nơi ăn sáng đều đúng; lặp không tính là lỗi ở đợt này |
| cafe-a2-followup | 35,25 | Đạt | Sai: hỏi lý do gọi món trong khi bài nghe giải thích không phục vụ; dịch tự thêm phô mai |
| directions-b1-followup | 33,30 | Đạt | Cần xem lại: câu hỏi có thể đòi việc cần làm để đi xe, bài nghe chỉ đề xuất đi xe |
| hotel-a2-continuity | 35,22 | Đạt | Sai: giờ ăn sáng thành giờ nhận phòng; đáp án thời hạn không trả lời hành động |
| hotel-a2-transfer | 30,79 | Đạt | Sai: thiếu cách nhận phòng; thêm đáp án hỗ trợ hành lý không được hỏi |
| cafe-a1-repeat-session | 25,35 | Đạt | Sai: hỏi món gì nhưng đáp án là quầy; dịch collect thành đặt món |
| cafe-a1-ten-recent-sessions | 48,81 | Đạt | Sai: tự thêm salad và bằng chứng không tồn tại; giờ lấy hàng chưa được nêu rõ |
| directions-a2-practice-focus | 20,28 | Đạt | Khớp: bảo tàng và xe buýt đều có trong bài nghe |
| hotel-b1-missed-transfer | 36,08 | Đạt | Cần xem lại: lẫn nhận phòng/nhận hành lý và đáp án giấy tờ không nêu hành động |

## Bằng chứng cho 7 ca sai rõ

### 1. Quán A1, lượt tiếp theo

- Hỏi: “Bạn muốn gọi gì và dùng mã số nào?”
- Nghe: “Please take your order at the counter. Your order number is thirty-two.”
- Facts: `Quầy` / `the counter`; `Ba mươi hai` / `thirty-two`.
- Sai chắc chắn: quầy là nơi, không phải món. Bài nghe không cung cấp tên món để trả lời ý đầu. Chưa cần kết luận về cách dùng `take your order` để xác định lỗi này.

### 2. Khách sạn B1, lượt tiếp theo

- Hỏi: “Tại sao ăn sáng ở ngoài vườn và bạn cần làm gì khi nhận phòng?”
- Nghe: “The dining room is closed today, so breakfast is in the garden. When you check in, please go straight to the reception and show your ID to the assistant there. This helps us avoid giving the wrong room key to the wrong person.”
- Facts: `Ăn sáng ở ngoài vườn` / `in the garden`; `Đưa giấy tờ tùy thân` / `your ID`.
- Sai chắc chắn: ý đầu hỏi nguyên nhân, fact lại nhắc địa điểm. Nguyên nhân đúng đã có trong bài nghe: phòng ăn đóng cửa. Evidence `your ID` chỉ nêu giấy tờ, không bao trọn hành động đưa giấy tờ.

### 3. Quán A2

- Hỏi: “Vì sao khách gọi món và bạn sẽ phục vụ món nào?”
- Nghe: “We can't serve you a sandwich because you're not a regular customer. Please wait while we check your name in the system.”
- Facts: `Không phải khách quen`; `Kiểm tra tên trong hệ thống`.
- Bản dịch: “Chúng tôi không thể phục vụ bạn bánh mì kẹp phô mai vì bạn không phải khách quen. Xin hãy chờ một chút trong khi chúng tôi kiểm tra tên bạn trong hệ thống.”
- Sai chắc chắn: lý do không phục vụ không phải lý do khách gọi món; hành động kiểm tra tên không phải tên món sẽ phục vụ. `Sandwich` không nói có phô mai nhưng bản dịch tự thêm chi tiết này. Chưa cần đánh giá quy định không phục vụ khách mới có thực tế hay không.

### 4. Khách sạn A2, lượt 3

- Hỏi: “Bạn sẽ nhận phòng lúc nào và phải làm gì trước khi rời đi?”
- Nghe: “Breakfast in the garden starts at seven in the morning. Please return your room key at reception before eleven when you leave. Our staff can help you carry your bags.”
- Facts: `Bảy giờ sáng` / `seven in the morning`; `Trước mười một giờ` / `before eleven`.
- Sai chắc chắn: bảy giờ là giờ ăn sáng, không phải giờ nhận phòng. Ý thứ hai hỏi hành động nhưng fact chỉ ghi thời hạn, thiếu trả chìa khóa. Việc chép lịch sử không được dùng làm lý do đánh trượt.

### 5. Khách sạn A2, lượt 4

- Hỏi: “Bạn sẽ nhận phòng bằng cách nào và cần làm gì trước khi rời phòng?”
- Nghe: “Your room key is in the drawer under your bed. Please return it to reception before you leave. If you need help with your bags, ask the staff for assistance.”
- Facts: `Trả chìa khóa tại quầy`; `Yêu cầu hỗ trợ với túi xách`.
- Sai chắc chắn: không có fact trả lời cách nhận phòng. Trả chìa khóa trả lời ý rời phòng; hỗ trợ hành lý không được hỏi. Không cần dựa vào độ tự nhiên của chỗ cất chìa khóa để kết luận.

### 6. Quán A1, phiên tiếp theo

- Hỏi: “Bạn muốn gọi gì và dùng mã số nào?”
- Nghe: “Please collect your order at the counter. Your order number is thirty-two.”
- Facts: `Quầy`; `Ba mươi hai`.
- Dịch: “Xin đặt món tại quầy. Số đặt món của bạn là ba mươi hai.”
- Sai chắc chắn: không có tên món, fact quầy không trả lời món gì. `Collect your order` là nhận/lấy món đã đặt, không phải đặt món. Lặp phiên trước không dùng làm lý do đánh trượt.

### 7. Quán A1, có 10 phiên gần nhất

- Hỏi: “Khách muốn mang đi món gì và lấy lúc mấy giờ?”
- Nghe: “I will take your order away. The time is twelve.”
- Facts: `Salad mang đi` / `a salad to go`; `Mười hai` / `twelve`.
- Sai chắc chắn: salad không có trong bài nghe; evidence `a salad to go` cũng không tồn tại. “The time is twelve” chỉ nói giờ là mười hai, chưa xác định đó là giờ lấy món. Lỗi đầu đủ để kết luận sai dù chưa xét ý giờ.

## Hai ca khớp thông tin

### Khách sạn A1

- Hỏi: “Phòng số mấy và ăn sáng ở đâu?”
- Nghe: “Hello. Your room is number eight. Breakfast is in the garden. Thank you.”
- Facts: `Phòng tám` / `number eight`; `Ngoài vườn` / `in the garden`.
- Dịch: “Chào bạn. Phòng của bạn là số tám. Bữa sáng được phục vụ tại vườn. Xin cảm ơn.”
- Các ý hỏi đều có đáp án và bằng chứng phù hợp. Đây là bài cũ, nhưng tính đa dạng được để ngoài tiêu chí hiện tại.

### Hỏi đường A2, có practiceFocus

- Hỏi: “Bạn sẽ đi đâu và bằng phương tiện nào?”
- Nghe: “We are going to the museum. Take the bus from here. This is because the road is too busy for walking.”
- Facts: `Bảo tàng` / `to the museum`; `Xe buýt` / `the bus`.
- Dịch: “Chúng tôi đang đi về bảo tàng. Hãy lên xe buýt từ đây. Đây là vì đường quá đông để đi bộ.”
- Đích và phương tiện khớp; bản dịch giữ các thông tin được hỏi. `We` nên được làm rõ theo vai hội thoại; chưa thấy nó làm thay đổi hai đáp án. Không đánh trượt vì bỏ mục tiêu luyện điểm xuống hoặc dùng lại thông tin cũ ở đợt đo tính đúng này; chưa phải xác nhận tuân thủ toàn bộ input.

## Ba ca cần xem lại, không tính là đúng hoặc sai chắc chắn

1. **Hỏi đường A2, lượt tiếp theo.** Hỏi: “Bạn sẽ dẫn người nghe đến đâu và bằng phương tiện gì? Vì sao không thể đi bộ?” Nghe: “We need to go to the museum. Take the bus from here. This is because the road is too busy for walking.” Ba fact là bảo tàng, xe buýt, đường quá đông để đi bộ; dịch giữ đúng các ý này. Thông tin khớp nhưng “bạn sẽ dẫn người nghe” đổi vai so với người học là khách đang hỏi đường. Cần chốt cách hiểu vai trước khi xác nhận bài đúng.
2. **Hỏi đường B1.** Hỏi: “Bạn cần làm gì để đi xe buýt và lý do là gì?” Nghe: “You can take the bus to the museum. This is because the road is too busy for walking.” Fact đầu là “Đi xe buýt đến bảo tàng”; fact thứ hai là đường quá đông. Nếu hỏi hành động được khuyên và lý do thì có đáp án; nếu hỏi việc cần làm trước/để lên xe thì bài nghe chưa nói. Đây là mơ hồ câu hỏi, không dùng độ dài quá ngắn của B1 để đánh trượt.
3. **Khách sạn B1, vận dụng sau MISSED.** Hỏi: “Bạn cần làm gì để nhận phòng và điều kiện nào phải được đáp ứng?” Nghe: “You can check in only after the security check has finished. Although the main desk is still open, please go straight to the collection area and show your receipt to the assistant there. This helps us avoid giving a bag to the wrong person.” Facts: “Kiểm tra an ninh đã hoàn tất” / `the security check has finished`; “Biên nhận” / `your receipt`. Điều kiện được nêu rõ, nhưng phần hướng dẫn/lý do chuyển sang nhận túi; fact giấy tờ chưa nêu hành động đưa giấy. Cần xem lại để phân biệt một quy trình khách sạn hợp lệ với ghép nhầm nghiệp vụ. Không kết luận sai chỉ vì giống ví dụ trong prompt.

## Nhận định

Vấn đề chắc chắn quan sát được không phải timeout hoặc JSON: model ghép câu hỏi và facts sai loại thông tin (món/nơi, lý do/nơi, giờ ăn/giờ nhận phòng, hành động/thời hạn), đôi khi tự thêm chi tiết vào bản dịch hoặc tạo bằng chứng không tồn tại. Cấu trúc đúng chưa đảm bảo bài tập trả lời được và chấm công bằng.

Chưa đủ bằng chứng để quy nguyên nhân riêng cho kích thước model, quantization hay một đoạn prompt cụ thể: đợt này không làm đối chứng model/prompt. Chưa sửa prompt hoặc code sản phẩm. Kết quả thủ công không phải điểm của reviewer AI, chứng nhận CEFR hay tỷ lệ thành công ổn định.

## Chạy lại và bằng chứng

```powershell
mvn --batch-mode --no-transfer-progress '-Dtest=ConversationLocalEvaluationTest#collectFirstOutputsForContentReview' '-Dconversation.evaluate=true' '-Dconversation.raw=true' test
mvn --batch-mode --no-transfer-progress '-Dtest=ConversationLocalEvaluationTest#checkCollectedOutputStructure' '-Dconversation.evaluate=true' '-Dconversation.raw-structure=true' test
```

Lệnh đầu chỉ xác nhận đã thu được đủ output, không khẳng định đúng nội dung. Lệnh thứ hai chỉ kiểm tra cấu trúc của file đã thu. Cả hai đều opt-in; CI thường không tự gọi model. Chạy lại có thể sinh nội dung khác vì sampling; nhận xét thủ công trong báo cáo chỉ áp dụng cho đợt này.

JSON input/output gốc: `target/conversation-local-raw-content-evaluation.json`, được Git bỏ qua, SHA-256 `0df45d8fb851a9c8e3271d4a91bce8b028bee39bc5b926eb143fefc6401cc65d`. Không chứa dữ liệu người học thật.

Hai lệnh thu thập/kiểm tra cấu trúc đã chạy riêng và đạt. Kiểm tra hồi quy cuối `mvn --batch-mode --no-transfer-progress -Pci-unit verify`: 122 test được phát hiện, 115 đạt, 7 opt-in bỏ qua, không lỗi; build thành công. Build xanh không xác nhận nội dung model đúng. UTF-8 đạt cho tệp test và báo cáo, không cần sửa encoding.

Giao bộ test và báo cáo trên nhánh `codex/practice-drafts-audio-20261017`. Workflow push chỉ nhận `main`; chưa xác minh được GitHub Actions qua API vì trả HTTP 404 trong môi trường hiện tại. Trạng thái push/remote commit được xác nhận riêng khi bàn giao, không coi đây là production deployment.

Không thử reviewer cloud, chấm câu trả lời, TTS, trình duyệt hay production. Không bật pilot local và không deploy.
