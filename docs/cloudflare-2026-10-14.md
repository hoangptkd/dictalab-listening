# Địa chỉ miễn phí Cloudflare — 14/10/2026

## Đã hoàn tất

- Địa chỉ chính: https://dictalab.hoangptkdhy.workers.dev
- Quản lý: https://dash.cloudflare.com/5109f30609bfc03cc6bbc9637a9070f7/workers/services/view/dictalab/production
- Netlify: https://dictalab.netlify.app chuyển hướng 302 tới Cloudflare, giữ đường dẫn. Đã kiểm tra `/history` và `/listening/history`.
- Dùng tài khoản Cloudflare đã đăng nhập trong Chrome chính, gói Workers Free. Không mua tên miền hoặc nâng cấp gói.
- Google Cloud đã chấp nhận và lưu địa chỉ Cloudflare trong Authorized JavaScript origins của OAuth client DictaLab Web Login; đã mở lại trang client để xác nhận cấu hình còn tồn tại.

## Cấu hình

- Worker chuyển tiếp HTTP/API/audio/WebSocket tới VPS qua HTTPS. Giữ cookie, CSRF token, Origin của trình duyệt, nội dung POST, query và Range; không dùng bộ đệm cho WebSocket.
- Hostname máy chủ gốc: `dictalab-origin-184-194-89-90.sslip.io`, DNS A tới `184.194.89.90`, chứng chỉ TLS hợp lệ do Let's Encrypt cấp. Đây là hostname kỹ thuật; địa chỉ dành cho người dùng được quản lý tại Cloudflare.
- Caddy thêm vhost cho máy chủ gốc, chỉ tin header chuyển tiếp từ các dải IP Cloudflare chính thức; đặt Host/X-Forwarded-Host thành địa chỉ công khai để link mời không lộ hostname gốc.
- `/etc/dictalab/app.env`: public URL mới và thêm chính xác origin Cloudflare vào danh sách phòng học. Giữ các origin cũ; không dùng wildcard hoặc tắt xác thực/CSRF/TLS.
- JAR giữ nguyên. Không triển khai các thay đổi backend local khác hoặc chạy migration mới trong lượt này.
- Sao lưu Caddy/env trước mỗi lần thay đổi; lỗi ở lượt lấy danh sách IP đã khôi phục cấu hình trước đó. Cấu hình thành công hiện tại: backup gốc `/var/lib/dictalab/backups/cloudflare-yz2e9isl`, backup trước đổi env `/var/lib/dictalab/backups/cloudflare-51a99ufi`, backup trước sửa Host `/var/lib/dictalab/backups/cloudflare-t6japwii`.

## Kiểm tra thực tế

| Hạng mục | Kết quả |
| --- | --- |
| HTTPS Cloudflare và HTTPS máy chủ gốc | Đạt |
| HTML so với VPS | SHA256 giống nhau: `982aa914c91d19514bcf1e95eb42fb8e9e671ef9b138b47bb8871190363fe53f` |
| Đăng ký, đăng nhập email, refresh, logout | Đạt |
| Cookie Secure/HttpOnly, API riêng no-store | Đạt |
| POST thiếu CSRF | Bị chặn 403 |
| Catalog, history, dashboard | Trả 200 khi đã đăng nhập |
| Link mời phòng | Dùng hostname Cloudflare |
| Hai tài khoản tạo/join/approve phòng | Đạt |
| WebSocket hai thành viên truyền mic-state | Đạt |
| WebSocket không đăng nhập hoặc sai Origin | Bị chặn 401/403 |
| Audio Range bytes=0-255 | 206, đúng 256 byte và Content-Range |
| Giao diện Chrome chính | Mở catalog, lịch sử, nghe hiểu và phòng học khi đã đăng nhập |
| Chuyển hướng Netlify | 302 tới Cloudflare, giữ đường dẫn |

Tài khoản thử đều được đăng xuất và xóa theo email ngẫu nhiên cùng tên/role cố định. Phòng thử đã đóng. Không bắt đầu bài học hoặc tạo lịch sử học. Lỗi JavaScript được thu trong Chrome thuộc tiện ích ví tiền `chrome-extension://...`, không phải script DictaLab.

Kiểm tra có thể chạy lại:

```powershell
node artifacts/cloudflare-proxy/check-worker.mjs
python artifacts/cloudflare-proxy/check-live.py
```

Kiểm tra live tạo hai tài khoản/phòng tạm và tự dọn. `cleanup-browser.py` là thao tác một lần đã hoàn tất, không chạy lại.

## Giới hạn và việc còn lại

- Workers Free giới hạn 100.000 request/ngày và 10 ms CPU/request. Cloudflare khuyến nghị tên miền riêng cho ứng dụng production quan trọng; `workers.dev` dành cho dự án cá nhân/hobby.
- Hostname máy chủ gốc phụ thuộc thêm vào DNS miễn phí sslip.io. Nếu đổi IP VPS phải cập nhật hostname và Worker; tên miền riêng sẽ bỏ phụ thuộc này.
- Đã kiểm tra tín hiệu WebSocket giữa hai thành viên; chưa kiểm tra một cuộc gọi microphone/WebRTC giữa hai thiết bị thật.
- Đăng nhập Google chưa deploy trên VPS; xem báo cáo Google login. Việc lưu origin trong Google Cloud chưa đồng nghĩa đã đăng nhập Google thành công.
- Kiểm tra UTF-8 cho các báo cáo và tệp triển khai: đạt.

Nguồn chính thức:

- https://developers.cloudflare.com/workers/configuration/routing/workers-dev/
- https://developers.cloudflare.com/workers/runtime-apis/websockets/
- https://developers.cloudflare.com/workers/platform/limits/
- https://developers.cloudflare.com/workers/platform/known-issues/#fetch-to-ip-addresses
- https://www.cloudflare.com/ips-v4/
- https://www.cloudflare.com/ips-v6/
- https://caddyserver.com/docs/caddyfile/directives/reverse_proxy
- https://nip.io/
