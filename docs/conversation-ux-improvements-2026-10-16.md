# Cải tiến trải nghiệm luyện hội thoại

## Phạm vi đã sửa

- Một lần bấm bắt đầu sẽ tạo phiên và tạo câu đầu trên chính phiên đó. Nếu tạo câu lỗi, thử lại không tạo thêm phiên.
- Ba câu đầu có hành trình theo tình huống, cùng người nói và bối cảnh. Bộ kiểm tra nội dung nhận cùng ràng buộc này.
- Bản làm rõ được yêu cầu dùng câu ngắn, từ phổ thông, không viết tắt; kiểm tra lại việc giữ nguyên ý trước khi công bố.
- Sau khi trả lời đủ ý hoặc hết ba lượt, mở phần đối chiếu: ý đúng, cụm tiếng Anh làm bằng chứng, transcript và bản dịch thu gọn.
- Người học có thể chủ động mở lời giải và kết thúc câu; không được cộng điểm cho việc nhìn đáp án. Không bỏ qua câu đang chờ chấm để làm mất câu trả lời đã lưu.
- Hiện số ý đã nắm và số lượt còn lại. Trả lời đúng ở lượt ba vẫn được ghi nhận đúng, không hiện thông báo hết lượt thay cho kết quả.
- Tóm tắt theo kỹ năng thay vì chỉ liệt kê đáp án. Xem lại và nghe lại từng câu ngay từ kết quả.
- Nút luyện theo mục tiêu tạo phiên mới cùng độ khó, lấy điểm cần luyện từ phiên đã hoàn thành thuộc chính người dùng. Client không tự cung cấp đáp án hay kỹ năng để chấm.
- Ghi nhận hỗ trợ theo lần đầu mỗi ý được nắm vững trong chuỗi bằng chứng còn đúng; mâu thuẫn về sau sẽ đặt lại chuỗi này.
- Nếu không làm được câu cuối, có thể lưu kết quả ba câu. Màn hình nói rõ chưa kiểm tra vận dụng, không đánh dấu câu bốn đã xong hay tự khuyên tăng độ khó.
- Bản nháp lưu trong sessionStorage, tách theo người dùng/phiên/câu, hết hạn sau 24 giờ; xóa khi gửi thành công hoặc đăng xuất.
- Gọn phần đầu trên mobile, nghe lại cạnh ô nhập, thu gọn lượt trả lời cũ. Icon điều hướng dùng SVG nội tuyến trong CSS, không phụ thuộc font icon tải được hay không.

Không đổi schema database, không thêm dependency; giữ kiểm tra quyền sở hữu, CSRF, lease, idempotency, giới hạn sử dụng AI và kiểm tra nội dung.

## Kiểm chứng

- JavaScript: `node --test src/test/js/*.cjs` — 273/273 đạt, trong đó 17 ca hội thoại.
- Java: `mvn --batch-mode --no-transfer-progress -Ddictalab.build.directory=target/conversation-ux verify` — build đạt; 468 ca được phát hiện, 402 chạy đạt, 66 bỏ qua theo cấu hình môi trường.
- Sau chỉnh sửa logic cuối: chạy lại `ConversationIntegrationTest` với `verify` — 45 đạt, 1 bỏ qua theo cấu hình PostgreSQL.
- Kiểm tra mã hóa UTF-8 trên toàn bộ file sửa; không phát hiện mojibake.
- Trình duyệt với CSS và module thật, API mô phỏng: desktop 1280 px, mobile 390 px và kết quả 360 px; không tràn ngang tại các màn đã kiểm tra.
- Đã thử lưu nháp rồi tải lại, gửi câu trả lời để mở lời giải, xem bốn câu trong kết quả và bắt đầu luyện theo mục tiêu.

## Giới hạn cần biết

- Chưa chạy lại hành trình với nhà cung cấp AI/TTS thật trên production. Các kiểm thử backend dùng nhà cung cấp giả lập; kiểm tra browser không đánh giá chất lượng giọng nói.
- Không bỏ qua bộ kiểm tra AI để xuất bản câu lỗi, không tự gọi lại dịch vụ có phí vô hạn. Lỗi tạo câu cuối có đường thử lại hoặc lưu kết quả ba câu; không dùng bài dự phòng giả làm bài kiểm tra vận dụng.
- Chưa thêm cắt audio theo timestamp, nhận diện giọng nói, hay đánh giá hiệu quả học dài hạn. Điểm trong phiên không phải chứng nhận CEFR.
- Chưa triển khai production; nhánh làm việc là `codex/dictalab-ui-release-20261014`. Quy trình hiện tại chỉ triển khai từ `main`.

## Skill đã áp dụng

- [Ponytail](C:/Users/HP/.codex/plugins/cache/ponytail/ponytail/5.2.0/skills/ponytail/SKILL.md): thay đổi gọn trong luồng sẵn có, không thêm framework hoặc dependency.
- [frontend-design](C:/Users/HP/.agents/skills/frontend-design/SKILL.md): giữ ngôn ngữ hình ảnh hiện có, giảm lớp khung và ưu tiên nghe/trả lời trên mobile.
- [Vietnamese Encoding Guard](C:/Users/HP/.codex/skills/vietnamese-encoding-guard/SKILL.md): chỉnh sửa UTF-8 bằng patch và kiểm tra mã hóa.
- [agent-browser](C:/Users/HP/.agents/skills/agent-browser/SKILL.md): kiểm tra bản render và thao tác trên trình duyệt bằng phiên riêng.
- [Spring Testing Fundamentals](C:/Users/HP/.codex/skills/spring-testing-fundamentals/SKILL.md) và [Spring Security Testing](C:/Users/HP/.codex/skills/spring-security-testing/SKILL.md): mở rộng bộ kiểm thử hiện có, gồm quyền sở hữu phiên luyện nguồn, không tắt bảo vệ ứng dụng.
