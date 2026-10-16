# Báo cáo thử tải DictaLab — tối 03/10/2026

Trạng thái: **đã kết thúc một lượt hữu hạn; 300 tài khoản hỗn hợp đạt trong 120 giây, chưa xác định sức tải tối đa**.
Thời gian trong báo cáo là giờ Việt Nam (UTC+7). Task bắt đầu 19:15:51, app watchdog kết thúc 19:35:18;
controller đã thoát trước 19:35:50. Không có lịch lặp hoặc retry tự động.

## Phạm vi và điều kiện

- VPS Windows Server 2019, 8 vCPU, khoảng 7 GiB RAM; máy sinh tải k6 chạy cùng VPS.
- Giữ DictaLab production, WebLens, BilingualBackend, PostgreSQL và chống virus hoạt động.
- Theo lựa chọn của người dùng, tạm đóng MoreLogin và tool_auto_register-hoang, không xóa hồ sơ hay dữ liệu.
  RAM khả dụng tăng 819 MiB ngay sau đóng; lần kiểm tra tiếp theo còn 2.642 MiB, CPU trung bình 2,25%, paging 0.
  Đây là các ảnh chụp thời điểm khác nhau, không chứng minh toàn bộ biến động RAM do riêng hai ứng dụng.
- Production và ứng dụng thử cùng SHA256 `E39A42108C942B192F9736BBFCEB104CF57DDEEF5028B9205FAB55C0A86EB61C`.
- Test dùng DB riêng `dictalab_capacity`: 1.001 tài khoản và 24.027 phiên trước lượt đo. Fixtures ban đầu gồm
  24.000 phiên ngắn, mỗi phiên một câu/bốn từ; không đại diện bài dài hay toàn bộ lịch sử của người dùng thật.
- Ứng dụng thử chỉ nghe loopback 18282; metrics riêng 18283; heap tối đa 512 MiB, DB pool 10,
  HTTP workers 100, tối đa 4.096 kết nối. Rate limit tắt trên ứng dụng thử, không thay đổi production.
- k6 binary SHA256 `87DFA91BC3E47BC4BD77911D59D7FF79F25CD76FB8322C97072F08F08A0DA5ED`.

## Kịch bản và quy tắc dừng

- Preflight tải nền một lần; không lặp tự động nếu bị từ chối.
- Làm nóng bằng 5 người đọc/30 giây và 5 người luyện tập/30 giây.
- Tải hỗn hợp: 80% tài khoản đọc catalog/history/dashboard, 20% thực hiện luyện tập có ghi dữ liệu.
  Đây là tỷ lệ tài khoản, không phải tỷ lệ request hoặc hành vi đã quan sát từ người dùng thật.
- Các mức dự kiến: 100, 200, 300, 500, 700, 1.000 tài khoản giả lập; mỗi mức 120 giây làm việc, login riêng
  có pacing ngoài cửa sổ đo độ trễ work. Mỗi chu kỳ có thời gian chờ; số tài khoản không phải số request đồng thời.
- Core p95 dưới 1 giây; checks trên 99%, lỗi HTTP/nghiệp vụ dưới 1%. Controller dừng tăng tải nếu thấy bất kỳ
  lỗi HTTP/nghiệp vụ nào, kể cả khi tỷ lệ vẫn dưới 1%; không nới tiêu chí để biến lỗi thành đạt.
- Watchdog dừng ứng dụng thử nếu CPU toàn máy >=85% trong sáu mẫu liên tiếp, RAM khả dụng <500 MiB
  hoặc disk trống <10 GiB. App watchdog tối đa 1.500 giây; task không có lịch lặp, giới hạn 30 phút.
- Không đo public TLS/CDN, browser rendering, tải AI/TTS thật hoặc WebRTC media trong lượt này.

## Kết quả

Preflight 19:15–19:17 đạt: 10 mẫu, CPU trung bình 3,3%, đỉnh 9%, RAM khả dụng thấp nhất 2.609 MiB;
paging và disk queue bằng 0 tại các mẫu.
Làm nóng đọc dữ liệu: 70 chu kỳ, 0 lỗi, p95 213,56 ms/p99 687,49 ms. Làm nóng luyện tập:
20 chu kỳ, 0 lỗi, p95 345,50 ms/p99 403,77 ms.

| Tài khoản hỗn hợp / 120 giây | Chu kỳ hoàn tất | HTTP toàn lượt, gồm login | p95 work | p99 work | Lỗi HTTP/nghiệp vụ | Kết quả |
|---:|---:|---:|---:|---:|---:|---|
| 100 | 4.986 | 17.058 | 32,93 ms | 186,68 ms | 0 | Đạt |
| 200 | 10.019 | 34.257 | 27,19 ms | 108,66 ms | 0 | Đạt |
| 300 | 14.916 | 51.048 | 46,91 ms | 124,90 ms | 0 | Đạt |
| 500 — bị dừng sớm | 7.924 | 28.784 | 351,08 ms | 589,09 ms | 432 HTTP transport failures (1,50%); business error 0,31% | Không nghiệm thu; CPU guard |

Các mức 100/200/300 đều hoàn tất cửa sổ 120 giây và 100% business checks, không HTTP/business error.
Chu kỳ gồm chuỗi đọc hoặc một câu luyện tập, không phải hoàn tất một khóa học. HTTP toàn lượt có login;
không dùng `http_reqs/s` toàn lượt làm throughput work. Ở mức 300, trừ 900 request setup còn 50.148 request work;
chia cửa sổ danh nghĩa 120 giây là khoảng 418 request/s, nhưng graceful completion có thể kéo dài thêm tối đa
15 giây. Đây không phải tốc độ steady-state đã đo chính xác hay AI calls/s.

Ở mức 300, request chậm nhất vẫn lên **4,12 giây** dù p95 tốt. Không được diễn giải p95 là mọi request nhanh.
Các p95 route ở mức 300: catalog 49,49 ms, history 36,65 ms, dashboard 42,92 ms.

## Vì sao mức 500 dừng

- Từ 19:34:42 đến 19:35:18, sáu mẫu CPU liên tiếp là **100%, 90%, 100%, 100%, 94%, 95%**.
  Bộ bảo vệ dừng đúng ứng dụng thử; RAM tại mẫu cuối vẫn còn **1.368 MiB**, không chạm ngưỡng RAM 500 MiB.
- Lỗi kết nối đầu tiên được k6 ghi lúc **19:35:19**, sau khi app thử bị dừng. Log có 423 dòng connection-refused
  và 89 dòng business rejection với HTTP status 0; các nhóm log không được cộng lại thành số request.
  Tổng failure trong k6 là 432/28.784 request. Đây không phải 432 response HTTP 500 từ endpoint.
- p95/p99 và 7.924 chu kỳ của mức 500 là kết quả **một lượt bị cắt**, không đủ 120 giây. Không dùng chúng để
  công bố mức 500 đạt hoặc tính throughput bền vững. Lượt không được nghiệm thu dù latency từng route dưới 1 giây.
- 40 structured log records của lần chạy không có application ERROR hoặc cảnh báo DB unavailable;
  metric Hikari connection timeout được scrape gần cuối là 0. Pool pending có mẫu lên 11; snapshot 0 timeout
  không chứng minh mọi biến động ngắn đều đã được bắt. Chưa có bằng chứng lỗi nghiệp vụ mới cần sửa từ lượt này.
- 700 và 1.000 không chạy vì đã dừng ở 500. Không kết luận các mức đó bất khả thi.

## Tài nguyên

Watchdog có **174 mẫu**, từ 19:17:00 đến 19:35:18: CPU trung bình toàn lượt 29,39%, đỉnh 100%;
RAM khả dụng thấp nhất 1.368 MiB; RSS app thử cao nhất 568 MiB. Generator mức 500 có RSS cao nhất
**956,72 MiB** và mẫu RAM toàn máy thấp nhất 1.367 MiB. Sai khác 1 MiB là do thời điểm lấy mẫu khác nhau.

| Mức | Mẫu watchdog | CPU trung bình / đỉnh | RAM khả dụng thấp nhất | Pool pending đỉnh |
|---:|---:|---:|---:|---:|
| 100 | 28 | 25,57% / 67% | 1.894 MiB | 0 |
| 200 | 35 | 31,74% / 79% | 1.944 MiB | 0 |
| 300 | 43 | 39,77% / 100% | 1.821 MiB | 0 |
| 500, bị cắt | 46 | 24,15% / 100% | 1.368 MiB | 11 |

Các khoảng này gồm setup/login và work; CPU trung bình thấp ở 500 không phủ nhận sáu mẫu bão hòa cuối.
Trong các mẫu CPU cao của 500, app thử dùng khoảng 41–50% CPU chuẩn hóa toàn máy, k6 khoảng 10–19%.
Top process còn lại từng mẫu là PostgreSQL hoặc System. Số CPU theo process là delta giữa mẫu, còn counter
CPU toàn máy có khoảng lấy mẫu khác; không cộng các con số này để tuyên bố phân bổ đủ 100% hay quy nguyên nhân
cho một ứng dụng khác. Muốn định vị hàm/truy vấn tốn CPU cần profiling riêng, chưa thực hiện trong lượt này.

Paging có đỉnh 4.645 pages/s, disk queue đỉnh 3 trong toàn lượt; không gán các đỉnh này cho cùng thời điểm
CPU abort nếu chưa đối chiếu timestamp. Không giảm bộ nhớ DB hoặc tăng pool chỉ để làm một lượt đo đạt.

## Trạng thái sau thử tải và bằng chứng

- Production health UP; WebLens backend/crawler, BilingualBackend và hai PostgreSQL service đều Running.
- Không còn k6 hoặc Java capacity; task one-shot đã gỡ. Không xóa DB, media, profile hay log.
- Kiểm tra cuối 19:47: 0 process thử/0 load tasks, RAM khả dụng 2.844 MiB, production hash giữ nguyên;
  Windows Defender và lịch daily backup được giữ. Kiểm tra UTF-8 đạt 6 files và targeted diff check đạt.
- MoreLogin và tool_auto_register-hoang vẫn tạm đóng theo lựa chọn của người dùng; có thể mở lại bình thường.
- Production sau đo có 7 tài khoản và 37 phiên. k6 chỉ dùng DB capacity, không tạo tài khoản trên production.
  Không có count production ngay trước lượt tối này để quy thay đổi so với ảnh chụp 14:00 cho lượt thử tải.
- 36 file evidence được giữ tại `target/capacity/evidence-20261003-evening/` trên HP và
  `C:/DictaLab/capacity/evidence-20261003-evening/` trên VPS: JSON, logs, generator CSV, preflight,
  watchdog CSV và metrics. Bằng chứng các lượt trước cũng được giữ riêng, không ghi đè kết luận lịch sử.

## Kết luận và bước tiếp theo

**Mức cao nhất hoàn tất trong lượt này: 300 tài khoản giả lập hỗn hợp, 120 giây, không lỗi.**
Đây không phải cap cứng 300, không phải 300 request đồng thời và chưa là công suất bền vững của website.
Đóng hai ứng dụng đã tạo dư địa RAM, nhưng ở bước 500 bộ bảo vệ dừng vì CPU. Không có cơ sở chỉ tiếp tục
dọn RAM hoặc tắt WebLens để suy ra sức tải tối đa.

Nếu đo tiếp, ưu tiên đưa generator sang HP sau khi kiểm chứng đường truyền SSH, giữ ngưỡng bảo vệ VPS;
generator ở cùng VPS đã chiếm gần 1 GiB và CPU đáng kể. Sau đó đo lại workload mixed và soak dài hơn ở mức
đã đạt, lấy profile CPU/query trước khi thay đổi app/pool. Đây là bước đề xuất, chưa tự khởi chạy.

Không coi số đo reads 300/10 phút của bản build buổi chiều là mixed soak hoặc kết quả của JAR tối này.
AI/TTS, WebRTC hai thiết bị, burst login, dataset lớn hơn và edge/browser vẫn cần phép đo riêng.

## Giới hạn diễn giải

Máy sinh tải cùng VPS chiếm CPU/RAM, nên ngưỡng dừng không tự chứng minh giới hạn riêng website. Kết quả
ngắn chỉ thể hiện khả năng của dataset/kịch bản/bản build này; chưa là công suất bền vững nhiều giờ hay cam kết
cho người dùng thật. Không áp đặt 10.000 người và không coi mức cao nhất được thử là sức tải tối đa.
