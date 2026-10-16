# payOS và Premium

## Quyền lợi ban đầu

- Premium: 49.000 VNĐ cho 30 ngày; mua thêm cộng dồn từ ngày hết hạn còn hiệu lực, hoặc từ lúc xác nhận thanh toán nếu đã hết hạn.
- Miễn phí: tối đa 3 phiên luyện mới/ngày, gộp Dictation, Listening và bài học chung. Tiếp tục phiên Dictation đang dở không trừ thêm lượt.
- Premium: luyện không giới hạn, tối đa 10 lượt gọi AI tạo/phân tích mới/ngày. Các lần gọi sửa kết quả AI cũng tính vào hạn mức; phát audio không trừ thêm lượt AI. Giới hạn chi phí AI toàn hệ thống vẫn được giữ.
- Đọc lại lịch sử, báo cáo đã tạo, tiến trình và dữ liệu học không bị xóa khi hết hạn. Quản trị viên không bị giới hạn theo gói.
- Hạn mức theo ngày Việt Nam (UTC+7), đặt lại lúc 00:00. Tài khoản người dùng hiện tại bắt đầu ở gói miễn phí khi bật tính năng.
- Khi chưa bật thanh toán, hạn mức Premium chưa áp dụng và website tiếp tục hoạt động như trước.

## Cấu hình an toàn

Tạo và xác thực tài khoản cá nhân payOS, liên kết ngân hàng cùng tên, sau đó tạo kênh thanh toán. Lưu các giá trị sau vào cấu hình runtime được bảo vệ của máy chủ, không đưa vào Git, JavaScript, câu lệnh hoặc log:

```dotenv
DICTALAB_BILLING_ENABLED=false
DICTALAB_BILLING_PUBLIC_URL=https://your-public-domain.example
PAYOS_CLIENT_ID=
PAYOS_API_KEY=
PAYOS_CHECKSUM_KEY=
DICTALAB_PREMIUM_PRICE_VND=49000
DICTALAB_PREMIUM_DURATION_DAYS=30
DICTALAB_FREE_DAILY_LESSONS=3
DICTALAB_PREMIUM_DAILY_AI=10
```

`DICTALAB_BILLING_PUBLIC_URL` là origin HTTPS công khai, không có dấu `/` cuối hoặc query. Không lấy origin từ header do trình duyệt gửi. Đổi giá hoặc quyền lợi bằng cấu hình và khởi động lại ứng dụng. Đơn đã tạo giữ nguyên số tiền và số ngày tại thời điểm tạo; thay giá không thay các đơn cũ.

## Bật nhận tiền thật

1. Triển khai mã với `DICTALAB_BILLING_ENABLED=false`; kiểm tra migration V38, sao lưu và health bằng quy trình triển khai hiện có.
2. Hoàn thành xác minh tài khoản/ngân hàng và lưu đủ ba khóa kênh payOS vào runtime.
3. Đặt `DICTALAB_BILLING_ENABLED=true`, khởi động lại và cấu hình webhook của kênh tại `https://your-public-domain.example/api/billing/payos/webhook`. payOS gửi mẫu có chữ ký khi xác nhận URL; mẫu không khớp đơn được nhận nhưng không cấp Premium.
4. Dùng một tài khoản người học kiểm tra tạo đơn tại **Tài khoản → Premium**. Trang payOS hiển thị QR, số tiền và tài khoản nhận. Thực hiện giao dịch thật có giá trị đã được chủ tài khoản đồng ý; payOS hiện không có sandbox riêng.
5. Xác nhận webhook, đơn `PAID`, hạn Premium, audit `PREMIUM_PAYMENT`, gia hạn cộng dồn và không cộng lại khi gửi webhook lặp. Kiểm tra bằng nút **Kiểm tra thanh toán** để đối soát qua API payOS nếu webhook chậm.
6. Chỉ thông báo đã nhận tiền thật sau khi các bước trên thành công. Trước đó đây là phần tích hợp chưa kích hoạt.

## Đơn hàng và lỗi

- Đơn có thời hạn 15 phút. Mỗi người chỉ tái sử dụng một đơn đang chờ; nhấp lại không tạo một lần thu khác.
- Không tự lặp lệnh tạo đơn khi kết nối lỗi. Đơn `CREATING` được giữ để truy vấn cùng mã đơn qua **Kiểm tra thanh toán**. Không thanh toán thêm cho tới khi rõ kết quả.
- Chỉ chữ ký HMAC hợp lệ và giao dịch đúng mã đơn, mã link, số tiền, VND mới cấp quyền; URL quay về không có quyền xác nhận.
- Đơn thanh toán đến muộn vẫn được đối soát và cộng hạn nếu payOS xác nhận hợp lệ. Dữ liệu giao dịch, mã tham chiếu duy nhất và cập nhật hạn nằm trong cùng transaction.
- Khi xử lý yêu cầu xóa tài khoản, hạn Premium và hạn mức bị xóa; đơn thanh toán được tách khỏi tài khoản để giữ đối soát. Thông báo đến muộn chỉ cập nhật giao dịch, không tạo lại tài khoản hoặc quyền học.
- Không tự động hoàn tiền, cấp khuyến mại, hoặc thu định kỳ. Sai số tiền và giao dịch không khớp phải được kiểm tra thủ công trên payOS; không sửa thành `PAID` bằng tay để bỏ kiểm tra.
- Tắt `DICTALAB_BILLING_ENABLED` sẽ dừng tạo/kiểm tra đơn và áp hạn mức, webhook trả lỗi tạm thời. Giữ ứng dụng và dữ liệu để payOS có thể gửi lại sau khi bật. Không xóa bảng thanh toán để quay lui.

## Kiểm tra chạy lại

```powershell
mvn --batch-mode --no-transfer-progress '-Dtest=BillingIntegrationTest,PayOsClientTest,RequestRateLimitFilterTest' test
node --test src/test/js/billing.test.cjs
```

Tài liệu chính thức: [API](https://payos.vn/docs/api/), [chữ ký](https://payos.vn/docs/tich-hop-webhook/kiem-tra-du-lieu-voi-signature/), [môi trường thử nghiệm](https://payos.vn/docs/moi-truong-test/).
