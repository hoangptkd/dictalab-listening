# Kết quả sửa trang quản trị — 30/09/2026

Phạm vi: sửa các lỗi và chuẩn hóa bốn màn trong [audit](admin-audit-2026-09-30.md). Giữ kiến trúc HTML/CSS/JS, Spring Boot và API cũ; không thêm thư viện. Chưa triển khai bản sửa này lên VPS.

## Đối chiếu các phát hiện

| ID | Kết quả sửa | Bằng chứng kiểm tra |
|---|---|---|
| A01 | Nút áp dụng và Enter tìm người dùng hoạt động, có thông báo khi thất bại | Test JS implicit submit; tìm không có kết quả trên trình duyệt |
| A02 | Khi bỏ lựa chọn trống, đáp án đúng được ánh xạ theo lựa chọn gốc; chặn đáp án trống/trùng và câu bắt buộc thiếu | `admin-regressions.test.cjs`; script tái hiện cũ |
| A03 | Cảnh báo rời trang/đóng trang/Back khi chưa lưu; giữ các trường đã sửa khi vùng Dictation được render lại; lọc danh sách không tải lại editor đang mở | Test dirty capture/restore; giữ tiêu đề khi lọc; hủy điều hướng giữ nguyên nội dung trên trình duyệt |
| A04 | Lỗi tải danh sách thay kết quả cũ bằng thông báo và nút thử lại; bỏ qua phản hồi cũ sau đổi màn | Test JS; chặn request users rồi bỏ chặn và thử lại thành công |
| A05 | Lọc kết hợp, sort, xóa lọc, URL và phân trang máy chủ cho cả bốn màn | Integration test quyền/filter/LIKE literal/page clamp; lọc và page trên trình duyệt |
| A06 | Toolbar, control, bảng/card mobile, nhãn trạng thái và panel chi tiết thống nhất | Ảnh desktop/mobile trong `admin-fix-assets` |
| A07 | Tên truy cập cho tài khoản, câu transcript, nút thứ tự; trạng thái, focus và tương phản admin được điều chỉnh | Axe không báo violation trên mẫu đã kiểm sau khi animation kết thúc; vẫn có mục cần kiểm thủ công ở danh sách Dictation cuộn |
| A08 | Empty state có hướng dẫn; pager có tổng/trang/trước/sau; chuẩn hóa trang vượt giới hạn | Test máy chủ và trình duyệt |
| A09 | Chi tiết Listening có audio, transcript theo đoạn, đáp án/giải thích; bản phát hành chỉ đọc; copy thành nháp; bỏ nháp không dùng | Trình duyệt copy → lưu → xuất bản; test archive giải phóng nguồn |
| A10 | Trang chi tiết tài khoản, thu hồi phiên có lý do, nhật ký có thời gian; ticket có người gửi/ngữ cảnh/ngày giờ/lịch sử phản hồi | Test history/actor; trình duyệt lưu phản hồi và mở chi tiết tài khoản |
| A11 | Dictation kiểm tra thời điểm cập nhật; Listening/ticket kiểm tra version và khóa hàng; khóa control khi gửi; tạo nháp cùng nguồn được tuần tự hóa | Test form cũ nhận 409, không ghi đè; test actor đúng |
| A12 | Endpoint page trả tối đa 20 mục; lọc/count/sort/page ở DB; danh sách Listening không trả `content_json` | Integration test payload và page; chưa benchmark dữ liệu production |

## Bộ lọc đã có

| Màn | Điều kiện |
|---|---|
| Dictation | Tên/mã; level; nháp/xuất bản; chủ đề; nguồn; cập nhật/tên |
| Listening | Tên/ID; level; nháp/xuất bản/ngừng; bài nguồn; mới nhất/tên |
| Người dùng | Tên/email; quyền; hoạt động/khóa; xác minh email; đăng ký/đăng nhập/tên |
| Hỗ trợ | ID/tiêu đề/email; trạng thái; loại vấn đề; loại bài; ngày tạo từ/đến; cũ/mới |

Các điều kiện kết hợp bằng AND. Đổi bộ lọc về trang đầu, reload/Back giữ URL, server kiểm tra tham số và sort theo danh sách cho phép. API mảng cũ vẫn giữ để tương thích; giao diện admin dùng API page mới.

## Bảo toàn dữ liệu

- Dictation đã có phiên học hoặc được dùng làm nguồn Listening khóa transcript/audio/thứ tự; có nút tạo bản sửa mới. Bài gốc và lịch sử được giữ.
- Bản sao giữ cùng định danh audio để việc đã từng nghe không bị tính thành benchmark mới. Thay audio tạo file mới; chỉ dọn file khi không còn câu/bài nghe hiểu tham chiếu. Có integration test kiểm tra file qua nhiều câu và hai bài.
- Listening đã phát hành chỉ đọc; sửa qua bản nháp. Ngừng phát hành/bỏ nháp giữ bản ghi lịch sử.
- Version cũ nhận lỗi 409. Header version của nội dung vẫn tùy chọn cho tích hợp API cũ; giao diện admin gửi version. Không lưu bản soạn nhạy cảm vào localStorage.
- Nhật ký nội dung/hỗ trợ ghi người thao tác, thời điểm, đối tượng và hành động. Lịch sử phản hồi cũ có sẵn được giữ khi xử lý lần đầu.

## Kiểm tra đã chạy

- `mvn test -q`: **92 test, 0 failure/error/skipped**, H2 chế độ PostgreSQL.
- `node --test src/test/js/*.test.cjs`: **28 test đạt**; gồm hồi quy admin, điều hướng, Listening và cuộc gọi.
- Kiểm tra UTF-8 tiếng Việt và diff không có lỗi khoảng trắng.
- Trình duyệt local dùng dữ liệu giả, cổng 18085: bốn màn danh sách; lọc/page/chi tiết; cảnh báo dữ liệu chưa lưu; Listening copy/save/publish; lỗi mạng/retry. Đo reflow 390px và 320px không tràn ngang ở mẫu đã xem.
- Axe ở các mẫu danh sách và chi tiết đã xem không còn violation sau khi giao diện ổn định. Một nhóm kiểm tra tương phản của các dòng nằm ngoài vùng cuộn Dictation vẫn yêu cầu kiểm tra thủ công. Đây không phải chứng nhận WCAG toàn bộ ứng dụng.

Ảnh: [người dùng mobile](admin-fix-assets/users-mobile.png), [hỗ trợ mobile](admin-fix-assets/support-mobile.png), [Listening mobile](admin-fix-assets/listening-mobile.png), [Listening nháp 320px](admin-fix-assets/listening-draft-320.png), [chi tiết tài khoản](admin-fix-assets/user-detail.png), [lỗi mạng](admin-fix-assets/network-error.png).

## Chưa kiểm chứng hoặc chưa thuộc đợt này

- Chưa chạy bản sửa trên VPS/PostgreSQL production, thử tải lớn, trình đọc màn hình đầy đủ, hoặc chấm live bằng ShopAIKey/gửi email thật. Preview không dùng AI, SMTP hay dữ liệu người dùng thật.
- Danh sách ticket trong chi tiết tài khoản hiển thị kết quả gần đây, có link tìm toàn bộ theo email; không tuyên bố đây là toàn bộ lịch sử ticket.
- Nhật ký hiển thị tối đa 100 sự kiện gần nhất. Hàng đợi quản trị AI pending/failed, MFA, export, bulk action và phân công ticket chưa bổ sung. Các mục có điều kiện trong audit vẫn cần đánh giá nhu cầu riêng.
- Tạo nháp Listening bằng AI giữ khóa bài nguồn trong transaction đến khi request hoàn tất; nếu nhiều admin tạo đồng thời hoặc AI thường timeout, cần tách bước sinh AI khỏi transaction. Chưa đo ảnh hưởng trên production.

## Khi triển khai

Migration `V20__admin_edit_versions_and_events.sql` chỉ thêm hai cột phiên bản, bảng nhật ký và index; không xóa dữ liệu. Đã chạy qua Flyway trên H2. Chưa kết luận thời gian khóa trên PostgreSQL production: cần xác nhận phiên bản DB, kích thước bảng, transaction dài; sao lưu và chạy trong cửa sổ triển khai phù hợp. Rollback ứng dụng có thể giữ các cột/bảng mới, tránh xóa nhật ký đã phát sinh.
