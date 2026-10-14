# Tên miền miễn phí — 13/10/2026

**Cập nhật 14/10:** đã triển khai Cloudflare tại https://dictalab.hoangptkdhy.workers.dev và đổi Netlify sang chuyển hướng tới Cloudflare. HTTPS, đăng nhập email, audio Range và WebSocket hai thành viên đã qua kiểm tra. Chi tiết: `docs/cloudflare-2026-10-14.md`. Các mục bên dưới ghi lại tiến trình ngày 13/10.

## Netlify đã tạo và kiểm tra

- Site: https://dictalab.netlify.app
- Quản lý: https://app.netlify.com/projects/dictalab
- Đã công khai sau khi chủ tài khoản xác nhận Make public.
- Không nâng cấp gói, không mua tên miền, không dùng Agent Runner để sửa code.
- Thử proxy HTTPS toàn bộ sang https://184.194.89.90: HTML khớp SHA256 với VPS; đăng nhập email, cookie Secure/HttpOnly, refresh, logout và CSRF hoạt động.
- WebSocket có xác thực: VPS nâng cấp thành công (101), Netlify trả 400. Phép thử dùng tài khoản riêng, không tạo phòng hoặc lịch sử học.
- Đã chuyển Netlify sang **302 tới VPS**, giữ đường dẫn. Ví dụ `/history` trả `Location: https://184.194.89.90/history`. Đây là địa chỉ chuyển tiếp, chưa phải tên miền phục vụ toàn bộ app.
- Không đổi JAR, cấu hình môi trường hoặc Caddy trên VPS.
- Hai tài khoản thử nghiệm đã được đăng xuất và xóa đúng định danh. Script PowerShell cũ lỗi quoting khi cleanup; đã xử lý riêng qua stdin SSH và xác nhận thành công.

## Tệp triển khai và bằng chứng

- Proxy thử nghiệm: `artifacts/netlify-proxy/dictalab-proxy.zip`.
- Bản chuyển hướng đang dùng: `artifacts/netlify-redirect/dictalab-redirect.zip`.
- Kiểm tra proxy: `python artifacts/netlify-proxy/check.py`. Chỉ dùng khi site thực sự chạy proxy; bản đang hoạt động là 302 nên không chạy lại với cấu hình hiện tại.
- Headers kiểm tra: `artifacts/netlify-proxy/public-headers.txt`, `artifacts/netlify-redirect/headers.txt`.
- Ảnh Netlify đã công khai: `artifacts/netlify-proxy/netlify-published.png`.

## Dịch vụ thay thế

Chủ tài khoản chọn tên miền miễn phí trỏ trực tiếp VPS, sau đó yêu cầu dịch vụ có tài khoản và quản lý tên miền chuyên nghiệp hơn. DuckDNS đã đăng nhập nhưng yêu cầu reCAPTCHA; chưa tạo hostname tại DuckDNS.

Đề xuất Dynu Dynamic DNS:

- Tài khoản và tối đa 4 hostname miễn phí theo FAQ hiện tại.
- Quản lý DNS A/AAAA/CNAME; trỏ thẳng VPS, không đi qua proxy Netlify.
- Dynu công bố tài khoản miễn phí không hết hạn do không truy cập.
- Cấp tên miền phụ, không cấp riêng một tên miền `.com`/`.vn`.
- TXT cho hostname mới thuộc gói miễn phí bị giới hạn tuổi tối thiểu 30 ngày theo thông báo hỗ trợ; cần kiểm tra nếu xác minh sở hữu Google yêu cầu TXT.

Nguồn: https://www.dynu.com/en-US/DynamicDNS ; https://www.dynu.com/FAQ/Dynamic-DNS-Service ; https://www.dynu.com/en-US/Forum/ViewTopic/Quota-for-API-updates/19322

## Còn cần hoàn tất

1. Đăng ký/xác minh tài khoản dịch vụ được chọn và tạo hostname còn trống.
2. Đặt A record tới `184.194.89.90`; chỉ đặt AAAA nếu VPS có IPv6 thực tế.
3. Sao lưu Caddy/env, thêm hostname HTTPS và kiểm tra chứng chỉ trước khi đổi public URL.
4. Thêm origin chính xác vào cấu hình phòng học; kiểm tra đăng nhập và WebSocket bằng Origin của hostname mới.
5. Đổi public URL, cập nhật địa chỉ chuyển tiếp Netlify và xác minh luồng thực tế.
6. Thêm hostname vào Google OAuth client; Google login còn là bản local theo `docs/google-login-2026-10-13.md`, chưa deploy và chưa xác nhận đăng nhập Google thành công.
