# Triển khai bản sửa Checklist Design — 03/10/2026

## Kết quả

Đã triển khai lên [website production](https://160.191.244.27:18443/). Dịch vụ DictaLab đang Running; health nội bộ và HTTPS công khai đều trả UP.

- Backend và frontend được phát hành cùng nhau từ bản source cố định tại `target/checklist-release-20261003/`.
- JAR production: `C:\DictaLab\app\dictalab.jar`, 88.392.062 byte.
- SHA-256 đã xác minh trên VPS: `048B823765789EFA5762595D5BC36AFF925B1B3CAE045021C3646B6AEE2D2808`.
- Bản phát hành cũng chứa các thay đổi reliability có sẵn trong workspace, cập nhật cấu trúc dữ liệu từ V22 lên V24. Các migration V1–V22 khớp byte với bản production trước đó.

## Kiểm tra trước triển khai

- JavaScript: 85/85 đạt.
- Maven/H2 và bộ kiểm tra PostgreSQL 18 local: mỗi lượt ghi nhận 160 kiểm tra, 159 đạt, 1 benchmark tùy chọn bỏ qua.
- Bộ kiểm tra đầy đủ qua kết nối SSH đến PostgreSQL 17 bị ngắt kết nối; không tính lần đó là đạt. Đã dọn cơ sở dữ liệu thử và kết nối tạm.
- Trên PostgreSQL 17.11 của VPS: 16 kiểm tra dữ liệu trước phát hành đều không có lỗi.
- Đã phục hồi bản sao lưu vào cơ sở dữ liệu thử trên VPS; JAR mới chạy và migrate đến V24 thành công. JAR cũ cũng khởi động khỏe trên bản sao V24, giữ 7 tài khoản, 36 buổi học và không có khóa ngoại hỏng. Đây là kiểm tra khởi động của bản quay lại, chưa phải nghiệm thu mọi hành trình.

## Sao lưu và quay lại

Bản sao lưu cuối được tạo khi dịch vụ đã dừng, gồm cơ sở dữ liệu và media. Thử phục hồi thành công trước khi thay JAR:

- Database: `C:\DictaLab\backups\dictalab-20261003-114846-678.dump`, 4.860.121 byte.
- SHA-256: `3C428D8074429543002D5B7F85E48A300239B92FED4FAA81DB20C37BA6BDA210`.
- Manifest: `C:\DictaLab\backups\dictalab-20261003-114846-678.manifest.json`.
- Media: `C:\DictaLab\backups\dictalab-20261003-114846-678.media`, 3 tệp, đã kiểm tra checksum sau phục hồi.
- JAR trước: `C:\DictaLab\app\dictalab.previous.jar`; thêm một bản giữ tại `C:\DictaLab\backups\checklist-before-20261003-114543.jar`.
- Quy trình triển khai tự quay lại JAR trước nếu bản mới không khỏe. Việc quay lại JAR không tự hoàn nguyên cấu trúc dữ liệu.

## Kiểm tra sau triển khai

| Kiểm tra | Kết quả |
| --- | --- |
| Dịch vụ và checksum JAR | Running, khớp SHA-256 bản phát hành |
| Health nội bộ và HTTPS công khai | UP |
| Cấu trúc dữ liệu | V24; 0 migration thất bại; 0 constraint chưa được xác nhận |
| Dữ liệu | 7 tài khoản, 36 buổi học; 0 buổi có khóa ngoại tài khoản hỏng |
| `/`, `/js/app.js`, `/js/account.js`, `/css/app.css` | SHA-256 khớp source cố định của bản phát hành |
| Dashboard sau đăng nhập bằng tài khoản được cung cấp | Trả dữ liệu, 8 điểm biểu đồ có sessionId |
| Tổng quan quản trị 7/30/90 ngày | Trả đúng kỳ, 7 tài khoản, các số liệu không âm |
| Xuất CSV 7/30/90 ngày | UTF-8 có BOM; không chứa địa chỉ email |
| Khách chưa đăng nhập truy cập Dashboard API, thống kê quản trị và CSV | HTTP 401 |
| Đăng xuất sau kiểm tra | HTTP 204 |

Các kiểm tra tài khoản chỉ đăng nhập, đọc và đăng xuất; không gửi yêu cầu hỗ trợ, đổi tên, đổi mật khẩu, tạo hay xóa buổi học. Đăng nhập có cập nhật thời điểm đăng nhập gần nhất theo hành vi hiện có.

Đã dọn 15 phần JAR tạm trên VPS, giữ các bản sao lưu. Trong kiểm tra cuối có một lần HTTPS timeout; lần thử lại trả UP. Một lần kết nối SSH kiểm tra thêm cũng timeout, nên chưa kết luận kết nối mạng ổn định lâu dài.

## Giới hạn còn lại

- Trình duyệt của công cụ chặn URL production với ERR_BLOCKED_BY_CLIENT. Chưa nghiệm thu giao diện production bằng trình duyệt; kết quả trên dựa vào HTTPS/API, checksum và kiểm tra trực tiếp trên VPS. Không vượt qua chặn an toàn của trình duyệt.
- Chưa xác minh SMTP tới hộp thư thật, liên kết đặt lại mật khẩu thực tế, mic/cuộc gọi giữa hai thiết bị, AI production và trình đọc màn hình thật.
- Những kiểm tra trên xác nhận triển khai thành công, chưa chứng nhận toàn sản phẩm đạt WCAG hay mọi mục Checklist Design.

Bằng chứng local: `target/checklist-release-activation.log`, `target/checklist-release-validation.log`, `target/checklist-release-build.log`, `target/checklist-release-js.log`, `target/checklist-release-postgres18.log`. Các bản sao lưu trên VPS được giữ lại.
