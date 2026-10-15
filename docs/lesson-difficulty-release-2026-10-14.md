# Phân loại bài theo độ khó — 14/10/2026

Trạng thái: Đã deploy VPS và kiểm tra HTTPS production ngày 14/10/2026 tại https://dictalab.hoangptkdhy.workers.dev/catalog.

## Phạm vi đã duyệt

Điều hướng: **IELTS / TOEIC / Giao tiếp → Độ khó bài → Nhóm bài → Bài gốc**. Chủ dự án chốt IELTS 3.0/4.0/5.0/6.0/7.0/8.0+, TOEIC 300/500/700/900+, Giao tiếp A1/A2/B1/B2/C1.

Stitch MCP chỉnh tại chỗ màn B desktop `959b7db7e09c4918812a034d2d56a941` và mobile `6e54928b964a49efb8849b77200d64cf`, project `6530183283621390934`, session `15118462609102154264`. Áp dụng thẻ mức gọn, nhãn Độ khó bài, chủ đề thứ cấp, lưới 3 cột mobile; giữ màu, font, danh sách/chi tiết và bo góc. Bằng chứng: `artifacts/library-difficulty-20261014/stitch-edit-evidence.json`. Stitch trả báo cáo và thao tác DOM; số bài minh họa của Stitch không thay cho API hoặc phân loại nội dung thực tế. Không khẳng định đối chiếu pixel 100%.

## Nội dung và giới hạn phân loại

Đây là **phân loại luyện tập tham khảo**, chưa hiệu chuẩn IELTS/TOEIC. Transcript của 23 bài đã được đọc; audio có kiểm kê kỹ thuật 82 file giải mã được. Chưa nghe đối chiếu toàn bộ âm, nối âm, tốc độ nói hoặc giọng đọc; không mở bộ lọc giọng/số người nói chưa xác minh.

IELTS chính thức chấm Listening theo kết quả bộ câu hỏi và thang band, không ấn định band từ một câu chép chính tả: [IELTS scoring](https://ielts.org/take-a-test/your-results/ielts-scoring-in-detail). TOEIC gồm Listening và Reading, nội dung thường gắn với công việc: [ETS TOEIC Listening & Reading](https://www.eu.ets.org/toeic/about/listening-reading.html). Vì vậy, các mốc dưới đây chỉ là lựa chọn sắp xếp giáo trình nội bộ, không điểm thi hoặc bảng đổi CEFR.

| Hướng luyện | Mức | Bài gốc | Căn cứ nội dung |
|---|---|---|---|
| IELTS | 3.0 | 30,31,32,29 | Hỏi đáp ngắn, động từ cơ bản, thông tin quen thuộc |
| IELTS | 4.0 | 33,34,35,36,37,38,39 | Hỏi đáp mở rộng, thói quen, lịch hẹn, tương lai; B1 nhấn mạnh vẫn có câu ngắn |
| IELTS | 5.0 | 41,42,40,43,44,45,47 | Thói quen quá khứ, sai khiến, lời khuyên, bị động, thay đổi dự định; không nâng mọi B2 lên 6.0 |
| IELTS | 6.0 | 46,48,121,122,123 | Nguyên nhân/kết quả, trạng từ mang sắc thái; hội thoại nhiều điều kiện, trung bình ~12–13 từ/câu với 3 bài tổng hợp |
| IELTS | 7.0,8.0+ | Chưa có | Thiếu diễn giải học thuật, ý ngầm và lời nói phức tạp đã được đánh giá |
| TOEIC | 300 | 30,32,33,34,35,36 | Nghề nghiệp, địa điểm, hoạt động, đi lại; chi tiết trực tiếp |
| TOEIC | 500 | 37,38,40,44,45,47,48 | Lịch hẹn, yêu cầu, bị động, dịch vụ và thay đổi kế hoạch |
| TOEIC | 700 | 46,121,122,123 | Phương án thay thế, nguyên nhân, đổi trả, ngân sách và phân công |
| TOEIC | 900+ | Chưa có | Thiếu nội dung nghiệp vụ dài, thông tin dày và ý ngầm đã được đánh giá |
| Giao tiếp | A1 | 29–33 (5 bài) | CEFR bài gốc |
| Giao tiếp | A2 | 34–38 (5 bài) | CEFR bài gốc |
| Giao tiếp | B1 | 39–43,121–123 (8 bài) | CEFR bài gốc; bài tổng hợp vẫn là mức tham khảo |
| Giao tiếp | B2 | 44–48 (5 bài) | CEFR bài gốc |
| Giao tiếp | C1 | Chưa có | Không nâng B2 lên C1 để lấp chỗ trống |

15 mức công khai, 11 mức có nội dung, 4 mức đang bổ sung; 63 liên kết tới 23 bài gốc. Phân loại IELTS/TOEIC có thể cần điều chỉnh sau khi giáo viên nghe đối chiếu và thử nghiệm với người học.

## Code và bảo toàn dữ liệu

- Migration V33 thêm 15 mức, 26 nhóm và liên kết đã rà. 14 khóa cũ được ngừng công bố, giữ nguyên bản ghi/liên kết cho quản trị; không xóa bài, sửa transcript/audio hoặc cập nhật phiên/kết quả.
- Link khóa cũ có alias sang mức mới; nếu có ID bài đã chọn thì ưu tiên mức chứa đúng bài đó. Bỏ group cũ để tránh lọc mất bài.
- Tiếp tục đúng phiên solo, hoàn thành 100% và luyện lại 0% vẫn riêng biệt. Mỗi bài dùng chung lịch sử giữa các hướng luyện.
- Màn quản trị đổi nhãn sang mức luyện, giữ xác thực/quyền admin/CSRF/kiểm tra nội dung; khóa lưu trữ vẫn chỉnh được.
- Đóng gói trên JAR production `c56ff1f8b7814479ca2fa172f17b2afba65b01dfd9545ff0b9a84eb94748ea1f`, chỉ thay `index.html`, `admin.html`, `library.js`, `library.css`, thêm V33; bytecode, `app.js`, dependency và cấu hình runtime giữ nguyên.

## Kiểm tra

- 7/7 kiểm thử PostgreSQL `LessonLibraryIntegrationTest`: taxonomy, quyền/CSRF, nội dung riêng tư, metadata hết hạn, độ phủ nhóm, cùng bài gốc, tiến độ hoàn thành/luyện lại.
- 11 kiểm tra trình duyệt bản JAR ứng viên: API/mức/ID, phân trang, tìm kiếm/nhóm, link cũ, login return, desktop/mobile 320/390px, Back, bàn phím, không pageerror.
- 6 kiểm tra giao diện bằng fixture người học/admin: phân biệt hoàn thành/luyện lại, URL tiếp tục, link bài trang sau, lưu form đúng kiểu dữ liệu, không bịa giọng/số người nói.
- DB test mới có 20 bài bundled: 9 mức đủ nhóm; QA trình duyệt nhập thêm 3 hội thoại công khai production vào DB loopback để đối chiếu đủ 23 bài. Không nhập tài khoản hay lịch sử thật, không đóng vai người dùng thật.
- UTF-8 và cú pháp JavaScript/Python được kiểm tra. Không chạy lại 50VU hoặc thao tác OAuth Google bằng tài khoản thật trong thay đổi taxonomy này.

## Review migration / triển khai

Quyết định: **low risk với điều kiện** dùng PostgreSQL 18.6 production, Flyway transaction; chỉ DML trên bảng thư viện nhỏ. Không sửa checksum V31/V32, không DDL hoặc rewrite bảng lịch sử. V33 có lock timeout 3s và statement timeout 15s. Dừng service, sao lưu quiescent, kiểm tra SHA nền, thay JAR atomically, chờ health; lỗi thì quay lại JAR đã sao lưu. Dữ liệu V33 tương thích schema/code cũ, không tự chạy down migration.

Preflight: V32; 123 lessons, 1.237 sessions, 68.352 attempts, 111 users; không có idle transaction >30s. Inventory công khai và số bản ghi học không thay đổi so với lần deploy trước.

## Kết quả production

- JAR cuối: `d3bf6b4091e925b3943dfbccdf462f4ec5071f2c05d3f935aa661b7e8bbe174e`; health UP khi helper hoàn tất, hash JAR cài trên VPS đối chiếu trùng khớp.
- Schema V33. Inventory 23 bài công khai và counters 123 lessons / 1.237 sessions / 68.352 attempts / 111 users giống preflight. Bản ghi đối chiếu tiến độ bài 122 giữ nguyên: có hoàn thành, phiên solo mới ở vị trí 1, không room.
- HTTPS smoke 11 requests: catalog/API/resources thành công; JS/CSS byte-for-byte đúng JAR; endpoint admin/history/me vẫn yêu cầu đăng nhập, POST không CSRF bị từ chối; không lộ tiến độ cá nhân hoặc audit notes của quản trị. Google config vẫn enabled.
- 11/11 kiểm tra trình duyệt trên HTTPS thật: đủ 15 mức/63 memberships/23 originals, labels và counts đúng, 4 mức chưa có bài hiển thị trung thực, nhóm/tìm kiếm/phân trang, link cũ có hoặc không có course, login return, 320/390px, Back/bàn phím, không pageerror. Một lần đọc API bằng User-Agent mặc định Python bị 403; client QA có tên `DictaLabReleaseCheck/1.0` và trình duyệt Chromium đều hoàn tất các kiểm tra.
- Bản đầu `fda448bc1caa49b26a176a839095848f5325bbcf2277a3aeb456003f8ab1abdb` deploy cùng V33; sau kiểm tra bổ sung xử lý link chỉ có goal + lesson, bản cuối chỉ thay `library.js`. Không thay V33 sau khi deploy.
- Backup trước taxonomy: `/var/lib/dictalab/backups/release-a7pv49w0`; backup trước bản JS cuối: `/var/lib/dictalab/backups/release-p8tapvix`. Quay lui JAR giữ dữ liệu V33; muốn khôi phục cách tổ chức khóa cũ phải có thay đổi công bố taxonomy riêng, không xóa dữ liệu học.
- Bằng chứng: `artifacts/library-difficulty-20261014/{manifest.json,predeploy.json,postdeploy.json,deploy-smoke.json,browser-results.json,production-browser-results.json,browser-auth-fixtures.json}`. Ảnh `desktop.png`, `mobile-list.png`, `mobile-detail.png` cuối là website production thật; ảnh người học/admin là fixture được ghi rõ.
- Kiểm tra UTF-8, cú pháp JavaScript/Python đã qua. Không chạy lại 50VU, hiệu chuẩn band/score hoặc OAuth Google bằng tài khoản thật.
