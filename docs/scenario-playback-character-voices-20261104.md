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

## Cập nhật 2026-11-06: chuẩn bị bài và tải âm thanh cuốn chiếu

### Hành vi mới

- Xác nhận bài mở ngay màn chuẩn bị; các giai đoạn phản ánh đăng ký yêu cầu,
  tạo hội thoại/câu hỏi và chuẩn bị hai đoạn đầu. Không có phần trăm hoặc ETA giả.
  Khi reload Conversation, lớp “Đang mở DictaLab” được bỏ sau khi mở view để
  người dùng thấy màn chuẩn bị và dùng được nút vào bài trước khi audio sẵn sàng.
- Chỉ đợi tối đa hai đoạn đầu; hai worker chuẩn bị/tải/giải mã cuốn chiếu.
  File nào READY được tải ngay, không chờ cả nhóm. Những đoạn sau chạy nền khi
  đang nghe. Nếu thiếu đoạn tiếp theo, thông báo đệm thay vì giả đang phát.
- `POST .../prepare` dùng admission/cache/voice snapshot có sẵn, không tăng
  `plays` hoặc session version. Reload không retry FAILED; chỉ lần bấm nghe
  chủ động có `retryFailed=true`. UNKNOWN luôn giữ tác vụ cũ.
- `POST .../played` chỉ nhận media READY đúng owner/activity/fingerprint;
  không thể gọi provider. Sự kiện `playing` ghi một lượt trên mỗi lần gán đoạn,
  không đếm lại khi pause/resume hoặc buffering. Đây là ghi nhận bắt đầu phát,
  không chứng minh đã nghe hết. Số lịch sử từ API `/play` cũ vẫn là lượt yêu cầu.
- Prepare và ghi lượt phát xếp cùng hàng đợi, lấy version lúc gửi. Poll/download
  chạy ngoài hàng đợi. Dừng nghe ngăn admission mới; tác vụ đã gửi có thể tiếp tục
  phía server. Ghi nhận phát đã quan sát phải xong trước khi nộp câu trả lời.
- Nếu mất phản hồi ghi lượt, retry đúng body/key một lần vì endpoint không gọi
  provider; nếu vẫn chưa xác nhận, chặn mutation và yêu cầu tải lại, giữ bản nháp.
- GRADE đang chờ nằm trong bài, giữ câu trả lời và lịch sử; submit trùng bị chặn.
  Poll nền không thay màn hình giữa một thao tác help/playback. Nút kiểm tra
  trạng thái được gắn lại sau render và bỏ qua phản hồi của operation cũ.
- Help dùng trực tiếp response, không GET lại session. Blob giữ trong phạm vi
  bài đang mở và được thu hồi khi rời bài/đổi tài khoản. Không thêm dependency,
  migration, thay prompt, model, dữ liệu bài học hoặc cấu hình production.

### Kiểm chứng bản cập nhật

| Kiểm tra | Kết quả |
|---|---|
| `node --test src/test/js/*.test.cjs` | 409/409 đạt |
| `mvn -q -Dtest=ScenarioEngineIntegrationTest test` | 78/78 đạt |
| Native browser: chuẩn bị → nghe → pause/resume → stop → help | Đạt; giữ bản nháp và không GET thừa khi mở help |
| Native browser: nộp → chờ chấm trong bài → chữa → hoạt động tiếp theo | Đạt; gọi GRADE thật trên bài QA đã có |
| Hai giọng qua prepare/played mới | Hai media cache READY, hai voice ID khác nhau; không thêm TTS job |
| Mobile 390 px và 320 px | Không tràn ngang trong bài được thử |

Bài QA `6a12dd79-72e7-4494-82ba-1c0897a74145`, không sinh thêm hội thoại:

- Hoạt động 1 đo cold: chuẩn bị hai đoạn đầu **9.556 ms**, sau sẵn sàng bấm nghe
  đến `playing` **72,2 ms**; ba khoảng `ended → playing` **27,1 / 29,0 / 29,4 ms**.
  Sau khi màn chuẩn bị bị lớp khởi động che ở lượt đầu, đã sửa router và chụp
  lại màn chuẩn bị thật trên hoạt động 2; không dùng ảnh bị che làm bằng chứng UI.
- Hoạt động 2: hai đoạn đầu đã chuẩn bị, hai đoạn sau chưa có audio. Bấm nghe
  đến `playing` **765,0 ms**, các khoảng chuyển **50,9 / 58,4 / 53,8 ms**.
  Đo tốc độ audio bình thường, không mock provider/media và không xóa cache.
- Đối chiếu DB cho từng lượt: chuẩn bị giữ `plays=0`; nghe bốn đoạn thành `4`;
  nghe lại một đoạn rồi pause/resume/stop thành `5`. TTS chỉ có bốn job cho bốn
  segment; replay/help không tăng job. Hoạt động 2 trước phát có version `12`
  và `plays=0`; version bao gồm GRADE/complete trước đó, không so với trước GRADE.
- Mốc cũ khoảng 27 giây là chuẩn bị đủ bốn đoạn ở bài khác. Các phép đo đơn lẻ
  này không đủ để tuyên bố phần trăm cải thiện ổn định. TTS chậm vẫn có thể gây
  đệm; hai worker là giới hạn client, không hủy job server đã gửi trước khi Stop.

Bằng chứng riêng, ngoài Git:

- `data/conversation-e2e-learning-audit-20261106/rolling-preparation/result.json`:
  lượt cold đầu, request/event timeline và DB trước/sau.
- `data/conversation-e2e-learning-audit-20261106/rolling-preparation-final/`:
  `preparing-desktop.png`, `pending-grade-desktop.png`, ảnh desktop/mobile,
  `result.json` và `two-voice/new-voice-play.json`.

Đây là kiểm tra hành vi audio và bảo toàn trạng thái trên local, không phải
đánh giá nội dung AI hoặc chứng minh phát âm/giọng nói tự nhiên trong mọi bài.
