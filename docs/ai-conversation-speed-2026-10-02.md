# Đo tốc độ tạo hội thoại — 02/10/2026

## Kết luận

Đề xuất **gemini-2.5-flash trên ShopAIKey cho bước tạo hội thoại**, có điều kiện kiểm tra thêm chất lượng trước khi triển khai. Trong 3 đầu vào A1/A2/B1, trung vị là **1,747 giây**, so với **21,472 giây** của ChatGPT Web và **28,987 giây** của ShopAIKey claude-sonnet-4-5. Tương ứng nhanh khoảng **12,3 lần** và **16,6 lần** trong phép đo nhỏ này; không phải cam kết tốc độ lâu dài.

Flash trả đúng cấu trúc của ConversationService, tiếng Việt và độ dài câu tiếng Anh ở cả 3 mẫu. Tuy nhiên, mẫu A2 đưa thêm lý do trong fact mặc dù câu hỏi chỉ hỏi phương tiện. Không được xem vượt qua kiểm tra cấu trúc là bảo đảm chất lượng chấm điểm.

Không đổi cấu hình, mã ứng dụng, model đang chạy hay triển khai. Không tạo phiên học trong cơ sở dữ liệu. Công cụ chẩn đoán dùng Node có sẵn, không thêm thư viện; đó là lựa chọn tối giản theo Ponytail.

## Phương pháp

- Chạy từ chính VPS của chương trình, khoảng 02:10–02:19 ngày 02/10/2026 (UTC+7). Dấu thời gian chi tiết bên dưới dùng UTC.
- Model khả dụng được xác nhận bằng GET /v1/models có xác thực; dùng đúng các tên mà ShopAIKey trả về. Tên model trong phản hồi không chứng minh nhà cung cấp thực sự chạy đúng model nội bộ.
- Giữ nguyên PROMPT_INSTRUCTION trong ConversationService, đã đối chiếu nội dung với nguồn Java.
- Ba ngữ cảnh tổng hợp, không chứa thông tin riêng: A1 gọi món ở quán/lượt 1; A2 hỏi đường/lượt 1; B1 đổi lịch hẹn/lượt 4 có lịch sử 3 lượt và một kỹ năng nghe giờ bị PARTIAL.
- ShopAIKey: system prompt + user context JSON, temperature=0, max_tokens=2200, không streaming, giới hạn 60 giây; giữ cách gửi của ShopAiKeyClient.
- ChatGPT Web: một user message có prompt + EVIDENCE_JSON, không streaming, giới hạn 75 giây; giữ cách gửi của ChatGptWebCoachClient. Alias chatgpt-web không xác nhận model thực tế được chọn trên giao diện ChatGPT.
- Thời gian tính từ gửi HTTP đến đọc xong toàn bộ phản hồi. Không đo thời gian token đầu tiên, Cartesia tạo âm thanh, lưu dữ liệu, tải trình duyệt hay tổng thời gian người học phải chờ.
- Các yêu cầu trong mỗi đợt chạy tuần tự. Có một đợt Gemini Flash chạy chồng thời gian với đợt đối chứng; đây không phải phép đo cô lập tải.
- Bảng gồm 15 lượt có thời gian và đầu ra được lưu đầy đủ. Mỗi model có 3 mẫu được thử trên 3 đầu vào khác nhau, không phải 3 lần lặp cùng đầu vào. GPT mini/nano và Haiku chỉ được sàng lọc 1 mẫu.
- Sự cố gửi công cụ qua SSH trước khi model được gọi không được tính vào tốc độ. Công cụ cuối dùng tệp chẩn đoán tạm, được xóa sau mỗi đợt; nguồn công cụ vẫn còn tại target.
- Bộ kiểm tra mô phỏng đúng shape, giới hạn trường, ID fact và evidenceEn là chuỗi con của câu tiếng Anh như ứng dụng. Kiểm tra riêng 20–65 từ theo prompt, vì validatePrompt hiện chưa cưỡng chế giới hạn số từ.
- Nội dung, ngôn ngữ, mối liên hệ câu hỏi/fact và yêu cầu không lặp đáp án được đọc kiểm tra thủ công. Không gán điểm chất lượng tự động.

Hai model GPT được đưa vào sàng lọc sau khi đối chiếu [OpenAI Docs: GPT-4.1 mini](https://developers.openai.com/api/docs/models/gpt-4.1-mini) và [GPT-4.1 nano](https://developers.openai.com/api/docs/models/gpt-4.1-nano). Tài liệu chính thức mô tả chúng có độ trễ thấp, không cần bước reasoning. Khả dụng và độ trễ qua ShopAIKey được xác nhận độc lập bằng API và phép đo trên đây, không suy ra từ tốc độ hay giá của OpenAI.

## Kết quả

Các thời gian trong một hàng theo thứ tự A1 / A2 / B1 nếu có đủ 3 mẫu. Tất cả 15 lượt ghi nhận HTTP 200 và không có content rỗng. “Khớp schema” chỉ có nghĩa vượt bộ kiểm tra của ConversationService, không có nghĩa hoàn toàn đúng mục tiêu sư phạm.

| Nguồn/model | Số mẫu | Thời gian từng mẫu (giây) | Trung vị (giây) | Khớp schema | Đạt 20–65 từ | Nhận xét nội dung |
|---|---:|---|---:|---|---|---|
| gemini-2.5-flash | 3 | 1.782 / 1.747 / 1.412 | 1.747 | 3/3 | 3/3 | Ưu tiên thử tích hợp; mẫu A2 có ý chấm rộng hơn câu hỏi. |
| gemini-2.5-flash-lite | 3 | 2.436 / 2.927 / 2.723 | 2.723 | 3/3 | 3/3 | Không ưu tiên: lượt 4 lặp lại 17:00 thứ Bảy của lịch sử. |
| gpt-4.1-nano | 1 | 2.423 | 2.423 | 1/1 | 0/1 | Mới sàng lọc 1 mẫu: 15 từ; câu hỏi hỏi sở thích người học, không kiểm tra đúng nội dung nghe. |
| gpt-4.1-mini | 1 | 3.309 | 3.309 | 1/1 | 0/1 | Mới sàng lọc 1 mẫu: 15 từ; skill còn nguyên tiếng Anh; hỏi món ăn nhưng chấm thêm đồ uống. |
| claude-haiku-4-5-20251001 | 1 | 3.479 | 3.479 | 1/1 | 0/1 | Mới sàng lọc 1 mẫu: 13 từ; chấm lời chào ngoài câu hỏi; JSON có bọc ngoài. |
| chatgpt-web | 3 | 21.472 / 20.733 / 21.997 | 21.472 | 3/3 | 2/3 | Mẫu A1 chỉ 17 từ; mẫu A2 hỏi hướng được chỉ nhưng người nói chỉ hỏi đường. |
| claude-sonnet-4-5 | 3 | 28.338 / 29.131 / 28.987 | 28.987 | 3/3 | 3/3 | Chậm hơn; mẫu A2 và lượt 4 hợp lý; JSON có bọc ngoài được ShopAiKeyClient xử lý. |

## Các lỗi nội dung cần chú ý

1. **Flash Lite ở lượt 4** trả lại five o'clock on Saturday afternoon, trong khi lịch sử đã có five on Saturday. Mẫu này vượt schema nhưng vi phạm yêu cầu chuyển tiếp bằng chi tiết mới. Không ưu tiên Flash Lite dù nhanh.
2. **Flash A2** hỏi vị trí và phương tiện, nhưng fact f2 là “Nên đi xe buýt vì đi bộ hơi xa”. Phần lý do không được hỏi; nếu chấm toàn bộ fact này, người học có thể bị đánh thiếu ý không công bằng. Giữ fact chỉ về phương tiện hoặc hỏi thêm lý do khi tích hợp.
3. **ChatGPT Web A2** hỏi người nói “được bảo nên đi theo hướng nào”, nhưng đoạn tiếng Anh chỉ có người đang hỏi đường; không có câu trả lời chỉ hướng. JSON hợp lệ vẫn có thể tạo câu hỏi không có đáp án.
4. **GPT mini/nano, Haiku A1** tạo câu 13–15 từ. GPT mini còn trả skill = short Vietnamese listening skill. Haiku có một fact về lời chào không thuộc câu hỏi “Nhân viên quán hỏi gì?”. Không chọn chỉ dựa trên tốc độ.

## Hướng dùng trong chương trình

Luồng ConversationService hiện gọi **ChatGptWebCoachClient**, không phải ShopAiKeyClient. Chỉ đổi SHOPAIKEY_MODEL chưa chuyển được hội thoại sang API.

Nếu chọn tích hợp Flash, giữ nguyên dạng một lời thoại tiếng Anh, câu hỏi tiếng Việt, bản dịch, skill và 2–3 facts; giữ lịch sử cho lượt tiếp theo và lượt 4. Dùng cơ chế kiểm tra cấu trúc hiện có, đồng thời kiểm tra độ dài, chống lặp đáp án và bảo đảm chỉ chấm ý được hỏi. Cần thử riêng bước chấm và phản hồi + nextPrompt trước khi chuyển toàn bộ phiên sang API; phép đo này chỉ kiểm tra tạo hội thoại.

Không kết luận chi phí ShopAIKey: chưa xác minh giá tài khoản, số dư hay số tiền từng yêu cầu. Không dùng thời gian của bài kiểm tra MCQ trước đó để so ngang với hội thoại ngắn này.

## Mẫu hội thoại Flash thực tế

Đây là nguyên văn đầu ra, chưa chỉnh sửa để che lỗi. Mẫu B1 là ví dụ thích hợp nhất để minh họa câu mới với ngày giờ khác lịch sử.

### Mẫu A1, lượt 1

```json
{
  "taskVi": "Nghe hiểu yêu cầu về loại đồ uống của nhân viên phục vụ",
  "questionVi": "Nhân viên muốn hỏi bạn muốn uống loại nước nào và cỡ nào?",
  "utteranceEn": "Hello! Welcome to our cafe. What kind of coffee would you like to order today, and would you prefer a regular size or a large one?",
  "translationVi": "Xin chào! Chào mừng bạn đến quán cà phê của chúng tôi. Hôm nay bạn muốn gọi loại cà phê nào, và bạn thích cỡ vừa hay cỡ lớn?",
  "skill": "Nghe hiểu yêu cầu gọi đồ uống cơ bản",
  "facts": [
    {
      "id": "f1",
      "textVi": "loại cà phê muốn gọi",
      "evidenceEn": "What kind of coffee would you like to order today"
    },
    {
      "id": "f2",
      "textVi": "cỡ đồ uống",
      "evidenceEn": "regular size or a large one"
    }
  ]
}
```

### Mẫu A2, lượt 1

```json
{
  "taskVi": "Nghe đoạn hội thoại ngắn để xác định vị trí của hiệu thuốc và phương tiện di chuyển phù hợp.",
  "questionVi": "Hiệu thuốc nằm ở đâu và người nói gợi ý đi bằng phương tiện gì?",
  "utteranceEn": "To get to the pharmacy, go straight down this street for two blocks, then turn left at the traffic lights. Since it is a bit far to walk, you should take the bus from the corner.",
  "translationVi": "Để đến hiệu thuốc, bạn hãy đi thẳng con phố này hai dãy nhà, sau đó rẽ trái ở đèn giao thông. Vì đi bộ hơi xa, bạn nên đi xe buýt ở góc phố.",
  "skill": "Xác định địa điểm và phương tiện giao thông",
  "facts": [
    {
      "id": "f1",
      "textVi": "Hiệu thuốc ở sau hai dãy nhà và rẽ trái ở đèn giao thông",
      "evidenceEn": "go straight down this street for two blocks, then turn left at the traffic lights"
    },
    {
      "id": "f2",
      "textVi": "Nên đi xe buýt vì đi bộ hơi xa",
      "evidenceEn": "you should take the bus from the corner"
    }
  ]
}
```

### Mẫu B1, lượt 4

```json
{
  "taskVi": "Nghe và xác định thời gian mới của cuộc hẹn",
  "questionVi": "Bạn nghe được cuộc hẹn mới chuyển sang ngày giờ nào?",
  "utteranceEn": "Let's make it Sunday afternoon at four o'clock instead. We can still meet at the cafe near the station and talk about our upcoming project.",
  "translationVi": "Hãy chuyển sang chiều chủ nhật lúc bốn giờ nhé. Chúng ta vẫn có thể gặp nhau ở quán cà phê gần ga và trò chuyện về dự án sắp tới.",
  "skill": "Nghe thời gian chính xác trong lịch hẹn mới",
  "facts": [
    {
      "id": "f1",
      "textVi": "chiều chủ nhật",
      "evidenceEn": "Sunday afternoon"
    },
    {
      "id": "f2",
      "textVi": "lúc bốn giờ",
      "evidenceEn": "at four o'clock"
    }
  ]
}
```

## Chạy lại và kiểm tra

Công cụ chẩn đoán nằm ở target/conversation-model-speed.cjs và target/run-conversation-benchmark.ps1 (target được bỏ qua trong Git). API key chỉ được đọc từ môi trường dịch vụ trên VPS vào bộ nhớ, không lưu vào mã hay báo cáo.

```powershell
node target/conversation-model-speed.cjs --self-test
python C:/Users/HP/.codex/skills/vietnamese-encoding-guard/scripts/check_encoding.py target/conversation-model-speed.cjs target/run-conversation-benchmark.ps1 docs/ai-conversation-speed-2026-10-02.md
& target/run-conversation-benchmark.ps1 -BenchmarkArgs @('--model=gemini-2.5-flash','--all-cases')
```

Lệnh cuối có phát sinh yêu cầu inference thực tế và có thể tính phí. Self-test bộ kiểm tra và kiểm tra UTF-8 của hai công cụ đã đạt; báo cáo cũng được kiểm tra UTF-8 trước bàn giao.

## Dữ liệu đo rút gọn

```json
[
  {
    "source": "ShopAIKey",
    "requested_model": "gpt-4.1-mini",
    "returned_model": "gpt-4.1-mini",
    "case_index": 0,
    "at": "2026-10-01T19:15:32.237Z",
    "http_status": 200,
    "elapsed_ms": 3309,
    "words": 15,
    "strict_json": true,
    "validation_errors": [],
    "finish_reason": "stop"
  },
  {
    "source": "ShopAIKey",
    "requested_model": "gpt-4.1-nano",
    "returned_model": "gpt-4.1-nano",
    "case_index": 0,
    "at": "2026-10-01T19:15:35.551Z",
    "http_status": 200,
    "elapsed_ms": 2423,
    "words": 15,
    "strict_json": true,
    "validation_errors": [],
    "finish_reason": "stop"
  },
  {
    "source": "ShopAIKey",
    "requested_model": "claude-haiku-4-5-20251001",
    "returned_model": "claude-haiku-4-5-20251001",
    "case_index": 0,
    "at": "2026-10-01T19:15:37.976Z",
    "http_status": 200,
    "elapsed_ms": 3479,
    "words": 13,
    "strict_json": false,
    "validation_errors": [],
    "finish_reason": "stop"
  },
  {
    "source": "ShopAIKey",
    "requested_model": "gemini-2.5-flash-lite",
    "returned_model": "gemini-2.5-flash-lite",
    "case_index": 0,
    "at": "2026-10-01T19:15:41.459Z",
    "http_status": 200,
    "elapsed_ms": 2436,
    "words": 30,
    "strict_json": true,
    "validation_errors": [],
    "finish_reason": "stop"
  },
  {
    "source": "ShopAIKey",
    "requested_model": "claude-sonnet-4-5",
    "returned_model": "claude-sonnet-4-5",
    "case_index": 0,
    "at": "2026-10-01T19:15:43.895Z",
    "http_status": 200,
    "elapsed_ms": 28338,
    "words": 28,
    "strict_json": false,
    "validation_errors": [],
    "finish_reason": "stop"
  },
  {
    "source": "ChatGPT-Web",
    "requested_model": "chatgpt-web",
    "returned_model": "chatgpt-web",
    "case_index": 0,
    "at": "2026-10-01T19:10:09.533Z",
    "http_status": 200,
    "elapsed_ms": 21472,
    "words": 17,
    "strict_json": true,
    "validation_errors": [],
    "finish_reason": "stop"
  },
  {
    "source": "ShopAIKey",
    "requested_model": "gemini-2.5-flash",
    "returned_model": "gemini-2.5-flash",
    "case_index": 0,
    "at": "2026-10-01T19:17:26.015Z",
    "http_status": 200,
    "elapsed_ms": 1782,
    "words": 26,
    "strict_json": true,
    "validation_errors": [],
    "finish_reason": "stop"
  },
  {
    "source": "ShopAIKey",
    "requested_model": "gemini-2.5-flash",
    "returned_model": "gemini-2.5-flash",
    "case_index": 1,
    "at": "2026-10-01T19:17:27.801Z",
    "http_status": 200,
    "elapsed_ms": 1747,
    "words": 36,
    "strict_json": true,
    "validation_errors": [],
    "finish_reason": "stop"
  },
  {
    "source": "ShopAIKey",
    "requested_model": "gemini-2.5-flash",
    "returned_model": "gemini-2.5-flash",
    "case_index": 2,
    "at": "2026-10-01T19:17:29.549Z",
    "http_status": 200,
    "elapsed_ms": 1412,
    "words": 25,
    "strict_json": true,
    "validation_errors": [],
    "finish_reason": "stop"
  },
  {
    "source": "ChatGPT-Web",
    "requested_model": "chatgpt-web",
    "returned_model": "chatgpt-web",
    "case_index": 1,
    "at": "2026-10-01T19:17:05.152Z",
    "http_status": 200,
    "elapsed_ms": 20733,
    "words": 33,
    "strict_json": true,
    "validation_errors": [],
    "finish_reason": "stop"
  },
  {
    "source": "ShopAIKey",
    "requested_model": "gemini-2.5-flash-lite",
    "returned_model": "gemini-2.5-flash-lite",
    "case_index": 1,
    "at": "2026-10-01T19:17:25.890Z",
    "http_status": 200,
    "elapsed_ms": 2927,
    "words": 33,
    "strict_json": true,
    "validation_errors": [],
    "finish_reason": "stop"
  },
  {
    "source": "ShopAIKey",
    "requested_model": "claude-sonnet-4-5",
    "returned_model": "claude-sonnet-4-5",
    "case_index": 1,
    "at": "2026-10-01T19:17:28.818Z",
    "http_status": 200,
    "elapsed_ms": 29131,
    "words": 34,
    "strict_json": false,
    "validation_errors": [],
    "finish_reason": "stop"
  },
  {
    "source": "ChatGPT-Web",
    "requested_model": "chatgpt-web",
    "returned_model": "chatgpt-web",
    "case_index": 2,
    "at": "2026-10-01T19:18:30.859Z",
    "http_status": 200,
    "elapsed_ms": 21997,
    "words": 34,
    "strict_json": true,
    "validation_errors": [],
    "finish_reason": "stop"
  },
  {
    "source": "ShopAIKey",
    "requested_model": "gemini-2.5-flash-lite",
    "returned_model": "gemini-2.5-flash-lite",
    "case_index": 2,
    "at": "2026-10-01T19:18:52.860Z",
    "http_status": 200,
    "elapsed_ms": 2723,
    "words": 30,
    "strict_json": true,
    "validation_errors": [],
    "finish_reason": "stop"
  },
  {
    "source": "ShopAIKey",
    "requested_model": "claude-sonnet-4-5",
    "returned_model": "claude-sonnet-4-5",
    "case_index": 2,
    "at": "2026-10-01T19:18:55.584Z",
    "http_status": 200,
    "elapsed_ms": 28987,
    "words": 37,
    "strict_json": false,
    "validation_errors": [],
    "finish_reason": "stop"
  }
]
```

