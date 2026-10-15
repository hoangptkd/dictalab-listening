# Kế hoạch kiểm thử trải nghiệm làm bài và tải 50 VU

Đây là kế hoạch baseline trước khi chạy. Kết quả cập nhật, bằng chứng browser, lỗi mới và phần chưa bao phủ nằm tại `artifacts/practice-ux-20261014/report.md`; trạng thái “Chờ” trong ma trận dưới không phải kết quả cuối. Chủ dự án đã yêu cầu tiếp tục chạy tải local độc lập với các lỗi frontend đang đỏ.

Bản sửa sau khi được duyệt, kết quả 204 kiểm thử và lượt tải 50 VU chạy lại nằm tại [báo cáo sửa ngày 14/10](../artifacts/practice-fix-20261014/report.md). Các lỗi RACE-01..07 đã có kiểm thử đạt; bản sửa chưa deploy lên VPS.

## Mục tiêu và phạm vi

Ưu tiên chép chính tả: người học gõ nhanh vẫn giữ đúng chữ, vị trí con trỏ, kết quả chấm và số lần thử. Kiểm tra toàn bộ hành trình chọn bài → nghe → nhập → sửa/gợi ý → chuyển câu → kết quả → mở lại. Phòng học chung được kiểm tra tương tác riêng.

Hai lớp kiểm thử độc lập:

1. Frontend: thứ tự sự kiện bàn phím, timer, phản hồi API, bản nháp và focus. Test tự động dùng chính các hàm trong `app.js`, đồng hồ và API có độ trễ điều khiển được; không thay logic sản phẩm.
2. k6: tối đa 50 tài khoản tổng hợp đồng thời, gọi API và tải audio thật trong môi trường local tách biệt. Đây là 50 VU HTTP, không phải 50 trình duyệt đang gõ và không chứng nhận sức tải VPS.

Chưa sửa logic làm bài, chưa chạy tải production, không dùng tài khoản học thật, không gọi AI trả phí. Không thể chứng minh “mọi trường hợp” bằng một lần chạy; ma trận dưới đây xác định phạm vi nghiệm thu và ghi rõ phần còn thiếu.

## Kết quả khởi đầu

Ngày 14/10/2026, `practice-input-races.test.cjs`: **17 test, 12 đạt, 5 lỗi**, không skip/TODO. Đây là test logic với DOM/API giả lập, cần xác nhận thêm trên trình duyệt thật.

| Mức | Mã | Tái hiện và kết quả hiện tại | Tiêu chí sau sửa |
|---|---|---|---|
| High | RACE-01 | Gửi `cot`, trong lúc đang chấm sửa thành `cat`; timer mới chạy khi request cũ chưa xong nên bị bỏ qua. Sau phản hồi cũ, `cat` vẫn chưa được gửi. | Chấm bản nhập mới nhất khi hàng đợi rảnh; không mất bản nháp hoặc nhảy con trỏ. |
| High | RACE-02 | Gõ đủ chữ rồi rời ô; blur gửi ngay, timer 240 ms vẫn gửi lại sau khi request đầu kết thúc. | Một hành động nhập chỉ tính một lần thử; retry cùng hành động dùng cùng khóa. |
| High | RACE-03 | Đặt timer nhập, đổi câu trước 240 ms; timer cũ dùng đường dẫn câu mới. | Timer/phản hồi của câu hoặc phiên cũ không ghi vào câu/phiên mới. |
| Medium | RACE-04 | Sự kiện input có `isComposing=true` vẫn được tự chấm sau 240 ms. | Chỉ chấm sau khi bộ gõ kết thúc composition. |
| Medium | RACE-05 | Space trong composition bị chặn và chuyển sang ô kế tiếp. | Bộ gõ giữ quyền xử lý Space/Enter khi composition đang hoạt động. |

Test đỏ được giữ nguyên làm tiêu chí sửa lỗi, không nới assertion để làm xanh. Chạy cả thư mục test JS hiện sẽ có các thất bại này cho đến khi sửa logic.

## Ma trận 50 tình huống

Ký hiệu: **Đạt logic** = harness đã chạy; **Lỗi logic** = đã tái hiện trong harness; **Chờ** = cần test bổ sung hoặc xác nhận trong trình duyệt/backend. Không coi test logic là thay thế cho kiểm tra browser.

| ID | Tình huống | Kỳ vọng chính | Trạng thái |
|---|---|---|---|
| 01 | Gõ liên tục cách 0/10/40/120 ms | Chỉ gửi bản nhập cuối sau debounce | Đạt logic, 4 tốc độ |
| 02 | Sửa đủ chữ khi request cũ đang chấm | Bản mới được chấm tiếp | Lỗi logic RACE-01 |
| 03 | Xóa hết trước/sau phản hồi | Giữ ô trống, không khôi phục chữ cũ | Đạt logic trước phản hồi; chờ browser |
| 04 | Sửa chưa đủ chữ khi phản hồi sai đến muộn | Giữ bản nháp và focus hiện tại | Đạt logic |
| 05 | Blur trùng timer tự chấm | Không chấm cùng câu trả lời hai lần | Lỗi logic RACE-02 |
| 06 | Đổi câu khi timer còn chờ | Timer cũ không tác động câu mới | Lỗi logic RACE-03 |
| 07 | Đổi phiên khi request/timer còn chờ | Không ghi vào phiên khác | Chờ |
| 08 | Reload lúc đang chấm | Khôi phục kết quả đã lưu; không nhân đôi lần thử | Chờ browser/backend |
| 09 | Back/Forward giữa catalog và bài | Đúng phiên/câu, không audio/timer chạy nền | Chờ |
| 10 | Đăng xuất khi có request đang chạy | Dữ liệu cũ không hiện trên tài khoản kế tiếp | Chờ |
| 11 | Space rồi gõ ngay ô tiếp theo | Di chuyển ngay, phản hồi cũ không cướp focus | Đạt logic |
| 12 | Enter liên tiếp khi từ cuối đang chấm | Một check, một lần hoàn thành | Đạt logic |
| 13 | Enter khi phản hồi cuối là sai | Ở lại câu, không tự hoàn thành về sau ngoài ý muốn | Chờ |
| 14 | Tab/Shift+Tab nhanh và click đổi ô | Thứ tự focus đúng, bản nhập được giữ | Chờ |
| 15 | Backspace trong ô rỗng/đang chọn chữ | Chỉ lùi ô khi phù hợp, không xóa nhầm | Chờ |
| 16 | Composition input chưa kết thúc | Không chấm chữ trung gian | Lỗi logic RACE-04 |
| 17 | Space trong IME | Không chặn bộ gõ/chuyển ô | Lỗi logic RACE-05 |
| 18 | Enter trong IME | Không submit khi composition đang hoạt động | Đạt logic; chờ IME thật |
| 19 | Dán nhiều từ có dấu câu | Phân phối đúng các ô và xử lý dấu câu | Đạt logic |
| 20 | Paste dài, xuống dòng, ô readonly/tên riêng | Không lệch từ, không sửa ô đã khóa | Chờ |
| 21 | Chữ hoa/thường, khoảng trắng đầu/cuối | Chuẩn hóa nhất quán với backend | Chờ |
| 22 | Apostrophe/contractions | Không kẹt do độ dài/chuẩn hóa | Chờ |
| 23 | Gạch nối và Unicode tương đương | Nhập và chấm nhất quán | Chờ |
| 24 | Emoji, ký tự vô hình, HTML, chuỗi quá dài | Validation rõ ràng, không lỗi giao diện/XSS | Chờ |
| 25 | Ô tên riêng hoặc từ đã đúng | Không bị tính thêm lần thử khi điều hướng | Chờ |
| 26 | API trễ 100/300/1000/3000 ms | Không mất chữ, có trạng thái chờ và phục hồi | Đã mô phỏng một phần; chờ browser |
| 27 | Gợi ý/phân loại/audio chen giữa các check | Trạng thái cuối đúng, không ghi đè draft | Chờ |
| 28 | Mất phản hồi sau khi server đã lưu | Retry không tính điểm/lần thử hai lần | Giữ khóa retry đã đạt logic; chờ backend |
| 29 | Offline → online giữa câu | Giữ nội dung và cho retry có kiểm soát | Chờ browser |
| 30 | JWT/CSRF hết hạn, 401/403/429/503 | Không lặp request vô hạn hoặc mất bài | Có suite auth riêng; chờ hành trình browser |
| 31 | Nhập trước khi nghe | Khóa đúng và có hướng dẫn rõ ràng | Chờ |
| 32 | Bấm Nghe liên tục, Stop, chuyển tab | Không phát chồng hoặc tính replay sai | Chờ |
| 33 | Đổi tốc độ 0,75/1/1,25 khi phát | Âm thanh và thống kê thống nhất | Chờ |
| 34 | Mở một từ/toàn câu rồi nghe lại | Chỉ mở nút tiếp tục khi đủ điều kiện | Cấu hình k6 đã có; chưa chạy HTTP |
| 35 | Audio 404, chậm, autoplay bị chặn | Thông báo rõ, có thao tác thử lại | Chờ browser |
| 36 | Từ cuối đúng và Enter đang chờ | Hoàn thành đúng một lần | Đạt logic |
| 37 | Bấm Tiếp tục nhiều lần/retry complete | Không nhảy hai câu, trả lại cùng kết quả | Cấu hình k6 đã có; chưa chạy HTTP |
| 38 | Chuyển câu khi hint/check/audio chưa xong | Không mang trạng thái cũ sang câu mới | Chờ |
| 39 | Hoàn thành câu cuối → kết quả/lịch sử | Điểm, số câu và lần thử khớp dữ liệu lưu | Chờ |
| 40 | Luyện lại bài 100% | Giữ thành tích, tách lượt luyện lại | Catalog đã test; chờ làm bài trọn lượt |
| 41 | Mobile 360/390px, bàn phím mở | Ô đang gõ và nút cần dùng không bị che | Chờ browser |
| 42 | Con trỏ ở giữa chữ/chọn đoạn khi render | Không nhảy vị trí, không mất vùng chọn | Chờ DOM thật |
| 43 | Xoay màn hình/thay đổi viewport | Bản nháp và focus còn nguyên | Chờ browser |
| 44 | Chỉ bàn phím/trình đọc màn hình | Nhãn, focus, lỗi và trạng thái chấm dễ hiểu | Chờ |
| 45 | Sai → sửa đúng, loading, lỗi mạng | Phản hồi đúng ô, không báo lỗi cũ cho chữ mới | Chờ browser |
| 46 | Hai tài khoản và session ID của nhau | Dữ liệu tách biệt, truy cập sai bị từ chối | Chờ backend |
| 47 | Hai tab cùng một tài khoản/bài | Không mất tiến độ hoặc hoàn thành trùng | Chờ browser/backend |
| 48 | Host chuyển câu khi guest đang nhập | Đồng bộ có điều kiện, không ghi nhầm câu | Chờ room |
| 49 | Room reconnect/guest gửi draft nhanh | Giữ bản mới nhất theo đúng người/câu | Chờ room |
| 50 | Resume sau reload/rời bài | Đúng câu hiện tại, đáp án đã lưu và điểm | Cấu hình k6 một phần; chờ browser |

## Thứ tự thực hiện

1. Giữ baseline và 5 ca đỏ; bổ sung ca đối ứng trước khi sửa.
2. Xác nhận RACE-01/02/03 trên DOM thật với request trễ và gõ 0–120 ms/ký tự. Lưu video/screenshot, payload test, thứ tự sự kiện và request ID; không ghi thông tin đăng nhập.
3. Chạy các nhóm bàn phím/IME/paste và mobile trên Chrome/Edge; kiểm tra WebKit/Safari ở môi trường hỗ trợ. Lưu rõ trình duyệt nào chưa kiểm tra.
4. Kiểm tra backend/idempotency/ownership trên PostgreSQL và Redis riêng. So sánh số lần thử, bản ghi sự kiện, câu hiện tại và điểm, không chỉ HTTP 200.
5. Chạy smoke 1 VU. Dừng nếu script, dữ liệu, audio, auth hoặc invariant lỗi.
6. Chạy tải tăng dần tới 50 VU sau khi smoke và các lỗi High được xử lý; theo dõi độ trễ, lỗi, RAM/CPU/disk, hàng đợi và DB pool.
7. Chạy hồi phục tải xuống 10 VU, đối soát hành trình và invariant DB, rồi kiểm tra UI 1 người học trong môi trường đó.
8. Tổng hợp lỗi High/Medium/Low, cách tái hiện, bằng chứng, phạm vi đã/chưa kiểm tra. Việc sửa sản phẩm và deploy là bước tiếp theo, không nằm trong lượt chuẩn bị test này.

## Cấu hình tải đã cài trong dự án

Tận dụng k6 **2.2.0** đã có trên máy; không thêm dependency.

| Giai đoạn | Thời gian | VU |
|---|---:|---:|
| Kiểm tra riêng trước chiến dịch | 30 giây | 1 |
| Khởi động | 30 giây | 1 |
| Tăng / giữ | 30 / 60 giây | 1 → 10 / 10 |
| Tăng / giữ | 30 / 60 giây | 10 → 25 / 25 |
| Tăng / giữ | 30 / 600 giây | 25 → 50 / 50 |
| Giảm / hồi phục | 30 / 60 giây | 50 → 10 / 10 |

Chiến dịch chính: **930 giây (15 phút 30 giây)**, chưa gồm login, graceful stop tối đa 90 giây và cleanup. Mỗi VU dùng tài khoản/cookie/CSRF riêng. Tất cả VU thực hiện chép chính tả, tối đa 8 ô/câu, gửi nhanh sau mỗi phản hồi, nghe audio đúng thời lượng, mở đáp án/nghe lại khi cần, hoàn thành và đọc lịch sử. Một vòng là **một câu**, không phải một bài hoàn chỉnh.

Ngưỡng ban đầu: lỗi nghiệp vụ/mất dữ liệu/gửi trùng = 0; HTTP lỗi <1%; API p95 <1 giây, p99 <2 giây; tải audio p95 <3 giây. Đây là mục tiêu nghiệm thu, không phải kết quả đo. Lỗi nghiệp vụ dừng sớm sau cửa sổ đánh giá 30 giây; không tự tăng tải khi smoke thất bại.

Giữ guard hiện có: RAM trống ≥500 MiB, disk ≥10 GiB, dừng khi CPU ≥85% trong 6 mẫu; preflight RAM local ≥1.800 MiB. Manifest bắt buộc xác nhận DB/JWT/Redis tách biệt, rate limit bật, provider giả và đủ tài khoản. Chỉ cho phép `127.0.0.1:18282`; không có đường chạy lên domain thật trong profile này.

### Lệnh kiểm tra logic (không tạo tải HTTP)

```powershell
node --test src/test/js/practice-input-races.test.cjs
node --test src/test/js/practice-ux-load.test.cjs src/test/js/load-contracts.test.cjs
```

Lệnh đầu hiện trả mã 1 vì 5 lỗi đã nêu. Lệnh thứ hai kiểm tra cấu hình/hành trình tải với API giả lập, không chứng minh server chịu được 50 VU.

### Lệnh chạy môi trường tải local tách biệt

Điều kiện: PostgreSQL binary đã có, Redis mới/rỗng/riêng tại loopback 16480, cổng 15437/18282/18283/18380 trống. `RedisIsolationCheck` sẽ từ chối Redis có dữ liệu; không xóa Redis hiện có để vượt kiểm tra. Runner tạo cluster/database mới, 50 tài khoản test, build artifact riêng và tự dừng các process nó tạo. Lưu lại log/data và kiểm tra invariant/restore DB bằng bộ có sẵn.

```powershell
# Bước 1: smoke. Chỉ chạy bước 2 khi bước 1 đạt và lỗi High đã sửa.
pwsh -File scripts/run-local-evidence-smoke.ps1 -PracticeUX -Seconds 30 -PostgresBin 'C:/Users/HP/Tools/postgresql-18.4/pgsql/bin'

# Bước 2: tăng dần tới 50 VU, giữ 50 VU trong 10 phút.
pwsh -File scripts/run-local-evidence-smoke.ps1 -PracticeUX -Seconds 930 -PostgresBin 'C:/Users/HP/Tools/postgresql-18.4/pgsql/bin'
```

Không chạy hai lệnh song song. Mỗi lệnh tạo môi trường riêng nên Redis phải rỗng trước lượt kế tiếp; giữ nguyên dữ liệu lượt cũ để đối chiếu, dùng instance Redis thử mới nếu cần.

### Bằng chứng và điều kiện công nhận

- Runner giữ summary JSON, raw `.json.gz`, cấu hình/hash script+artifact, stdout/stderr, số liệu máy phát tải và Prometheus nếu endpoint sẵn sàng.
- Bắt buộc `practice_started = practice_finished = practice_sentences_completed`, đúng 50 VU đã hoàn thành ít nhất một câu, đủ logout. Runner chặn các số đếm thiếu/lệch.
- Trước khi công nhận tải duy trì: kiểm tra raw `vus` đủ cửa sổ 50 VU/600 giây, không gián đoạn/ramp-down ngoài dự kiến. Peak 50 và tổng số request không đủ chứng minh điều này.
- Đối soát invariant DB và số lần thử sau gửi lặp. WordSlot API không trả `attemptCount`; receipt replay và GET trạng thái chỉ chứng minh phản hồi nhất quán, không thay thế kiểm tra DB/lịch sử.
- Không suy diễn tải local trên máy HP thành sức tải VPS, độ mượt browser, chất lượng WebRTC hay throughput AI.

## Trạng thái bàn giao

Đã chạy smoke và chiến dịch 50 VU hoàn chỉnh; lượt cuối đạt threshold, 5.056 câu hoàn tất, đủ 50 logout, invariant và dump/restore DB đạt. Bộ JS mở rộng đạt 53/60, backend 48/49; còn 7 assertion race thất bại và một assertion catalog cũ. Đã xác nhận thêm lỗi vùng chọn trên browser. Các tình huống còn thiếu và bằng chứng nằm trong `artifacts/practice-ux-20261014/report.md`; không công nhận 50/50 ca UX đều đạt. Không thay logic sản phẩm hoặc deploy.
