# Kết quả triển khai 90 bài nghe B1 — 15/10/2026

Đã tạo và xuất bản đủ 90 bài mới, gồm 1.333 câu nghe, 520 câu hỏi,
khoảng 116,7 phút audio. Mỗi bài có transcript, đáp án, nghe từng câu,
WAV và MP3. Audio yêu cầu đăng nhập như luồng luyện hiện có.

[Mở bộ 90 bài trên website](https://dictalab.hoangptkdhy.workers.dev/audio/b1-expansion-20261015/index.html).

| Mục | Mục tiêu luyện tham khảo | Số bài |
|---|---|---:|
| Giao tiếp | B1 | 30 |
| IELTS | 4.0 | 15 |
| IELTS | 5.0 | 15 |
| TOEIC | 600 | 15 |
| TOEIC | 700 | 15 |

IELTS có Part 1–4; TOEIC có Part 2–4. Đây là bài luyện rút gọn với
ngôn ngữ B1, chưa hiệu chuẩn điểm thi. Các mức không phải quy đổi chính thức
từ kết quả nghe sang overall IELTS hoặc tổng TOEIC. Nguồn và thiết kế chi tiết
nằm trong `b1-expansion-research-2026-10-15.md` cùng thư mục.

## Audio và lưu trữ

- 3 bài dùng Cartesia; 87 bài dùng Microsoft Edge neural sau khi Cartesia trả
  HTTP 402. Không mua thêm tín dụng hoặc nâng gói.
- Giao tiếp/TOEIC dùng giọng Mỹ; IELTS dùng giọng Anh. Provider và giọng được
  ghi đúng trên từng bài. Tốc độ thực tế của bộ này là 0,97.
- 180 file WAV/MP3 đã lưu trên AWS S3; từng object được đọc lại toàn bộ và
  đối chiếu SHA-256 trước khi nhập bài.
- WAV PCM16 mono 24 kHz; mốc câu theo số mẫu, đoạn không im lặng.
- Audio được stream cùng origin qua `/media/`, hỗ trợ HTTP Range 206.
  Không đưa audio vào JAR hoặc giữ nguồn audio của đợt này trên VPS sau triển khai.
- Các bản sao lưu được bảo vệ từ những đợt trước vẫn giữ nguyên.

## Kết quả kiểm tra

- Kiểm tra nội dung, dẫn chứng đáp án, dạng câu hỏi, tính riêng biệt transcript
  và biên tập bằng mô hình: đủ 90 bài.
- Nhận diện ngoại tuyến toàn bộ 90 WAV: tỷ lệ khớp token thấp nhất 0,9481,
  vượt ngưỡng 0,90. Hash, định dạng, thời lượng, mốc câu và âm lượng đều đạt.
- Xuất 90 MP3 và giải mã lại thành công; kiểm tra UTF-8 đạt cho 182 file web.
- HTTPS: 180 audio khớp byte khi đọc một đoạn; 8 WAV đại diện khớp toàn bộ SHA-256;
  90 trang bài tập khớp hash; 8 lượt bắt đầu luyện chép chính tả đạt.
- Trình duyệt: 8 dạng giao tiếp/IELTS/TOEIC đều phát và tua được;
  đáp án đúng được chấm đúng, đáp án sai bị từ chối; lựa chọn TOEIC Part 2 được
  ẩn khi làm bài. Kiểm tra không tràn ngang ở chiều rộng 390 px đạt.
- Khách chưa đăng nhập không truy cập được audio; người học không truy cập được
  API quản trị. Tài khoản và phiên luyện kiểm thử đã xóa.
- 90 bài mới thuộc đúng một mục; không có bài lẫn mục. Toàn bộ lesson, sentence,
  metadata, nhóm và liên kết cũ giữ nguyên.
- Sau triển khai: 222 lesson, 5.283 sentence; thư viện công khai có 122 bài;
  Flyway vẫn V35; health UP. 317 alias S3, 17 audio hội thoại; không còn WAV
  trong database, audio trong JAR, tham chiếu audio thiếu hoặc audio staging mới.

Kiểm tra mô hình và ASR không thay thế nghe duyệt của giáo viên hoặc thử nghiệm
người học. Chưa có chứng nhận CEFR/band/điểm cho các bài này.

## Bản triển khai và sao lưu

Chỉ overlay 183 tài nguyên tĩnh lên JAR production; backend, thư viện và
migration không đổi. Nhập nội dung và alias trong một transaction.

- JAR trước: `770f19df2657e06632d834f077d0db938e75c1d5de2b964562cf8a0899d54a31`
- JAR sau: `4a2556e5a4e4db6bc10e63ed976b8206efa52046b98f4ee2e5038d4dc4127f0f`
- Backup DB/media/JAR khi dừng dịch vụ: `/var/lib/dictalab/backups/cloud-ogv4xynm`
- JAR rollback: `/var/lib/dictalab/backups/release-exyzpw0n`
- Bằng chứng S3/SQL/triển khai: `/var/lib/dictalab/backups/b1-expansion-imq2qtu4`

Bằng chứng cục bộ: `artifacts/b1-expansion-20261015/` chứa `result.json`,
`verification.json`, `browser-check.json`, `final-check.json` và snapshot trước/sau.
Nội dung và audio nguồn: `data/generated-b1-expansion-20261015/`.

Kiểm tra có thể chạy lại: `node scripts/test-b1-expansion.cjs`.
Không commit/push hoặc triển khai các thay đổi khác đang có trong workspace.
Sau khi mọi kiểm tra hoàn tất, máy Windows được hẹn tắt theo yêu cầu chủ dự án;
không cưỡng chế đóng ứng dụng có dữ liệu chưa lưu.
