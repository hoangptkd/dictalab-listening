# Kiểm tra quản lý Gemini — 02/11/2026

## Phạm vi và kết luận

Rà soát màn `/admin/gemini`, Core proxy, API quản trị, PostgreSQL, quota Redis,
xoay nguồn 429, snapshot cấu hình, thống kê và audit. Đã sửa các vấn đề dưới đây.
Không dùng dữ liệu/key thật và không triển khai production trong lần kiểm tra này.

## Các vấn đề đã sửa

| Vấn đề | Hành vi sau sửa |
|---|---|
| Thêm nguồn với ID đã tồn tại có thể ghi đè cấu hình/key cũ | POST chỉ tạo; PUT chỉ sửa nguồn còn tồn tại; trùng ID trả 409 |
| Header actor quá dài hoặc DSN có port sai gây lỗi không được xử lý | Trả 400/503 an toàn; không đưa dữ liệu đầu vào vào thông báo |
| Có thể mở form/làm mới khi thao tác lưu chưa xong | Khóa các điều khiển trong màn cho đến khi xác nhận kết quả |
| Lưu thành công nhưng GET tải lại lỗi khiến người dùng không biết đã lưu | Giữ thông báo đã lưu và cung cấp nút tải lại; không tự gửi lại thao tác ghi |
| Không đọc trạng thái quota của nguồn đã tắt/nhóm tạm dừng | Đọc bộ đếm cho cả nguồn đã tắt |
| Provider dùng lại có thể giữ token của lần gọi trước khi lần sau lỗi | Xóa số liệu token trước mỗi lần gọi |
| Audit từ CLI hiển thị sai mảng ID, trạng thái còn hiện true/false | Hiện danh sách ID và nhãn tiếng Việt; phân biệt thêm/thay key |
| Nguồn đã xóa không còn trong bộ lọc dù thống kê vẫn tồn tại | Giữ bộ lọc theo nguồn lịch sử, có nhãn “Đã xóa” |

## Hoàn thiện vòng đời quản lý

- Tạo nhóm Gemini từ UI, đặt/đổi model mặc định, hạn mức chung và tạm dừng nhóm.
- Thêm/sửa nguồn, thay key, bật/tắt, thứ tự, giới hạn RPM/TPM/RPD và kiểm tra kết nối.
- Xóa nguồn đã tắt sau xác nhận rõ việc không khôi phục được key; giữ audit và usage.
- Cảnh báo model mặc định chưa có nguồn đang bật; kiểm tra RPM vận hành không vượt RPM tối đa.
- Hiện hạn mức đã cấu hình và token dự trù cùng bộ đếm, tách khỏi token thực tế.
- Chống xung đột giữa các admin và bảo vệ bản nháp khi chuyển giữa form nguồn/nhóm.
- Giới hạn chờ trên UI; phản hồi thao tác nằm gần các điều khiển chính.

Model mặc định áp dụng cho workflow dùng mặc định. Tác vụ yêu cầu một model cụ thể
vẫn chỉ chọn nguồn đúng model đó; không tự đổi model khi gặp 429. Lần kiểm tra
kết nối chỉ đọc danh sách model, không gửi yêu cầu sinh nội dung hoặc xác nhận
quota/hóa đơn của Google.

## Kiểm thử và bằng chứng

- **419 ca Python đạt**, chạy cùng PostgreSQL và Redis dùng riêng cho test.
- **73 ca Java đạt, 1 ca bỏ qua** do thiếu môi trường registry Java riêng;
  PostgreSQL thực của API quản trị được kiểm tra bằng các ca Python ở trên.
- **6 ca JavaScript đạt**; kiểm tra cú pháp JS và UTF-8 đạt.
- Ghi nhận runtime cho thấy **20/20 hàm trong `provider_admin.py` đã được gọi**.
  Đây là độ phủ hàm; không phải cam kết đã thử mọi tổ hợp dữ liệu/nhánh.
  Bằng chứng cục bộ: `target/gemini-audit/functions.json`.
- Chrome thật, phiên riêng: **16 nhóm tình huống đạt**, desktop 1440px và mobile 375px.
  Không tràn ngang trang ở mobile và không có lỗi JavaScript chưa xử lý.
  Ảnh cục bộ: `target/gemini-audit/desktop.png`, `target/gemini-audit/mobile.png`.

### Đối chiếu các hàm quản trị

| Nhóm hàm | Những đường đi đã kiểm tra |
|---|---|
| `authorize`, `target` | Token sai, sai loại token, actor không hợp lệ/quá dài, cấu hình thiếu/sai DB/port |
| `initialize_management` | Khởi tạo, chạy lại không đổi v1, checksum sai |
| `edit_source`, `audit` | Tạo/sửa/đổi key/project/model, validation, trùng ID/project-model, revision cũ, hai người cùng lưu, rollback khi ghi audit lỗi |
| `edit_pool` | Tạo nhóm, đổi model/hạn mức, tạm dừng, snapshot cũ/mới |
| `delete_source` | Chặn nguồn đang bật/revision cũ, xóa nguồn đã tắt và giữ lịch sử/thống kê |
| `snapshot` | Che key, quota nguồn đã tắt, ngày/tháng, lọc nguồn, 50 sự kiện/trang, kỳ lưu 90 ngày |
| `record_attempt`, `finish_request`, `cleanup_usage` | Token thực/thiếu token, một yêu cầu nhiều lần thử, kết thúc yêu cầu sau 429, role không có quyền ghi, dọn dữ liệu cũ |
| `get_sources`, `create_source`, `put_source`, `write_source`, `remove_source`, `put_pool` | Qua HTTP thật trong TestClient: thành công, validation, conflict, 404, lỗi DB đã làm sạch thông báo |
| `probe_source`, `load`, `save` | Model có/không có, HTTP 401/429, timeout, nguồn không tồn tại; endpoint cố định, không lộ key, có audit |

Các ca liên quan còn kiểm tra chỉ chuyển nguồn sau 429, mỗi nguồn một lượt,
giữ model/deadline, không thử nguồn khác sau timeout/5xx, giữ Retry-After qua
Core, và thay key chỉ ảnh hưởng workflow mới. Các cập nhật CLI tăng revision,
ghi audit và không đưa key vào audit.

### 16 nhóm kiểm tra Chrome

1. Màn chưa có nguồn.
2. Tạo nhóm khi chưa có nhóm Gemini.
3. Thêm nguồn, validation, chống chèn HTML và che key.
4. Từ chối trùng mã nguồn.
5. Sửa giữ key cũ, khóa thao tác trong lúc lưu.
6. Hủy/giữ thay đổi chưa lưu.
7. Hai form nguồn/nhóm không làm mất bản nháp của nhau.
8. Xung đột revision.
9. Lưu thành công, tải lại lỗi và thử tải lại.
10. Kiểm tra kết nối thành công/429.
11. Đổi model mặc định, hạn mức và tạm dừng nhóm.
12. Bảng token thực tế và biểu đồ tháng.
13. Chặn xóa nguồn đang bật, hủy/xác nhận xóa nguồn đã tắt, giữ lịch sử.
14. Lọc thống kê nguồn đã xóa.
15. Xem audit cũ/mới nhất.
16. Hiển thị danh sách nguồn trong audit CLI.

## Chạy lại

Kiểm tra giao diện tự tạo và dọn PostgreSQL riêng ở cổng 15549, server ở 18089,
Chrome dùng profile riêng. Cần Docker đang chạy, Python test dependencies và
`agent-browser` đã được cài. Không sử dụng DB hay đăng nhập thật.

```powershell
python tools/ai-eval/gemini_admin_ui_smoke.py --headed
node --test src/test/js/admin-regressions.test.cjs
```

Có thể đặt `GEMINI_QA_CHROME` nếu Chrome ở đường dẫn khác. Các ca DB trong
`test_provider_admin.py` chạy khi có `TEST_PROVIDER_ADMIN_DSN` trỏ đến PostgreSQL
test tại `127.0.0.1:15549`; chúng tạo/xóa các database/role riêng. CI đã được
bổ sung service PostgreSQL và chạy các kiểm thử Core quản trị.

## Giới hạn còn lại

Chưa gọi Gemini bằng key thật, chưa kiểm tra số quota Google báo và chưa deploy.
Ứng dụng thật cần migration/token/DSN và các quyền manager cập nhật theo
`docs/ai-provider-database.md`. Quyền bổ sung chỉ cấp cho manager; runtime giữ
quyền đọc, telemetry không được đọc key, manager không được sửa/xóa audit.
