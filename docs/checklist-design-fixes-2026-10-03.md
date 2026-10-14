# Các sửa đổi sau kiểm tra Checklist Design — 03/10/2026

Đã sửa và triển khai lên website ngày 03/10/2026. Dịch vụ đang chạy, HTTPS health trả UP; các tệp giao diện khớp bản phát hành đã kiểm tra. Chi tiết tại [báo cáo triển khai](checklist-design-deployment-2026-10-03.md).

## Đã sửa

| Màn hình | Thay đổi |
| --- | --- |
| Đăng nhập, đăng ký, đặt lại mật khẩu, tài khoản | Thêm nút hiện/ẩn mật khẩu có nhãn, trạng thái và liên kết tới từng ô nhập; sử dụng được bằng bàn phím. Giữ nguyên giá trị, tự động điền và kiểm tra mật khẩu. |
| Đăng nhập | Đổi lời nhắc thành “Nhập mật khẩu của bạn”; đưa “Quên mật khẩu?” sát trường mật khẩu. |
| Đặt lại mật khẩu | Bổ sung yêu cầu mật khẩu mới và liên kết lời nhắc với ô nhập. Sau thành công, nút gửi giữ trạng thái khóa. |
| Dashboard | Đưa bài gợi ý lên trước thống kê; làm gọn tiêu đề và thẻ trên điện thoại; ghi rõ thống kê toàn bộ thời gian của các buổi chép chính tả cá nhân đã hoàn thành. |
| Biểu đồ | Hiển thị ngày/tháng từng buổi; sửa mô tả “đường” thành “cột”; biểu đồ cuộn riêng trên màn hình hẹp. |
| Tài khoản | “Lưu tên” chỉ bật khi tên thay đổi; tiếp tục khóa sau khi lưu thành công; giữ khả năng thử lại nếu gửi thất bại. |
| Xóa tài khoản | Liên kết mang loại ACCOUNT_DELETE. Sửa điều hướng để giữ tham số này và chọn sẵn đúng loại trong biểu mẫu hỗ trợ. |
| Hỗ trợ | Nêu nơi xem phản hồi, không hứa thời hạn xử lý chưa được quy định; bổ sung hướng dẫn khi danh sách trống. |
| Lịch sử | Phân biệt chưa luyện với không có dữ liệu ở trình độ đang lọc; có nút “Xem tất cả trình độ”, giữ focus bàn phím và liên kết luyện đúng trình độ. |

## Kiểm chứng

- 34/34 kiểm tra hồi quy trong ui-regressions, history và admin-regressions vượt qua.
- Hai tệp JavaScript đã qua kiểm tra cú pháp.
- Kiểm tra UTF-8 vượt qua cho các tệp mã nguồn và kiểm tra đã sửa.
- Kiểm tra giao diện ở 1440×1000, 390×844 và 320×740 trên bản xem trước local dùng dữ liệu giả. Không gọi biểu mẫu ghi dữ liệu vào hệ thống thật.
- Đã xác nhận trực tiếp: hiện/ẩn mật khẩu bằng Enter; lưu tên trước/sau chỉnh sửa; liên kết xóa tài khoản giữ loại; bỏ lọc lịch sử và focus về tab “Tất cả”.
- Dashboard không tràn trang theo chiều ngang ở cả hai kích thước điện thoại đã kiểm tra. Nội dung biểu đồ có thể cuộn ngang trong khung.

Ảnh kiểm chứng:

- [Dashboard điện thoại](checklist-design-fixes-2026-10-03-assets/dashboard-mobile.png)
- [Dashboard máy tính và biểu đồ](checklist-design-fixes-2026-10-03-assets/dashboard-desktop.png)
- [Đăng nhập điện thoại](checklist-design-fixes-2026-10-03-assets/login-mobile.png)
- [Tài khoản](checklist-design-fixes-2026-10-03-assets/account-mobile.png)
- [Yêu cầu xóa tài khoản](checklist-design-fixes-2026-10-03-assets/support-delete-mobile.png)
- [Lịch sử theo trình độ](checklist-design-fixes-2026-10-03-assets/history-filter-mobile.png)

Đây là xác nhận các sửa đổi giao diện trong phạm vi báo cáo trước, không phải chứng nhận WCAG hoặc kiểm thử toàn bộ hệ thống. Các thay đổi khác có sẵn trong workspace được giữ nguyên.

## Đợt bổ sung: xử lý các mục còn lại

Đã tiếp tục sửa và kiểm tra trên backend thật ở localhost, dùng cơ sở dữ liệu H2 trong bộ nhớ và hai tài khoản thử. Không dùng cơ sở dữ liệu production; không đổi mật khẩu, xóa dữ liệu hoặc gửi yêu cầu hỗ trợ bằng tài khoản người dùng cung cấp.

| Mục trong báo cáo gốc | Xử lý và bằng chứng | Trạng thái |
| --- | --- | --- |
| Login — Email/password và Show/hide | Đổi placeholder; thêm hiện/ẩn; đã kiểm tra giữ giá trị, độc lập từng ô và bàn phím. | Đã sửa local |
| Login — Error messages | Tạo đăng nhập sai bằng tài khoản thử; thông báo “Email hoặc mật khẩu không đúng.”; nút gửi bật lại. AuthFlowIntegrationTest kiểm tra phản hồi backend. | Đã xác minh local |
| Sign up — Setting a password | Hiện/ẩn và quy tắc mật khẩu; AuthFlowIntegrationTest xác minh tạo phiên, dữ liệu riêng và không cấp admin chỉ bằng email allowlist. | Đã sửa/kiểm tra tự động |
| Reset — Link gần mật khẩu và account details | Đặt link gần trường mật khẩu; chuyển email đã nhập bằng sessionStorage rồi tiêu thụ một lần, có nhánh trình duyệt chặn storage; không đưa email vào URL. | Đã sửa và quan sát local |
| Reset — Show information has been sent | Phản hồi không tiết lộ email có tồn tại; khi SMTP chưa cấu hình, phản hồi lỗi dịch vụ. Kiểm tra outbox trong môi trường có mail sender giả. | Đã kiểm tra backend; email thật còn mở |
| Reset — Message explains next steps | Tiêu đề riêng cho đặt lại mật khẩu, hướng dẫn 8 ký tự/chữ/số, hạn 30 phút, đăng nhập lại và bỏ qua nếu không yêu cầu. ProductionReliabilityTest kiểm tra nội dung và hạn token. | Đã sửa/kiểm tra tự động |
| Reset — Reset password và success | Ô nhập mới có hướng dẫn/hiện mật khẩu; trả thông báo thành công và link về đăng nhập. Kiểm tra token hết hạn, dùng lại, đổi hash và thu hồi phiên cũ trên tài khoản thử. | Đã kiểm tra backend và giao diện theo nguồn; chưa thử bằng email thật |
| Dashboard — Welcome state | So sánh lần đầu của buổi gần nhất với buổi trước cùng trình độ trong tối đa 8 buổi; nêu bài có thể khác độ khó; không suy thành thay đổi từ lần truy cập. | Đã sửa và quan sát local |
| Dashboard — Key metrics và Quick actions | Ghi kỳ toàn bộ thời gian; bài gợi ý nằm trước thống kê; nút nằm trong vùng đầu trang điện thoại. | Đã sửa và quan sát local |
| Dashboard — Recent activity | Ba buổi mới nhất, ngày, trình độ, độ đúng và link xem lại. Dùng truy vấn 8 buổi đã có, không thêm truy vấn riêng. Backend kiểm tra loại trừ phòng/khác chủ sở hữu. | Đã sửa/kiểm tra backend và giao diện |
| Dashboard — Empty state | Người học chưa có dữ liệu có lời giải thích và hành động chọn bài đầu tiên; biểu đồ báo chưa có dữ liệu. | Đã quan sát tài khoản thử ở 320 px |
| Account — Save confirmation | Nút khóa khi không đổi; lưu tên thật trên tài khoản thử có thông báo thành công và khóa lại. | Đã quan sát local |
| Account — Delete/deactivate | Chọn sẵn ACCOUNT_DELETE; bổ sung các bước xét yêu cầu, rời phòng, xác nhận email và hậu quả xóa. Backend kiểm tra xác nhận email và dọn dữ liệu trên tài khoản thử. | Đã sửa/kiểm tra local |
| Support — Expected communication | Giải thích phản hồi nằm trong “Yêu cầu của bạn”; chưa có SLA cố định; hướng dẫn gửi khi rỗng. Các kiểm tra hỗ trợ xác minh sở hữu, phân trang, phiên bản phản hồi. | Đã sửa/kiểm tra local |
| Filtering — Empty state | Tách rỗng theo trình độ và chưa học; hành động về Tất cả giữ focus. | Đã sửa và kiểm tra |
| Empty State — Heading/description/zero vs no-results | Sửa Lịch sử và Hỗ trợ theo từng ngữ cảnh. | Đã sửa và kiểm tra |
| Empty State — Error variant | Dashboard/Lịch sử có thông báo lỗi lưu trên trang và Thử lại. Dùng proxy local trả 503, khôi phục API rồi thử lại bằng Enter; focus về nội dung hoặc tab Tất cả. Lỗi không được trình bày thành rỗng. | Đã kiểm tra trực tiếp local |
| Card — Spacing | Các thẻ dùng khung hiện có: padding desktop 32 px, mobile 24×19 px; gap danh sách 12 px, nội dung 6 px. Giữ hệ quy tắc đang có và ghi nhận bằng chứng bố cục, không thêm một design system mới. | Đã đối chiếu nguồn; chưa có tiêu chuẩn spacing riêng do chủ sản phẩm phê duyệt |
| Admin — Role-based access | USER nhận trang 403, không có điều hướng quản trị. API thống kê và CSV chặn khách/USER. SpaRoutingTest và AdminWorkflowIntegrationTest kiểm tra các đường quản trị còn lại. | Đã quan sát/kiểm tra local |
| Admin — User management | Xác minh danh sách/lọc, khóa và bảo vệ admin cuối, quyền và yêu cầu xóa. Lời mời người dùng không có trong mô hình đăng ký cá nhân hiện tại. | Đã kiểm tra backend hiện có |
| Admin — Usage overview/export | Tổng quan 7/30/90 ngày, tài khoản hiện có/mới, buổi/phút chính tả cá nhân, buổi nghe hiểu hoàn thành, phiên hội thoại được tạo. CSV UTF-8 có BOM, không chứa email/tên cá nhân; kiểm tra kỳ không hợp lệ, trạng thái, thời gian và phân quyền. | Đã bổ sung/kiểm tra local |
| Chart — Mô tả và ngày | Cột cam/xanh thống nhất lời mô tả, có ngày từng buổi, cuộn trong khung. | Đã sửa và kiểm tra |
| Mobile More | Enter mở menu và điều hướng được; giữ kết luận trước rằng menu hoạt động. | Đã kiểm tra lại |
| Listening titles | Giữ tên trung tính theo thiết kế đánh giá. | Không cần sửa |

Các mục xanh của báo cáo gốc được giữ. Các mục trắng như SSO, avatar, social proof, billing, widget layout và organization settings không phải thiếu sót cần thêm trong sản phẩm hiện tại.

### Khả năng tiếp cận và bố cục

- Đã sửa màu chữ nhỏ ở số buổi, badge lịch sử điểm thấp và hover liên kết về lịch sử sang coral-deep. Coral trên cream chỉ đạt 3,11:1; coral-deep trên cream đạt 5,63:1 và trên paper 5,03:1.
- Chữ ink/ink-soft trên cream lần lượt 14,80:1 và 6,62:1. Lime trên ink đạt 11,57:1. Đây là phép đo các cặp token sử dụng, không phải quét mọi điểm ảnh và trạng thái.
- Focus dùng coral-deep trên nền sáng và lime ở các vùng tối; bổ sung summary vào nhóm focus. Kỳ thống kê quản trị giữ nguyên phần tử select, khôi phục focus sau tải; đã xác nhận bằng ArrowDown.
- Quan sát bản thử ở 1440×1000, 390×844, 320×740; Dashboard, tổng quan quản trị và hướng dẫn xóa không tràn chiều ngang trang. Menu và biểu đồ có vùng cuộn riêng.
- Không thể dùng cây accessibility của trình duyệt để kết luận trải nghiệm NVDA/VoiceOver, mọi thứ tự focus, mọi browser hay bàn phím ảo trên thiết bị thật.

### Kiểm chứng cuối

- JavaScript: **85/85 đạt**, gồm giao diện, lịch sử, quản trị, hội thoại và đồng bộ audio phòng.
- Maven: **160 kiểm tra được ghi nhận; 159 đạt, 1 bỏ qua**, không lỗi/thất bại. Mục bỏ qua là BackendBenchmarkTest cần bật riêng dictalab.benchmark.
- Các suite bao phủ: AuthFlowIntegrationTest, AccountAndSupportIntegrationTest, BackendReadIntegrationTest, AdminWorkflowIntegrationTest, SpaRoutingTest, ListeningComprehensionIntegrationTest, ConversationIntegrationTest, ProductionReliabilityTest và các kiểm tra phòng.
- Mã JavaScript qua kiểm tra cú pháp; các tệp có tiếng Việt đã qua kiểm tra UTF-8.
- AI/mail được kiểm tra bằng fixture hoặc mock. Không coi kết quả đó là xác nhận nhà cung cấp production hay SMTP thật.

Ảnh mới:

- [Tiến bộ có hoạt động gần đây](checklist-design-fixes-2026-10-03-assets/dashboard-recent-mobile.png)
- [Tiến bộ chưa có dữ liệu](checklist-design-fixes-2026-10-03-assets/dashboard-empty-320.png)
- [Tổng quan quản trị máy tính](checklist-design-fixes-2026-10-03-assets/admin-usage-desktop.png)
- [Tổng quan quản trị điện thoại](checklist-design-fixes-2026-10-03-assets/admin-usage-mobile.png)
- [Hướng dẫn xóa tài khoản](checklist-design-fixes-2026-10-03-assets/support-delete-guide-320.png)
- [USER bị từ chối quản trị](checklist-design-fixes-2026-10-03-assets/admin-user-denied.png)
- [Đăng nhập sai](checklist-design-fixes-2026-10-03-assets/login-error-320.png)
- [Điền lại email khôi phục](checklist-design-fixes-2026-10-03-assets/reset-prefill-320.png)
- [Lưu tên thành công](checklist-design-fixes-2026-10-03-assets/account-save-success-320.png)
- [Lỗi tải Lịch sử](checklist-design-fixes-2026-10-03-assets/history-load-error-320.png)
- [Lỗi tải Tiến bộ](checklist-design-fixes-2026-10-03-assets/dashboard-load-error-320.png)

### Phần vẫn cần môi trường thực tế

1. SMTP gửi tới hộp thư thử thật, mở liên kết và xác minh toàn bộ luồng đặt lại. Mã/outbox/token đã kiểm tra; chưa xác nhận khả năng gửi của VPS.
2. Phát/thu âm, mic và cuộc gọi giữa hai thiết bị thật, mạng khác nhau, autoplay và quyền trình duyệt; AI production. Kiểm tra fixture không thay thế các bước này.
3. Trình đọc màn hình thật, browser khác và bàn phím ảo. Chưa chứng nhận WCAG toàn sản phẩm.
4. Đã triển khai backend và frontend cùng phiên bản lên VPS; xác minh Dashboard, thống kê quản trị 7/30/90 ngày, CSV và quyền truy cập bằng HTTPS. Bản phát hành gồm các thay đổi reliability có sẵn, cập nhật cấu trúc dữ liệu V22 → V24; bản sao lưu và JAR trước được giữ lại. Kiểm tra giao diện production bằng trình duyệt còn chưa hoàn tất vì công cụ báo ERR_BLOCKED_BY_CLIENT.

**Kết luận:** website production đã nhận bản sửa và vượt qua các kiểm tra sau triển khai nêu trên. Các mục cần kiểm chứng thêm ở môi trường thực tế vẫn được ghi rõ; chưa kết luận “tất cả đã đạt chuẩn”.
