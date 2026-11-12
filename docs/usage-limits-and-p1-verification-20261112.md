# Bỏ hạn mức sử dụng và xử lý P1 — 12/11/2026

## Phạm vi đã sửa

Dùng công tắc có sẵn `dictalab.operational-limits-enabled` /
`DICTALAB_OPERATIONAL_LIMITS_ENABLED`, mặc định `false` ở Core và AI service.
Không dùng số giả lớn để biểu diễn không giới hạn.

Khi tắt công tắc:

- Không yêu cầu Premium để dùng AI; không chặn số bài miễn phí hay lượt AI/TTS trong ngày.
- Không áp dụng ngân sách AI theo người dùng/toàn hệ thống, kể cả khi cấu hình ngân sách riêng còn bật.
- Không chặn 20 bài/24 giờ hoặc ba tác vụ đang chờ theo người dùng; giữ đăng ký tác vụ bền vững và chống gọi trùng.
- Không giới hạn tổng bộ từ, mục từ, kho câu hay số thẻ mới mỗi ngày. Kích thước mỗi phiên và yêu cầu vẫn được kiểm tra.
- Giao diện thể hiện học không giới hạn; không mời mua quyền truy cập vốn đã được mở. Thời hạn Premium và lịch sử thanh toán cũ được giữ nguyên.
- Gemini bỏ các hạn mức do ứng dụng tự đặt. Phản hồi 429/quota và thời gian chờ do nhà cung cấp trả về vẫn có hiệu lực.
- Demo cloud vẫn cần được cấu hình và cho phép rõ ràng; giữ dấu đã dùng để không gọi lại một yêu cầu có kết quả chưa rõ.

Giữ nguyên đăng nhập, quyền dữ liệu, CSRF, kiểm tra đầu vào/nội dung, dừng AI khẩn cấp,
chống gọi trùng phí, phục hồi lỗi và giới hạn bảo vệ từng yêu cầu.
Không đổi dữ liệu huấn luyện, prompt hoặc kích hoạt ứng viên chấm điểm.

## P1 local

### QA-01: tạo bài bị chặn trước khi gọi nhà cung cấp

Migration V52 không hỏng khi chạy bằng chủ sở hữu. Tài khoản DML của ứng dụng không thấy
đủ thông tin constraint qua `information_schema` và không được phép `ALTER TABLE`.
Đã sao lưu database local, kiểm tra danh mục bản sao, chạy Flyway bằng đúng owner rồi validate.
Database ở V52; giữ nguyên 260 bản ghi ngân sách; runtime vẫn không có quyền owner/DDL.
Không sửa V52, checksum hay lịch sử migration bằng tay.

Kiểm thử thật: EXTRACT thành công; tạo session HTTP 200, lưu đủ envelope chín lời gọi;
gửi lại cùng mã yêu cầu trả cùng tác vụ. GENERATE sau đó bị `GENERATION_REJECTED` từ quality gate.
Đây không phải lỗi quota/migration và không được tính là bài mới hoàn thành. Không gọi lại để làm đẹp kết quả.

Đã truy xuất trace cũ `f3220387edfa8bae7a640c541be65c42` và kiểm tra lại JSON ngoại tuyến:
model trả hai meanings và hai câu hỏi cho mỗi activity; hợp đồng V3 yêu cầu ít nhất ba.
Lỗi `too_short` xuất hiện ở cả sáu mảng. Không sửa hợp đồng hoặc thêm lời gọi sinh lại trong task bỏ hạn mức này.

### QA-03: bài cũ không tạo được audio khi đổi bản phát hành

Tách kiểm tra chương trình đã lưu khỏi quyền tạo bài mới. Audio và chấm bài đều kiểm tra
chương trình/hash gốc cùng quyền rollout/preview; không cần chương trình cũ còn được chọn để sinh bài.
Giữ cache đúng fingerprint, snapshot giọng đọc, quyền sở hữu và kiểm tra dữ liệu audio.
Regression kiểm tra chuẩn bị audio, chương trình bị tắt, hash sai và preview khác người dùng.
Local thật đọc lại bài program 3 không còn `playbackRestrictionVi`; bài đã chọn có đủ cache nên
không phát sinh thêm lời gọi TTS. Không suy diễn phép đọc này thành bằng chứng TTS lạnh đã được kiểm thử thật.

## Bằng chứng kiểm tra nhánh local

- Java: 200 trường hợp, 199 đạt, một bỏ qua; không lỗi.
- Python AI: 750 đạt, 27 bỏ qua. Sau khi rút gọn câu SQL demo, chạy lại riêng 10 trường hợp demo đều đạt.
- JavaScript: 420 đạt; sau chỉnh phần giá/thanh toán, bốn kiểm tra billing tiếp tục đạt.
- Build executable JAR thành công; kiểm tra UTF-8 và diff đạt.
- Kiểm thử thật bằng tài khoản QA local 14; billing/vocabulary trả `usageLimitsEnabled=false`.
- Local Core, AI API và worker được khởi động lại với công tắc tắt. Đăng xuất đúng phiên QA sau kiểm thử.
- Quyền DML cho 12 bảng từ vựng local được bổ sung theo mô hình Core hiện có; không cấp quyền DDL.

Lần chạy rộng ban đầu có một kiểm tra phục hồi thanh toán trả `CREATING` thay vì `PAID`.
Chạy lại riêng kiểm tra đó cùng kiểm tra quyền sử dụng đều đạt. Không thay logic thanh toán để che kết quả này.

Bằng chứng riêng nằm trong thư mục bị Git bỏ qua `data/conversation-full-audit-20261112/`.
Không đưa thông tin đăng nhập, database dump hoặc dữ liệu runtime vào Git.

## Production

Bản bỏ hạn mức được tách từ `origin/main` / release đang chạy
`88ed954fba79e687aace892bf63176a4dc32295d`, tại nhánh
`codex/remove-usage-limits-production-20261112`.
Không đưa kèm 15 commit thử nghiệm assessment hoặc migration V52 từ nhánh local.
Commit bỏ hạn mức đã push và xác minh trên nhánh production cùng `main`:
`dcbe1a0da806c86c16e916bbed67542b8ed9ea98`.

Kiểm tra nhánh production:

- Maven CI: 291 trường hợp, 274 đạt, 17 bỏ qua; kiểm tra tích hợp riêng 159 đạt, một bỏ qua.
- Python AI: 576 đạt, 27 bỏ qua; JavaScript: 393 đạt.
- Công cụ Linux: 100 đạt, 20 bỏ qua, chạy trong container Linux; kiểm tra JAR production đạt.

[GitHub Actions 43180150553](https://github.com/hoangptkd/dictalab-listening/actions/runs/43180150553)
đạt `test-build` và `required-checks`, nhưng bước deploy ban đầu thất bại trước khi chuyển bản chạy.
Cả bốn unit có `NeedDaemonReload=yes`; guard triển khai đã chặn đúng. Sau khi xác minh
bundle, hash các unit và cấu hình được bảo vệ, đã thực hiện `systemctl daemon-reload`.
Không sửa hoặc bỏ guard, không thay cấu hình bí mật.

Đã hoàn tất triển khai thủ công bằng chính công cụ phát hành, bundle và commit đã qua CI:

- Bundle SHA-256: `595dd49c48de0efa6fd48388f9c5d2c7e6d9c283d4378085408a5b15d74094b3`.
- Receipt: `/var/lib/dictalab-service-backups/activation-p4s4nf0e/activation.json`, phase `complete`.
- Sao lưu: `/var/lib/dictalab-service-backups/services-6o9hy65m`.
- Migration chạy bằng owner và hoàn tất; giữ nguyên quyền runtime. Không phục hồi lùi dữ liệu.
- Con trỏ release và hash Core JAR khớp commit mới; bốn dịch vụ active, readiness đạt,
  worker có heartbeat; đã gỡ trạng thái bảo trì.
- Core JAR có mặc định hạn mức `false`; môi trường tiến trình không ghi đè thành `true`.
  Nạp Settings bằng môi trường thật của AI API và worker đều xác nhận hạn mức đã tắt.
- Public `/actuator/health` trả HTTP 200 / `UP`; `billing.js` và `vocabulary.js` từ website
  có hash khớp source production.

Run GitHub ban đầu vẫn hiển thị deploy thất bại; không đổi kết quả đó thành thành công.
Kết quả production ở trên là triển khai phục hồi thủ công đã xác minh. Không dùng tài khoản
cá nhân để tạo phiên production mới; chưa chạy lại toàn bộ luồng học có đăng nhập trên production.
Không tuyên bố bài sinh mới đạt chất lượng: kết quả `GENERATION_REJECTED` local ở trên vẫn còn.
Các kiểm tra dữ liệu và lỗi từ nhà cung cấp vẫn có hiệu lực.
