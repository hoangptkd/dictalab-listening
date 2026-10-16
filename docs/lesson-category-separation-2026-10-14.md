# Phân loại riêng bài nghe — 14/10/2026

## Kết luận kiểm kê

23 bài công khai hiện có thuộc **Giao tiếp**: 20 bài ELLLO Grammar Conversations và 3 hội thoại tình huống do AI tạo. Các chủ đề công việc, dịch vụ hoặc du lịch có thể hỗ trợ luyện nghe, nhưng không đủ căn cứ để gắn thành bài IELTS/TOEIC. Không quy đổi độ khó CEFR thành điểm thi.

Đã đối soát toàn bộ ID, nguồn, transcript và mức CEFR trong database VPS với bản kiểm kê trước. Các bài gốc có 946 câu. Phân loại này không phải hiệu chuẩn năng lực hay chứng nhận nghe đối chiếu từng âm của audio.

| Mã bài | Tên bài | Mục | Mức |
|---|---|---|---|
| 29 | Be Verbs - Present Simple | Giao tiếp | A1 |
| 30 | Yes / No Questions - Be Verbs | Giao tiếp | A1 |
| 31 | Likes and Dislikes | Giao tiếp | A1 |
| 32 | Verbs - Present Simple | Giao tiếp | A1 |
| 33 | Yes / No Questions with Verbs | Giao tiếp | A1 |
| 34 | Present Simple | Giao tiếp | A2 |
| 35 | Third Person Singular - Present Simple | Giao tiếp | A2 |
| 36 | Present Continuous | Giao tiếp | A2 |
| 37 | Will / Future Tense | Giao tiếp | A2 |
| 38 | Going to / Future Tense | Giao tiếp | A2 |
| 39 | Do for Emphasis | Giao tiếp | B1 |
| 40 | Causative Verbs | Giao tiếp | B1 |
| 41 | Used To for Past Tense | Giao tiếp | B1 |
| 42 | Would for Past Tense | Giao tiếp | B1 |
| 43 | Should for Suggestions | Giao tiếp | B1 |
| 44 | Simple Passive | Giao tiếp | B2 |
| 45 | Past Passive | Giao tiếp | B2 |
| 46 | Was able to | Giao tiếp | B2 |
| 47 | Was going to | Giao tiếp | B2 |
| 48 | Adverb Placement | Giao tiếp | B2 |
| 121 | Đổi kế hoạch chuyến đi | Giao tiếp | B1 |
| 122 | Đổi trả đơn hàng | Giao tiếp | B1 |
| 123 | Lên kế hoạch hoạt động nhóm | Giao tiếp | B1 |

Giao tiếp: A1 5 bài, A2 5 bài, B1 8 bài, B2 5 bài. C1 chờ nội dung. IELTS và TOEIC giữ các mức để bổ sung bài riêng, hiển thị **Đang bổ sung nội dung**. Tất cả bài là danh sách tổng hợp của cùng các bài gốc.

## Thay đổi

- V34 gỡ liên kết IELTS/TOEIC của đúng 23 slug đã kiểm kê, kể cả khóa lưu trữ; giữ bài, audio, transcript, metadata và lịch sử học. Không sửa migration đã triển khai.
- Backend chặn liên kết một bài vào hai mục khác nhau, kể cả khóa chưa công bố. Khóa bài trong transaction để các yêu cầu đồng thời không vượt kiểm tra.
- Khi đổi mục của khóa, backend kiểm tra các bài đã liên kết để tránh tạo liên kết chéo mục.
- Muốn chuyển bài: bỏ các liên kết ở mục cũ, rồi liên kết vào mục mới. Một bài có thể nằm trong nhiều khóa thuộc cùng một mục.
- Quản trị hiển thị quy tắc phân loại này.

## Kiểm tra

9/9 kiểm thử `LessonLibraryIntegrationTest` đạt trên PostgreSQL 18.4: quyền ADMIN/CSRF, phân loại, chặn liên kết chéo, đổi mục khóa, metadata hết hạn, nội dung riêng tư, độ phủ nhóm, bài gốc và tiến độ. Kiểm tra UTF-8 cho các file thay đổi và cú pháp JavaScript đạt.

Lệnh chạy lại:

```powershell
./scripts/test-postgres.ps1 -PostgresBin 'C:\Users\HP\Tools\postgresql-18.4\pgsql\bin' -Port 15439 -MavenArgs @('test','-Dtest=LessonLibraryIntegrationTest','-Ddictalab.build.directory=target-library-separation-pg')
```

## Migration

Quyết định: **safe with conditions**. Production PostgreSQL 18.6, Flyway v33 trước cập nhật, database khoảng 63 MB, không có idle transaction quá 30 giây. V34 chỉ DML trên bảng liên kết nhỏ, lock timeout 3 giây và statement timeout 15 giây. Sao lưu quiescent trước khi thay JAR; không triển khai hai phiên bản song song. Migration giao dịch thất bại phải rollback; không chạy down migration tự động.

JAR ứng viên giữ toàn bộ entry ngoài `LessonLibrary.class`, `library.js` và V34. Bằng chứng và bản kiểm kê đặt tại `artifacts/library-separation-20261014/`.

## Kết quả triển khai

Đã cập nhật VPS và đối soát website thật. JAR SHA-256 `fef7a8f3011deb2718620ee5caae2b9b71b7070633845aec9b3f65f90c220527`; schema V34; health UP. Backup/rollback tại `/var/lib/dictalab/backups/release-tkkv_r8m` cùng bản sao lưu database quiescent của quy trình release hiện có.

- API công khai trả đúng 23 liên kết Giao tiếp; số bài A1/A2/B1/B2 là 5/5/8/5. IELTS và TOEIC có 0 bài, chưa ready.
- SQL kiểm tra cả khóa lưu trữ: không còn liên kết IELTS/TOEIC; không có bài thuộc nhiều mục khác nhau.
- Toàn bộ 23 bài, transcript và đường dẫn audio trùng bản kiểm kê trước; inventory trước/sau cập nhật bằng nhau.
- Số bản ghi giữ nguyên: 123 lessons, 1.237 phiên, 68.352 lượt trả lời, 111 tài khoản. Phiên hoàn thành và phiên luyện lại bài 122 giữ nguyên.
- JavaScript qua HTTPS trùng byte với JAR ứng viên. API quản trị và thông tin tài khoản tiếp tục từ chối khách; không lộ ghi chú nội bộ hoặc tiến độ cá nhân.
- Trình duyệt thật: IELTS và TOEIC hiển thị 0 bài cùng trạng thái đang bổ sung; Giao tiếp hiển thị đúng các mức và số bài. Ảnh bằng chứng `ielts.png`, `toeic.png`, `communication.png`.

Không tạo bài mới hoặc hiệu chuẩn điểm IELTS/TOEIC trong đợt sửa phân loại này.
