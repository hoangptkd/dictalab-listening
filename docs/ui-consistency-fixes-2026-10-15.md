# DictaLab — Sửa các lỗi giao diện đã duyệt

Ngày kiểm tra: 2026-10-15. Phạm vi: 7 mục trong báo cáo audit toàn hệ thống, kèm lỗi bộ lọc hỗ trợ phát hiện khi kiểm thử thực tế. Giữ các component bo tròn và chức năng hiện có.

## Thay đổi

| Mục | Kết quả |
|---|---|
| Sidebar/header quản trị | Dùng chung sidebar 240px ở desktop, 220px ở tablet và header 57px. Loại bỏ CSS ghi đè của Người dùng, Nghe hiểu, Hỗ trợ. Mobile dùng header, navigation và nội dung theo ba hàng. |
| Danh sách hỗ trợ sau lưu | Tải lại bảng, tổng số và pager theo bộ lọc hiện tại. Giữ form chi tiết và các phần chỉnh sửa khác đang mở; cập nhật version và lịch sử xử lý. Thông báo riêng nếu phản hồi đã lưu nhưng tải lại bảng thất bại. |
| Pager lịch sử nghe hiểu | Dùng `pagination` và `text-button` hiện có; giữ trạng thái disabled và hướng chuyển trang. |
| Navigation thư viện quản trị | Active “Độ khó và nhóm bài” theo `library=1` hoặc `#library-management`, có `aria-current="location"`. Các màn quản trị khác và điều hướng người học vẫn chọn đúng mục. |
| Nút Kho lỗi | Vùng bấm tối thiểu 44px, dùng màu và khung bo tròn hiện có. Luồng mở panel giữ nguyên. |
| Nhãn loại câu hỏi | Editor, báo cáo và gợi ý dùng chung Ý chính / Chi tiết / Suy luận / Ý định người nói. Loại chưa biết có nhãn dự phòng. Enum gửi API không đổi. |
| Câu đánh dấu | Hiển thị thứ tự trong transcript thay cho ID cơ sở dữ liệu. ID gửi API giữ nguyên. Câu không có trong payload được ghi rõ, không tự suy ra thứ tự. |

### Lỗi liên quan ở bộ lọc hỗ trợ

Spring áp dụng `defaultValue="OPEN"` cho cả tham số rỗng, khiến “Tất cả” thực tế chỉ lấy yêu cầu chờ xử lý. URL cũng xóa `status=` khi đổi trang hoặc mở chi tiết.

Đã phân biệt tham số bị thiếu (mặc định OPEN) với tham số rỗng (tất cả), và giữ `status=` trong URL hỗ trợ. Trạng thái không hợp lệ vẫn trả HTTP 400; quyền quản trị, CSRF và kiểm tra version vẫn được giữ.

## Kiểm tra

- JavaScript: **238/238** qua, gồm 7 test mới cho active navigation, URL bộ lọc, nhãn câu hỏi, ordinal/ID, pager, lưu phản hồi liên tiếp và lỗi refetch.
- Java/PostgreSQL 18.4: **38/38** qua trong `AdminWorkflowIntegrationTest`, `AccountAndSupportIntegrationTest`, `SpaRoutingTest`. Build executable JAR thành công.
- Bản thử được build từ source đã commit cộng các file thuộc lần sửa này, trong thư mục riêng. Code chưa commit của chat khác được giữ tại workspace.
- **42 lượt kiểm tra bố cục render thật**, có ảnh và số đo trong [geometry.json](ui-consistency-fixes-2026-10-15-assets/geometry.json):
  - 7 màn quản trị × 4 widths: 1440, 1024, 390, 320px.
  - 2 trạng thái lưu hỗ trợ tại 1440px.
  - Dashboard/Kho lỗi, lịch sử nghe hiểu, báo cáo, review × 3 widths: 1440, 390, 320px.
- Các màn trên không tràn ngang. Sidebar desktop/tablet và header có số đo thống nhất; pager và nút Kho lỗi đạt chiều cao tối thiểu 44px.
- Trình duyệt: lưu ticket thử OPEN → IN_PROGRESS, lưu tiếp trong khi lọc OPEN, giữ chi tiết, dòng biến mất khỏi bảng đúng bộ lọc; tải lại với “Tất cả” vẫn thấy trạng thái và phản hồi mới. Mở Kho lỗi, vào review và POST đánh dấu dùng ID thật 316; màn hình hiển thị Câu 1.
- UTF‑8 và `git diff --check` đạt. Tất cả thao tác tạo/sửa dữ liệu trong lần kiểm tra này dùng tài khoản giả tại loopback; không gửi dữ liệu thử lên production.

Các ảnh đánh dấu có nhiều dòng Câu 1 vì fixture được đánh dấu nhiều lần trong quá trình kiểm tra; API hiện lưu từng dấu. Lần sửa này chỉ thay cách hiển thị thứ tự.

### Chạy lại test

```powershell
node --test src/test/js/*.cjs
# Cấu hình TEST_DB_URL, TEST_DB_USERNAME, TEST_DB_PASSWORD cho PostgreSQL thử trước.
mvn --batch-mode --no-transfer-progress '-Dspring.profiles.active=postgres-test' '-Dtest=AdminWorkflowIntegrationTest,AccountAndSupportIntegrationTest,SpaRoutingTest' test
```

## Ảnh minh chứng

| Nội dung | Ảnh |
|---|---|
| Sidebar Người dùng / Hỗ trợ | [Người dùng desktop](ui-consistency-fixes-2026-10-15-assets/admin-users-1440.png), [Hỗ trợ desktop](ui-consistency-fixes-2026-10-15-assets/admin-support-1440.png), [Hỗ trợ mobile](ui-consistency-fixes-2026-10-15-assets/admin-support-320.png) |
| Active đúng phần thư viện | [Thư viện mobile](ui-consistency-fixes-2026-10-15-assets/admin-library-390.png) |
| Lưu hỗ trợ trong bộ lọc OPEN | [Danh sách và chi tiết](ui-consistency-fixes-2026-10-15-assets/support-save-filtered-1440.png) |
| Vùng bấm Kho lỗi | [Dashboard 320px](ui-consistency-fixes-2026-10-15-assets/dashboard-bank-320.png) |
| Pager dùng component chung | [Lịch sử 390px](ui-consistency-fixes-2026-10-15-assets/listening-history-pager-390.png) |
| Nhãn tiếng Việt / thứ tự câu | [Báo cáo](ui-consistency-fixes-2026-10-15-assets/listening-report-labels-390.png), [Đánh dấu](ui-consistency-fixes-2026-10-15-assets/listening-review-marks-390.png) |

## Giới hạn và trạng thái bàn giao

H2 bị chặn từ migration V33 đã có trên branch (`SET LOCAL lock_timeout` yêu cầu PostgreSQL), trước khi các test chạy. Cần xử lý tính tương thích migration/test trước khi merge main: workflow hiện vẫn chạy H2. Kết quả Java ở trên là trên PostgreSQL, không phải H2.

Đợt này xác minh các sửa đổi được duyệt; không xác nhận mọi nhánh AI, Google OAuth, email và phòng hai người/WebRTC trong audit đã hoàn tất. Không tuyên bố pixel 100% hay toàn bộ chức năng 100%.

Bàn giao bằng commit/push lên branch `codex/dictalab-ui-release-20261014`. Workflow trong repository chỉ tự chạy khi push main hoặc PR vào main; push branch này không tự deploy VPS. Không có rollout production trong lần bàn giao này.
