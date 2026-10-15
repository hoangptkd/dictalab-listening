# Google login và đồng bộ form xác thực — 14/10/2026

## Kết quả

- Production: https://dictalab.hoangptkdhy.workers.dev.
- Đã bật `GOOGLE_CLIENT_ID` trong cấu hình runtime được bảo vệ của VPS và deploy.
- Google Auth Platform: External, **In production**; đã lưu homepage, privacy và terms trên địa chỉ Cloudflare. Origin này đã được đăng ký trong OAuth client.
- Hai tab đăng nhập/đăng ký dùng chung bố cục từ mẫu Stitch `stitch-proposal-register.png`, project `6530183283621390934`, screen `3451944117445801020`. Logo, cột giới thiệu, vị trí form, tab, typography và kiểu nút thống nhất; giữ bo tròn hiện có. Chiều cao nội dung khác nhau do đăng ký có thêm trường tên và hướng dẫn mật khẩu.
- Form email, password reset, chức năng và điều hướng hiện có được giữ nguyên.

## Gói triển khai

- Nền production SHA-256: `81c5e1fa443c5d53326c4b4d15c3d5ebb931ea6cb8105140bd9e1a359fda85ce`.
- Bản mới: `9d0b6c7ff56900581e98fa44307cce5c9a54d95bbf979998d6e4e23d352addec`.
- `artifacts/google-release/package.py` chỉ overlay lớp xác thực Google và tài nguyên liên quan lên JAR production. Dependency và các lớp nghiệp vụ khác giữ nguyên theo manifest; các chỉnh sửa catalog local không đưa vào gói này.
- Backup JAR/env: `/var/lib/dictalab/backups/release-wz947ss3`; quy trình đã chạy cloud backup khi ứng dụng dừng, trước khi thay artifact.
- Cấu hình và JAR tự khôi phục nếu readiness thất bại; migration thêm dữ liệu được giữ khi rollback ứng dụng.
- PostgreSQL 18.6, trước deploy bảng `app_users` có 110 tài khoản, tổng dung lượng 136 kB. V30 thêm nullable `google_subject` và unique index, đã kiểm tra Flyway `30|true`, cột và index tồn tại. Sau cleanup vẫn 110 tài khoản.
- Flyway connection đặt lock timeout 5 giây, statement timeout 60 giây. Không xóa hoặc backfill dữ liệu học.

## Kiểm tra đã thực hiện

| Kiểm tra | Kết quả |
|---|---|
| GoogleIdentityService, GoogleAccount, GoogleAuth, AuthFlow, AuthTransaction | 41 tests, 0 lỗi |
| Google login JS và JWT/CSRF JS | 17 tests, 0 lỗi |
| Readiness VPS và SHA artifact | UP, khớp bản mới |
| Google config, CSP/COOP, CSRF, nonce cookie Secure/HttpOnly, token sai | Đạt trên production |
| Đăng ký/email login/refresh/logout | Đạt trên production |
| Catalog/history/dashboard, dữ liệu riêng tư/no-store | Đạt |
| Tạo/join/approve phòng, invite hostname, WebSocket hai thành viên | Đạt |
| WebSocket không cookie hoặc sai Origin | Bị từ chối đúng |
| Hai form ở 320, 390, 768, 1024, 1440 px | Không tràn ngang; cùng kiểu khung/tab/nút; nút Google có mặt |
| `/login`, `/register`, Google JS, CSS, privacy, terms | HTTP 200 |

Chrome chính được dùng để kiểm tra, không mở profile Chrome mới. Chrome ở nền nên kiểm tra responsive tải lại trang tại từng kích thước; không dùng kết quả của ResizeObserver bị trình duyệt tạm dừng ở tab nền để kết luận lỗi của website. Script kiểm tra có lưu số đo tại `artifacts/google-release/layout-checks.json`.

Ảnh đã xem trực tiếp: `artifacts/google-release/login-desktop.png`, `register-desktop.png`, `login-320.png`, `register-320.png`. Đây là kiểm tra đồng bộ giao diện hai form, không phải kết luận pixel 100% toàn website.

Hai tài khoản smoke được logout và xóa đúng phạm vi; không tạo bản ghi luyện học. Không kiểm tra microphone/WebRTC trên hai thiết bị thật trong lượt này.

## Giới hạn đăng nhập Google của tài khoản hiện có

Chrome đã mở Google và nhận callback thật; token hợp lệ được backend xử lý. Email Google trùng một tài khoản mật khẩu **chưa xác minh email**, nên backend từ chối liên kết và hiển thị hướng dẫn xác minh. Không sửa dữ liệu để bỏ điều kiện này.

Chủ tài khoản cần đăng nhập bằng mật khẩu, xác minh email trong trang Tài khoản, rồi đăng nhập Google lại. Nếu quên mật khẩu, dùng luồng khôi phục hiện có. Chưa xác nhận một lượt Google login thật cấp cookie DictaLab thành công cho tài khoản này; việc cấp cookie và tài khoản Google mới đã được kiểm tra bằng integration tests.

Nguồn Google chính thức: [xác minh ID token và quyền sở hữu email](https://developers.google.com/identity/gsi/web/guides/verify-google-id-token), [cấu hình Google Identity Services](https://developers.google.com/identity/gsi/web/guides/get-google-api-clientid), [trạng thái OAuth app](https://developers.google.com/identity/protocols/oauth2/production-readiness/overview).
