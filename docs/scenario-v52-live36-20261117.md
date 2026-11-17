# Kiểm thử sinh hội thoại V5.2: 36 lượt

Ngày 17/11/2026, 10:13:59–10:21:12 giờ Việt Nam, tổng thời gian thực **433,08 giây**. Đã xác minh mã nguồn `776c668ea7e257eb72088c28f7cde3e29e89d743`, Core jar, backend AI và prompt hash `5ac2eb0f3d04190bdc8e4dd5ecabf05d4f6d44f3d35edd2a7a99941a4006672b` trước khi chạy. Backend hash `2c10da5c746dee74e89568633148f620ea8e56cd65f9a65e53ed64312a368ff2`; runtime hash `52669a5a700874356c6fefabcef11ad2bf6e42daadc0c7443c947edfcef31902`.

## Thiết kế và phạm vi

- Chọn có chủ đích 12 đầu vào đã lưu, gồm bốn giao tiếp, bốn TOEIC và bốn IELTS, có các ca từng phát hiện lỗi. Mỗi đầu vào chạy ba lần với request ID riêng. Vòng hai đảo ngược vòng một; vòng ba dịch vị trí thứ tự. Không có request nào được gửi lại.
- Giữ snapshot cấu hình Gemini, nhiệt độ 0, model chính `gemini-3.5-flash-lite` và chính sách fallback sang `gemini-3.1-flash-lite`. Tối đa ba lượt đồng thời, như nhau ở cả ba vòng; pool provider và cooldown vẫn có hiệu lực. Hash tập đầu vào sau khi gắn chương trình V5.2: `2fb83f1a663cf5ea72b28fea7384a6dfa8610de9233d546ca39c3f8c75016dc9`.
- Các lượt chạy trực tiếp hàm Harness Python và gateway thật từ EXTRACT đến GENERATE, QUESTIONS, review, dịch và sửa khi cần; không mock và không phát lại artifact. Đây không phải test HTTP qua Core hoặc trải nghiệm trình duyệt. Sau đó đưa 33 kết quả hoàn tất qua kiểm tra chuỗi Harness và validator nội dung thực của Core: **33/33 qua cổng**. Cổng đó không chứng minh ngữ nghĩa đúng và không lưu bài vào cơ sở dữ liệu.
- Không đăng nhập, tạo tài khoản, thay đổi hạn mức hoặc chấm điểm học viên. Mẫu không ngẫu nhiên, không đại diện toàn bộ người học, và không đủ để suy luận thống kê nguyên nhân hay mức tin cậy production.

## Kết quả kỹ thuật

| Vòng | Hoàn tất kỹ thuật | Từ chối chất lượng | Lỗi provider cuối cùng |
|---|---:|---:|---:|
| 1 | 10/12 | 2/12 | 0/12 |
| 2 | 12/12 | 0/12 | 0/12 |
| 3 | 11/12 | 1/12 | 0/12 |
| Tổng | **33/36 (91,7%)** | **3/36 (8,3%)** | **0/36** |

Ba lượt từ chối đều `QualityRejected: question recovery exhausted` sau bước `REPAIR_QUESTIONS`: `toeic-standard-call` vòng 1 và `toeic-standard-poster` vòng 1, 3. Đây là kết quả kiểm tra nội dung/sửa không đạt, không phải lỗi provider. Không tính các lượt bị từ chối thành bài sửa thành công.

Có **12 lần fallback tại 12 request** sau 503 của model chính; các lần gọi fallback đều trả kết quả. Trong 12 request đó, 11 hoàn tất, một request sau đó bị từ chối chất lượng. Không có request cuối cùng báo lỗi provider. Harness ghi 11 lần thử sửa, năm lần được chấp nhận và sáu lần không được chấp nhận; chấp nhận patch không đồng nghĩa nội dung đúng.

Thời gian mỗi request: trung vị **33,43 giây**, khoảng **19,04–51,86 giây**. Trung vị mỗi lần gọi bước: EXTRACT 3,12 giây; GENERATE 3,10; QUESTIONS 4,26; REVIEW_QUESTIONS 4,63; TRANSLATE 2,88; REVIEW 4,14; REPAIR_QUESTIONS 2,56. REVIEW được dùng cho cả kiểm nguồn và kiểm bản dịch; các bước lặp được tính riêng. CSV bằng chứng có đủ ID, trạng thái, lỗi, thời gian toàn lượt, từng lần gọi bước, số fallback và kết quả Harness.

## Theo đầu vào

`OK` là hoàn tất kỹ thuật; `Từ chối` là `QualityRejected`. Số là thời gian toàn lượt tính bằng giây.

| Đầu vào | Vòng 1 | Vòng 2 | Vòng 3 |
|---|---|---|---|
| Giao tiếp A1 – plants | OK 25,62 | OK 29,42 | OK 25,86 |
| Giao tiếp A2 – pottery | OK 42,45 | OK 32,42 | OK 31,65 |
| Giao tiếp B1 – museum | OK 34,45 | OK 40,29 | OK 26,97 |
| Giao tiếp B2 – handover | OK 41,54 | OK 47,61 | OK 28,37 |
| TOEIC cơ bản – boxes | OK 26,76 | OK 34,49 | OK 21,11 |
| TOEIC chuẩn – call | Từ chối 25,88 | OK 41,82 | OK 24,45 |
| TOEIC chuẩn – poster | Từ chối 23,19 | OK 36,20 | Từ chối 19,04 |
| TOEIC cao – interview | OK 45,66 | OK 45,11 | OK 51,86 |
| IELTS cơ bản – artclub | OK 27,05 | OK 34,48 | OK 30,26 |
| IELTS chuẩn – essay | OK 43,08 | OK 35,17 | OK 32,08 |
| IELTS chuẩn – seeds | OK 35,68 | OK 46,80 | OK 35,65 |
| IELTS cao – lab | OK 29,22 | OK 43,15 | OK 31,18 |

Ba lần từ chối tập trung ở hai đầu vào TOEIC; poster có hai lần từ chối và một lần hoàn tất, call có một lần từ chối và hai lần hoàn tất. Đây là dấu hiệu biến thiên theo đầu vào/lần chạy trong mẫu nhỏ, không chứng minh nguyên nhân hay khác biệt có ý nghĩa thống kê.

## Đối chiếu nội dung

Đã đọc nguồn, câu hỏi, đáp án, khóa và bản dịch của các bài hoàn tất; các ví dụ sau là lỗi agent đối chiếu được, không phải nhãn chuyên gia và không phải kiểm định đầy đủ chất lượng sư phạm:

- `cefr-a1-plants` vòng 1, a3/q1: câu hỏi chỉ hỏi dụng cụ; khóa còn yêu cầu màu xanh và vị trí cạnh cửa. Dẫn chứng hoạt động a3 chỉ nói “that can”; hai chi tiết bổ sung nằm ở hoạt động trước. Có nguy cơ chấm thiếu công bằng.
- `cefr-b1-museum` cả ba vòng, a1/q1: khóa cho câu hỏi “vì sao” còn chứa chính việc bỏ lỡ phòng triển lãm, là tiền đề đã nêu trong câu hỏi. Vòng 3 đáp án còn nêu việc ở phòng gốm quá lâu nhưng khóa không kiểm tra ý đó. Vòng 3, a3 dịch “later this week” thành “cuối tuần này”, thu hẹp phạm vi thời gian.
- `cefr-b2-handover` vòng 1, a2/q1: “by six” trong nguồn bị dịch và trả lời thành “lúc sáu giờ”, làm mất nghĩa hạn chót.
- `toeic-standard-call` vòng 2, a3/q1: câu hỏi chỉ hỏi Jane có đồng ý không, nhưng khóa có thêm phần Alex đề xuất thời gian.
- `ielts-standard-seeds` vòng 3, a1/q1 và a3/q1: khóa thêm ý đã được câu hỏi cung cấp vào các phần bắt buộc, ngoài lý do hoặc thời điểm cần trả lời.
- `cefr-a2-pottery` vòng 3, a3/q1: câu hỏi hỏi đồ cần mang theo, nhưng đáp án và khóa thêm việc mặc quần áo thoải mái.

Các lỗi trên xuất hiện trong bài `COMPLETED` và đã qua cổng Core. Vì vậy không lấy 33/36 hay các reviewer PASS làm tỷ lệ chất lượng nội dung; chưa có tỷ lệ chất lượng được hiệu chuẩn bởi chuyên gia.

## Bằng chứng

Thư mục riêng `C:/Users/HP/.dictalab/local/v52-live36-20261117/` chứa `plan.json` với 36 ID và thứ tự, một JSON cho mỗi request với timestamps và attempts theo bước, `summary.json`, `per-request.csv`, `core-gate-input.json` và `finished.json`. Log fallback và lỗi nằm cạnh thư mục trong `v52-live36-20261117.log`. Các dữ liệu riêng không đưa vào Git. Không merge hoặc deploy production.
