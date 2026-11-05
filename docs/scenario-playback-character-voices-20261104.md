# AI Conversation: chữa bài, phục hồi chấm và giọng theo nhân vật

## Thay đổi

- GRADE thất bại có thể được yêu cầu lại trên cùng attempt: giữ câu trả lời,
  support snapshot và payload/prompt gốc; tối đa ba operation gồm lần đầu.
  UNKNOWN không có nút retry trả phí. Owner, version, CSRF, idempotency và budget
  tiếp tục được kiểm tra. Không đổi giới hạn ba lượt nộp hoặc chính sách hoàn thành.
- Hoàn thành hoạt động mở phần chữa bài trước khi chuyển tiếp. Có đáp án,
  bằng chứng, bản chép/dịch và nghe lại. Lựa chọn chữa bài được giữ sau reload.
- Tổng kết tách lần đầu/lần chấm thành công mới nhất, câu chưa chấm được và
  việc dùng hỗ trợ. `plays` được gọi đúng là số lần yêu cầu phát.
- Playback phân biệt chặn autoplay với lỗi tải/giải mã; bỏ qua callback sau
  navigation/đổi tài khoản. Nút nghe hiển thị tên nhân vật.
- TTS V2 đóng băng recipe và mapping hai nhân vật trong provenance có sẵn.
  Cache chứa text/speaker/voice/recipe; Core kiểm tra metadata trước khi lưu.
  Legacy DTO, hash, job và cache V1 giữ nguyên. Không thêm migration/dependency.
- Trace V2 có recipe/speaker/voice; không xuất audio base64 hoặc credential.

Hợp đồng cấu hình, thứ tự rollout và rollback nằm ở mục
“Scenario playback and character voices” trong `services/ai-generation/README.md`.
Mặc định nhiều giọng chưa bật; cần cấu hình recipe đã kiểm tra ở Core/API/worker.

## Kiểm tra tự động

| Bộ kiểm tra | Kết quả |
|---|---|
| Python: toàn bộ `services/ai-generation/tests` | 554 đạt, 2 bỏ qua |
| JavaScript: `node --test src/test/js/*.test.cjs` | 366/366 đạt |
| Java: ScenarioContent, ScenarioEngineIntegration, ScenarioIntegration, ScenarioDisabledIntegration, AiPlatformClient, AiBackgroundJobsIntegration | 104 đạt, 1 bỏ qua |
| UTF-8, syntax, package và diff whitespace | Đạt |

Java bỏ qua ca `leaseExpiryWhileCompletionWaitsForParentLockRejectsResult`
vì chưa bật system property `dictalab.testcontainers`. Không tuyên bố ca đó đã chạy.
Test mới kiểm tra retry/idempotency/UNKNOWN, metadata sai giọng, tương thích V1,
phục hồi UI, tổng kết và lỗi audio. Test WAV streaming cũ tiếp tục đạt.

## Kiểm tra thật trên preview riêng

- URL: `http://127.0.0.1:18082/conversation`.
- Bài mới: `6db264bc-8b1a-4a5c-b6aa-9c34462842ec`, CEFR B1, COMPLETED.
- 11 operation thật đều SUCCEEDED: EXTRACT ×1, GENERATE ×1, TTS ×6, GRADE ×3.
- Hai giọng Cartesia đã kiểm tra bằng mẫu ngắn; sáu audio trong bài đúng mapping
  speaker → voice xuyên ba hoạt động, kể cả khi thứ tự người nói đổi.
- Câu trả lời Anh/Việt được giữ và chấm. Sáu câu CAPTURED, nhưng cả ba hoạt động
  đều mở transcript: đây là kiểm tra chức năng, không phải đo năng lực nghe.
- Chữa bài trước khi chuyển tiếp, reload giữ phần chữa, phát lại dùng cache.
- Bài cũ `f6c2f802-4487-443f-a079-b2df1a85f800` vẫn COMPLETED, không có snapshot
  V2; phát lại thành công và số operation TTS cũ vẫn 12, không tạo thêm job.
- Mobile 390×844 không tràn ngang. Screenshot/bằng chứng riêng nằm trong
  `data/scenario-playback-20261104/`, ngoài Git; `live-receipt.json` đối chiếu DB
  và metadata, có thể dựng lại không gọi AI.

Preview đã được sao lưu trước khi khởi động. Một lần kiểm tra bị ngân sách local
tích lũy chặn trước admission. Theo yêu cầu test local không giới hạn đã có,
preview riêng dùng `AI_BUDGET_ENABLED=false`; không xóa bộ đếm, không sửa source
budget, provider quota hoặc cấu hình production. Cấu hình phê duyệt program của
preview được đối chiếu đúng cả version 2 và 3 từ definition đã lưu.

## Giới hạn và phạm vi bàn giao

- Chưa có người nghe đánh giá phát âm/accent/độ tự nhiên của hai giọng.
- Bài kiểm tra không chứng minh GENERATE/TRANSLATE hoặc GRADE đúng mọi nội dung.
  Có chi tiết nội dung cần xem riêng: yêu cầu đặt cuộc họp tại sảnh, nhưng đoạn
  sinh thêm phòng chính/lên tầng. Giữ nguyên bằng chứng, chưa sửa prompt trong task này.
- Giữ giới hạn hai nhân vật của schema hiện hành. Không thêm chọn/clone giọng.
- Đưa code lên nhánh task không đồng nghĩa triển khai production. Nhánh này
  không có PR mở; workflow hiện tự chạy khi push main hoặc có PR vào main.
