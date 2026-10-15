# Đăng nhập Google — 13/10/2026

**Cập nhật 14/10:** đã bật Google trên VPS, deploy và xác nhận Google Auth Platform ở trạng thái In production. Đã đồng bộ hai form đăng nhập/đăng ký theo khung Stitch, giữ bo tròn. Báo cáo hiện tại: [Google VPS release 14/10](google-vps-release-2026-10-14.md). Callback Google thật đã tới backend; tài khoản mật khẩu cũ chưa xác minh email cần hoàn tất xác minh trước khi liên kết.

**Các mục bên dưới là ghi nhận ngày 13/10, trước deploy; trạng thái còn lại được thay thế bằng báo cáo 14/10.**

## Đã triển khai trong mã nguồn

- Nút Google chính thức trên cả đăng nhập và đăng ký, cùng form email hiện có.
- Backend xác minh chữ ký RS256 bằng khóa Google, issuer, audience, azp, thời hạn, email đã xác thực và nonce.
- Nonce ngẫu nhiên 256 bit, cookie HttpOnly, TTL 5 phút, dùng một lần bằng Redis GETDEL. POST vẫn yêu cầu CSRF của DictaLab và chịu giới hạn đăng nhập hiện có.
- Đăng nhập Google cấp JWT/cookie DictaLab hiện có; refresh, logout, khóa tài khoản và thu hồi phiên giữ nguyên.
- Lưu Google subject, không dùng email làm định danh Google lâu dài. Gmail/Workspace có thể liên kết tài khoản email cũ đã xác minh và giữ lịch sử, tên, mật khẩu và vai trò. Tài khoản mật khẩu chưa xác minh phải đăng nhập/xác minh hoặc khôi phục mật khẩu trước khi liên kết; tránh gắn Google vào tài khoản được người khác tạo trước bằng email của chủ Google. Email ngoài Google không tự liên kết với tài khoản cũ; tài khoản mới loại này vẫn cần xác minh email trước các thao tác yêu cầu email đã xác thực.
- Khôi phục đường dẫn nội bộ sau đăng nhập; từ chối chuyển sang website ngoài. Tải Google thất bại vẫn dùng được form email.
- Không yêu cầu quyền Gmail/Drive, không lưu Google access token hoặc ID token. Chính sách quyền riêng tư đã cập nhật.
- Không có dependency mới; sử dụng Spring Security JWT/Nimbus và Redis đã cài.

## Google Cloud đã thiết lập

- Project: `dictalab`, tên DictaLab; không gắn billing hoặc tạo dịch vụ có phí.
- Google Auth Platform: External, đang Testing; email hỗ trợ/liên hệ dùng tài khoản chủ project.
- OAuth client: **DictaLab Web Login**, loại **Web application**.
- Authorized JavaScript origins hiện tại: `http://localhost`, `http://localhost:18089`.
- Đã nhập tài khoản chủ project vào test users; cần xác nhận thao tác Save đã được Google lưu trước lượt đăng nhập tiếp theo.
- `GOOGLE_CLIENT_ID` đã lưu trong `.env` local, bị Git ignore. Tích hợp này chỉ cần client ID công khai; không dùng client secret.
- Khi `GOOGLE_CLIENT_ID` trống, Google login bị tắt và không tải script Google.

## Kiểm tra đã chạy

- `mvn -q '-Ddictalab.build.directory=target-google-login' '-Dtest=GoogleIdentityServiceTest,GoogleAccountIntegrationTest,GoogleAuthIntegrationTest,AuthFlowIntegrationTest,AuthTransactionIntegrationTest' test`: **41 kiểm tra, 0 lỗi**.
- `node --test src/test/js/*.test.cjs`: **173 kiểm tra, 0 lỗi**.
- `mvn -q '-Ddictalab.build.directory=target-google-login' '-DskipTests' package`: build thành công. JAR này chứa cả thay đổi local khác, **không dùng trực tiếp để deploy**.
- UTF-8 checker: các tệp sửa đạt kiểm tra.
- Server local riêng: H2 trong bộ nhớ, Redis container riêng `dictalab-google-redis`, không truy cập dữ liệu học production.
- Chrome thực tế tải Google Identity Services qua CSP, hiện nút và mở popup chọn tài khoản/consent; chưa xác nhận callback hoàn tất và đăng nhập thực tế thành công.
- Kiểm tra kích thước 320, 390, 768, 1024, 1440 px: không tràn ngang; nút tự điều chỉnh khi đổi viewport.
- Axe kiểm tra trang đăng nhập local: 0 vi phạm được xác nhận; 1 mục cần xét thủ công cho ký tự mũi tên trang trí có `aria-hidden` của nút email hiện có.
- Kiểm tra hồi quy: token sai chữ ký/audience/issuer/azp, hết hạn, thiếu expiration, nonce sai/thiếu/lặp, email chưa xác thực, Redis lỗi; tài khoản khóa, email ngoài Google trùng tài khoản, signup đồng thời, giữ mật khẩu/vai trò, CSRF, cookie JWT và chuyển hướng.
- Không tạo bản ghi học thử trên VPS.

## Phần còn lại cần chủ website bổ sung

1. **Tên miền của DictaLab.** Website production đang dùng `https://184.194.89.90`. Google không cho origin production là IP. Cung cấp tên miền thuộc quyền quản lý của bạn; trỏ DNS về VPS và cấu hình HTTPS/Caddy.
2. Trong Google Cloud → DictaLab → Google Auth Platform → Clients → DictaLab Web Login, thêm origin `https://TEN-MIEN-CUA-BAN` (không có path). Luồng popup JS không cần redirect URI.
3. Branding: thêm homepage, privacy, terms bằng tên miền; xác minh quyền quản lý tên miền nếu Google yêu cầu. Chỉ dùng thông tin thuộc chủ website.
4. Hoàn tất consent Google và kiểm tra một lượt đăng nhập trên tên miền thật. Nếu có bước mật khẩu/2FA/xác minh danh tính, chủ tài khoản tự thực hiện.
5. Khi muốn mọi người dùng được, hoàn tất yêu cầu Google rồi chuyển Audience từ Testing sang Production. Chưa publish ứng dụng trong lượt này.
6. Cấu hình VPS `GOOGLE_CLIENT_ID` và `DICTALAB_PUBLIC_URL` bằng cấu hình runtime bảo vệ, không đưa credentials vào Git. Triển khai đúng thay đổi Google trên nền bản production đã xác minh, giữ các sửa giao diện hiện có; không kéo theo thay đổi backend local khác.

## Trạng thái VPS và migration

Google login **chưa kích hoạt/deploy trên VPS**. Bản production hiện tại và dữ liệu học giữ nguyên. Không thể kết luận đăng nhập Google production thành công khi chưa có tên miền và lượt đăng nhập thật.

Migration V30 thêm cột nullable `google_subject` và unique index vào `app_users`, không backfill/xóa dữ liệu. Đã chạy trên H2 kiểm thử. Trước deploy PostgreSQL, kiểm tra kích thước bảng, lock timeout và kế hoạch dừng ứng dụng; index có thể giữ khóa trong quá trình tạo. Có backup và xác minh sau migration; không hứa DDL không khóa khi chưa kiểm tra database production.

Nguồn chính thức:

- [Google: cấu hình client và CSP](https://developers.google.com/identity/gsi/web/guides/get-google-api-clientid)
- [Google: xác minh ID token và quyền sở hữu email](https://developers.google.com/identity/gsi/web/guides/verify-google-id-token)
- [Google: quy tắc origin, không dùng IP production](https://developers.google.com/identity/protocols/oauth2/javascript-implicit-flow#origin-validation)
- [Spring Security: JWT decoder](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
