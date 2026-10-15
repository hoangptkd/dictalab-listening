# Gmail SMTP production — 11/10/2026

## Kết quả

- Đã bật Gmail SMTP trên VPS `184.194.89.90`: `smtp.gmail.com:587`, STARTTLS,
  bắt buộc TLS và kiểm tra danh tính máy chủ. Sender: `hoangptkdhy@gmail.com`.
- App Password riêng `DictaLab production SMTP`; chuyển qua SSH stdin vào
  `/etc/dictalab/app.env` (`640 root:dictalab`), không đưa mật khẩu vào Git/báo cáo.
- Tái sử dụng luồng email outbox/token hiện có; không đổi Java, schema, pool,
  queue flags hay chính sách bắt buộc xác minh email. Không mua dịch vụ/domain.
- Trước khi bật: hàng đợi thư còn hiệu lực bằng 0; SMTP TLS/auth thành công;
  dừng ứng dụng và chạy backup quiescent trước khi thay cấu hình.
- Backup cấu hình: `/var/lib/dictalab/backups/smtp-3xb06wlb/app.env`.
  Helper có nhánh rollback nếu activation thất bại; không kích hoạt rollback thật.

## Nghiệm thu production

Chỉ tạo và thay đổi một tài khoản USER thử nghiệm, ID `123`, địa chỉ Gmail plus-alias
thuộc chủ dự án. Không reset mật khẩu tài khoản thật, không xóa dữ liệu.

| Kiểm tra | Kết quả |
|---|---|
| Thư xác minh thực tế | Vào Gmail Inbox, 18:25 UTC+7 |
| Thư reset thực tế | Vào Gmail Inbox, 18:26 UTC+7 |
| Xác minh bằng token lấy từ email | PASS; trạng thái `emailVerified=true` |
| Token xác minh dùng sai mục đích / dùng lại | HTTP 400, PASS |
| Reset bằng token lấy từ email | PASS |
| Mật khẩu mới quá yếu / token reset dùng lại | HTTP 400, PASS |
| Phiên đăng nhập cũ / mật khẩu cũ sau reset | HTTP 401, PASS |
| Đăng nhập mật khẩu mới và đăng xuất | PASS |

22/22 HTTP checks đạt, gồm cả CSRF và các lần từ chối dự kiến. Hai helper cấu hình/
nghiệm thu đều qua self-test; Python compile đạt. Token được lấy từ thư thật trong
Chrome, rồi nghiệm thu qua API production; không phải kiểm thử toàn bộ form trình duyệt.

Đọc DB sau kiểm thử: hai outbox `DELIVERED`, mỗi thư một attempt, không có last_error,
body đã xóa khỏi outbox, hai token đã dùng. Thời gian từ tạo token đến SMTP chấp nhận:
xác minh **1,975 giây**, reset **2,364 giây**. Đây không phải thời gian đo đến Inbox.
TTL trong DB là 1.800 giây; chưa chờ 30 phút để thử token hết hạn trên production.

## Trạng thái cuối

- HTTPS public và health nội bộ: `UP`; PID `91430`, `NRestarts=0` sau lần khởi động
  lại có chủ đích. Không diễn giải giá trị này là ứng dụng chưa từng restart.
- JAR giữ nguyên SHA-256:
  `eb3b0587efb9c696f08ed08f9834ee742e6dc979ccd5657f2f77eb8c53fb7d81`.
- Environment SHA-256:
  `a0fd1e833288a206baaff3b6122ce6c2c1d84da1e8051b0ab35a88b83b61a08a`.
- Migration vẫn V28; sampled idle-in-transaction và lock waiters bằng 0.
- Evidence: `target/smtp-acceptance-20261011/requests.jsonl`,
  `database-evidence.json`, `final-check.log`. Runtime test credentials/cookies ở
  thư mục `private` có ACL giới hạn; không công bố thư mục này.
- Không commit/push. Báo cáo qua kiểm tra UTF-8; Ponytail giữ thay đổi ở cấu hình,
  không viết lại luồng gửi email đang có.

## Giới hạn còn lại

Gmail cá nhân có quota/chống lạm dụng; không coi là SMTP không giới hạn hoặc SLA.
Mới xác minh gửi vào Gmail của chủ dự án, chưa chứng minh deliverability tới các
nhà cung cấp khác, gửi tải lớn, Gmail outage hoặc crash giữa SMTP acceptance/DB commit.
Outbox vẫn at-least-once: crash tại ranh giới này có thể tạo thư trùng.

Mailtrap/domain sender riêng và chính sách bắt buộc xác minh trước khi dùng tính năng
vẫn chưa hoàn tất. Không nâng toàn bộ checklist email/public-launch thành DONE.

Nguồn giới hạn dịch vụ: [Google — giới hạn gửi Gmail](https://support.google.com/mail/answer/22839),
[Google — App Password](https://support.google.com/mail/answer/185833).
