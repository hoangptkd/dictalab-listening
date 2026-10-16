# Tốc độ tạo câu hỏi: ChatGPT Web và ShopAIKey

Ngày đo: **02/10/2026, khoảng 01:54–01:59, Asia/Bangkok**.
Đo trực tiếp từ VPS đang chạy DictaLab; không restart/deploy hay đổi cấu hình ứng dụng.

## Kết quả ba lượt so sánh chính

| Nguồn đang cấu hình | Lượt 1 | Lượt 2 | Lượt 3 | JSON qua kiểm tra cấu trúc |
|---|---|---|---|---|
| ChatGPT Web qua bridge | 41,181 s; 6 câu | 27,348 s; 7 câu | 27,133 s; 7 câu | 3/3 |
| ShopAIKey, `claude-sonnet-4-5` | 38,623 s; 7 câu | 45,187 s; nội dung rỗng | 7,836 s; nội dung rỗng | 1/3 |

ChatGPT Web: median **27,348 s**, trung bình **31,887 s** cho ba bộ câu hỏi hợp lệ.
ShopAIKey chỉ có một bộ câu hỏi thành công trong ba lượt chính; không lấy latency của phản hồi
rỗng để tính tốc độ tạo câu hỏi thành công. Cả sáu request trả HTTP 200; HTTP 200 không đồng nghĩa
với đầu ra sử dụng được.

Một lượt **chẩn đoán bổ sung**, không gộp vào bảng ba lượt so sánh: ShopAIKey trả JSON hợp lệ
trong **17,925 s**, với 8 facts và 7 câu hỏi; `finish_reason=stop`, message có `role/content`,
không có tool calls. Tính cả lượt này, ShopAIKey có **2/4** phản hồi qua kiểm tra cấu trúc.
Hai phản hồi rỗng trước đó không được lưu đầy đủ envelope, nên chưa xác định được nguyên nhân
bên trong gateway/model; không kết luận chúng là timeout hoặc hết token.

## Chất lượng đầu ra liên quan đến phép đo

- Cả ba bộ của ChatGPT Web có câu hỏi, options và explanation bằng tiếng Việt, đúng yêu cầu prompt.
- Cả hai bộ JSON thành công của ShopAIKey có câu hỏi/options/explanation bằng **tiếng Anh**,
  dù prompt yêu cầu tiếng Việt. Ví dụ câu hỏi: “Why can't Emma and Jack take the morning train?”
- Kiểm tra cấu trúc hiện tại của ứng dụng không kiểm tra ngôn ngữ. Do đó JSON được validator
  chấp nhận chưa đồng nghĩa đáp ứng đầy đủ yêu cầu giao diện tiếng Việt.
- Đã đọc các bộ câu hỏi thử để kiểm tra ngôn ngữ và đối chiếu nội dung cơ bản; không thực hiện
  đánh giá sư phạm toàn diện hay chấm điểm chất lượng bằng tập dữ liệu độc lập.

## Cách đo và giới hạn

1. Dùng cùng transcript giả lập: 10 câu có số thứ tự, 163 từ/840 ký tự, về hai người lên kế hoạch
   đi bảo tàng. Không gửi dữ liệu người dùng, API key hoặc thông tin cá nhân trong prompt.
2. Dùng prompt tạo assessment trong `ListeningComprehensionService.createUnit`: 4–12 facts,
   5–8 câu hỏi, các loại MAIN_IDEA/DETAIL/INFERENCE/SPEAKER_INTENT; options, lời giải tiếng Việt.
3. Luân phiên Web/API ba lần, tuần tự, không stream và không retry tự động.
4. ShopAIKey giữ payload của client hiện tại: system + user messages, `temperature=0`,
   `max_tokens=2200`, timeout 60 s. Web dùng một user message chứa instruction và EVIDENCE_JSON,
   model alias `chatgpt-web`, timeout 75 s, không giới hạn token mà bridge không hỗ trợ.
5. Timer nằm trên VPS, từ trước HTTP request tới khi nhận toàn bộ response; không tính SSH,
   giao diện người dùng hoặc thời gian lưu database. Web bao gồm thao tác Chrome và chờ bridge.
6. Parser mô phỏng client đang có: Web yêu cầu JSON nghiêm ngặt và giới hạn độ dài;
   ShopAIKey bỏ markdown fence/đoạn ngoài object như client. Validator kiểm tra số facts/câu hỏi,
   ID, loại câu, options không trùng, đáp án và cân bằng tối thiểu một MAIN_IDEA/hai DETAIL.
   Validator có self-test đã đạt.

Đây là so sánh **hai cấu hình đang dùng**, không phải cùng một model trên hai kênh.
Bridge trả model alias từ request; chưa xác minh model thực được chọn trong giao diện ChatGPT.
ShopAIKey cấu hình và response đều ghi `claude-sonnet-4-5`; chưa xác minh độc lập model phía upstream.
Không đo TTFT, throughput, nhiều user đồng thời, cold start được kiểm soát hay chi phí.
Ba mẫu mỗi nguồn là kiểm tra nhanh, không đủ để kết luận p95/SLA hoặc độ tin cậy dài hạn.
Số câu hỏi và độ dài đầu ra biến động theo prompt, nên latency không thuần là tốc độ truyền HTTP.

## Đối chiếu với ứng dụng

Luồng tạo bộ câu hỏi Listening trong repository hiện gọi **ShopAIKey**, không gọi ChatGPT Web.
ChatGPT Web hiện phục vụ Coach/hội thoại. Phép đo Web là thử khả năng tạo cùng bộ câu hỏi qua
adapter hiện có, không phải xác nhận giao diện admin đã chuyển sang Web.

Kết luận: trong đợt thử này Web trả kết quả đều hơn và đúng ngôn ngữ; API có một lượt thành công
nhanh hơn Web (17,925 s) nhưng hai lượt rỗng và hai bộ tiếng Anh. **Chưa có bằng chứng API hiện tại
nhanh hơn một cách ổn định cho đầu ra đáp ứng yêu cầu**. Cần xử lý/kiểm tra phản hồi rỗng và ngôn ngữ
trước khi lấy tốc độ làm tiêu chí đổi nguồn. Đợt này chỉ chẩn đoán, không áp dụng sửa backend/prompt.

Harness tạm: `target/ai-question-speed-check.cjs` (Git ignore, mất sau `clean`). Lệnh self-test:
`node target/ai-question-speed-check.cjs --self-test`. Phép đo thực đọc cấu hình service bằng NSSM
và dùng Node trên VPS; không ghi API key vào file hoặc output. Có ba request thử vào ChatGPT Web
(tạo hội thoại thử theo hành vi bridge) và bốn request vào ShopAIKey; không tạo/sửa bài học trong DB.
