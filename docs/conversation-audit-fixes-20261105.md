# AI Conversation — xác minh sau sửa, 05/11/2026

Phạm vi: hoàn thiện 14 mục trong audit giao diện và luồng luyện nghe; giữ route
`/conversation`, API hiện tại, dữ liệu và lịch sử. Không đổi prompt/model, schema
chấm điểm, migration hoặc dependency.

## Kết quả

- Phục hồi lỗi tạo bài có mã tác vụ và đường về mô tả; UNKNOWN không tự gọi lại AI.
- Preview remix được phục hồi trên route session sau reload; chỉ xóa draft khi
  người dùng chủ động rời preview hoặc Core đã nhận yêu cầu tạo bài.
- Giữ ô trả lời rộng, action có khoảng cách, control có style và header mobile wrap.
- Hiển thị tên chương trình, trạng thái, câu hỏi và số thứ tự đoạn dễ đọc.
- Phản hồi câu sai chỉ đoạn cần nghe và chiến lược theo kỹ năng; không lộ đáp án
  hay thêm suy đoán về ý người học thiếu. Không ghi đè kết quả chấm đã lưu.
- Gợi ý chiến lược theo câu hỏi; tổng kết đếm riêng hint và transcript, đủ mọi
  trạng thái chấm. Vẫn loại dữ liệu có hỗ trợ khỏi bằng chứng nghe độc lập.
- Phát toàn bộ nối tiếp audio đã cache và đang chờ TTS; Stop/pause/error/rời trang
  hủy chuỗi. Stop khi POST đang chờ vẫn nhận sessionVersion đã được Core xác nhận.
- Mục tiêu đã lưu được thu gọn; gợi ý trùng không có lý do cá nhân hóa được ẩn.
  Khuyến nghị thật giữ kỹ năng cần luyện và truyền vào draft sau kiểm tra profile.
- Nghe chủ động không hiển thị sẵn bối cảnh chi tiết, mục tiêu tình huống hoặc vai
  nhân vật; nội dung này vẫn có trong preview và chữa bài/hoàn thành. Mô tả do
  người dùng nhập và tiêu đề bài vẫn có thể cung cấp ngữ cảnh.

## Kiểm chứng

```text
node --check src/main/resources/static/js/scenario.js
node --test src/test/js/scenario.test.cjs src/test/js/conversation.test.cjs
mvn -q "-Dtest=ScenarioEngineIntegrationTest,ScenarioContentTest" package
```

- 61/61 JavaScript; 75/75 Java, không skip. Có kiểm tra tương thích request cũ,
  fingerprint idempotency, CSRF/ownership, profile và feedback không lộ đáp án.
- Local build mới phục vụ JS/CSS khớp hash source. Chrome desktop 1440×1000 và
  mobile mô phỏng 390×844 có ảnh viewport/full-page và DOM geometry.
- Bài ACTIVE đã lưu: chạy thật 3 hoạt động, 12 audio kết thúc, 6 câu trả lời bằng
  Anh/Việt, 3 lần chấm; hoàn thành với 5 CAPTURED, 1 INCORRECT có chủ đích.
  Một hoạt động dùng hint, một dùng transcript; đây là QA có tham chiếu đáp án,
  không phải phép đo năng lực người học.
- Session có snapshot giọng: 7 WAV và 7 TTS operation thành công, hai voice ID
  khớp snapshot; phát nối tiếp thực tế gồm cache và một đoạn mới tạo.
- Remix EXTRACT thành công; reload phục hồi preview. Các trạng thái grade lỗi,
  UNKNOWN, hết lượt, 409 và chặn autoplay còn được kiểm tra bằng fixture browser
  có nhãn, không coi là lỗi provider thật.
- Axe trên vùng chữa bài: 0 violation; còn 1 kiểm tra contrast chưa kết luận vì
  nền gradient. Không thay cho chứng nhận WCAG hoặc kiểm tra screen reader.

## Giới hạn còn lại

Lần EXTRACT mới thành công, nhưng GENERATE mới trả `UNKNOWN / AI_JOB_UNKNOWN`;
AI job ghi `PROVIDER_OUTCOME_UNKNOWN`. Chưa đủ bằng chứng xác định nguyên nhân
provider, không tự retry và không tính là bài tạo thành công. Luồng luyện tập
được xác minh bằng session đã có. Cần điều tra riêng nếu tiếp tục gặp UNKNOWN.

Chưa đánh giá âm sắc/phát âm bằng người nghe, chưa kiểm tra Safari/iOS thật,
chưa chứng minh hiệu quả học thống kê. Bài cũ không có snapshot giọng vẫn giữ
chính sách giọng cũ. Gói bằng chứng riêng nằm tại
`data/conversation-ui-verification-20261105/` (Git ignore); không chứa khóa,
cookie, cấu hình đăng nhập hoặc database dump. Chưa triển khai production.
