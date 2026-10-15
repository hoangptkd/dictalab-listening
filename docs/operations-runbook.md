# DictaLab — hướng dẫn vận hành và phục hồi

Cập nhật 03/10/2026. Dùng cho VPS Windows hiện tại; không dừng WebLens hoặc các dịch vụ khác.
Mọi thao tác phục hồi phải có người vận hành, backup và đường dẫn đích đã xác minh. Không restore đè production để thử.

## Kiểm tra trước khi có sự cố

- Production app: loopback 18082; HTTPS 18443; management loopback 18183. Health phải UP.
- Ghi SHA256 JAR, phiên bản Flyway, trạng thái service, backup gần nhất và dung lượng trống.
- Chỉ truy cập metrics qua loopback hoặc SSH tunnel. Không mở Actuator, Prometheus hoặc Grafana công khai.
- Dashboard 12 panel và 7 alert rules đã kiểm thử. Nơi nhận cảnh báo được người dùng hoãn; chưa có bằng chứng gửi thật.
- Cấu hình giám sát có trong `ops/observability`; lần kiểm thử Grafana/Prometheus dùng tiến trình tạm, không đồng nghĩa đã cài dịch vụ giám sát liên tục.

## Website chậm hoặc lỗi

1. Kiểm tra health, RAM/CPU toàn VPS và tài nguyên riêng DictaLab. VPS dùng chung nên không quy mọi đỉnh CPU cho website.
2. Đối chiếu requestId/errorId trong phản hồi với JSON log. Không sao chép answer, token, cookie hoặc secret vào báo cáo.
3. Xem HTTP theo route, Hikari active/pending/timeouts, GC, tuổi queue, provider circuit/in-flight/rejected.
4. Nếu pool pending tăng, xác định truy vấn hoặc connection đang giữ; không tăng pool mù. PostgreSQL và bộ nhớ cũng có giới hạn.
5. Nếu CPU/RAM bị nghẽn, dừng đúng job thử tải của DictaLab trước; không taskkill mọi Java/Chrome/PostgreSQL.
6. Sau restart, warm-up bằng số ít tài khoản thử và kiểm tra độ trễ trước khi tăng tải. Các lần cold burst đã thất bại dù warm reads đạt.

## AI/TTS unavailable hoặc queue đầy

- 429 là giới hạn abuse/quota hoặc bận, không phải grade thành công. Tôn trọng Retry-After.
- Kiểm tra cấu hình provider và credential bằng trạng thái, không in giá trị. Không retry timeout mù vì provider có thể đã tính phí.
- Circuit mở phải được phục hồi bằng probe hữu hạn; permit và queue có giới hạn. Không nới queue để che overload.
- Conversation giữ pending answer trước gọi AI; gửi lại cùng requestId và cùng body, không tạo requestId mới để né pending.
- Listening giữ recall khi FAILED; dùng endpoint retry có quyền owner/admin và budget hiện hành. Không reset counter trực tiếp trong DB.
- Kết quả của worker đã mất lease không được ghi đè. Restart không chứng minh job đã hoàn tất; kiểm tra trạng thái terminal và report.
- TTS prefetch có thể thất bại; playback được phép thử lại. Không hiển thị grade hoặc audio giả để báo thành công.

## Email và outbox

- Outbox và token commit cùng transaction. Gửi SMTP ngoài transaction; mỗi email tối đa 5 attempts, lease 90 giây.
- Kiểm tra queue age, failure và cấu hình SMTP. Không ghi SMTP password hoặc link token vào log.
- SMTP là at-least-once: crash sau gửi trước commit có thể gửi trùng. Token dùng một lần bảo vệ thao tác tài khoản.
- Chưa kiểm chứng gửi thư SMTP thật. Nơi nhận cảnh báo vận hành không tự trở thành cấu hình SMTP nghiệp vụ.

## Backup, RPO và retention

- Task `DictaLab-Daily-Backup`: 02:30 giờ VPS, giới hạn 15 phút, không chạy chồng. Kiểm tra timestamp thực của marker và Task history.
- Backup định kỳ hiện là **DB-only**. Lịch một ngày là mục tiêu khoảng cách backup, không bảo đảm RPO 24 giờ khi task thất bại.
- Media nhất quán được snapshot ở cổng phát hành khi app dừng; chưa có media backup hằng ngày. Không hứa cùng RPO cho DB và media.
- Mặc định giữ 30 ngày và ít nhất 7 nhóm hoàn chỉnh. Manifest/checksum phải tồn tại trước retention. Không xóa thủ công riêng dump của nhóm.
- Backup định kỳ hiện ở cùng VPS. Một snapshot DB/media/JAR đã được sao chép sang HP tại `C:/Users/HP/.codex/backups/DictaLab/production-20261003-132241`, checksum dump/3 media/JAR đều đạt. Bản sao nằm ngoài Git và Maven target, chứa dữ liệu riêng tư; không công khai. Đây là bản sao một lần, chưa có lịch off-VPS hoặc SLA phục hồi thảm họa.
- Snapshot release DB overload mới tại `C:/Users/HP/.codex/backups/DictaLab/production-20261003-135358` có dump/media nhất quán cùng JAR `E39A4210…`; cả 5 checksum đạt. Giữ bản 13:22, không ghi đè bằng chứng phục hồi cũ.
- Secret DB được bảo vệ bằng DPAPI gắn máy/tài khoản; bản sao file không tự dùng được trên máy mới. Khôi phục secret qua kênh riêng, không đưa vào Git.

## Restore drill và phục hồi production

1. Chọn nhóm backup hoàn chỉnh; xác minh SHA256 dump, manifest, checksum media và phạm vi artifact/config.
2. Chạy `restore-drill-windows-vps.ps1` trên DB tạm độc lập, cùng phiên bản PostgreSQL phù hợp; thêm SmokeArtifact đã xác minh.
3. Yêu cầu không FK hỏng, không constraint chưa validate, đủ tham chiếu media, app health và registration/catalog/practice smoke đạt.
4. Giữ log, kết quả, thời gian; dọn đúng DB/media tạm bằng script. Không dùng production làm DB drill.
5. Khi phục hồi thật, mở maintenance window, dừng riêng DictaLab, lưu thêm bản hiện tại rồi thực hiện kế hoạch đích đã được duyệt.
6. Khởi động và xác minh DB, media, quyền truy cập, hành trình, backup marker; chỉ mở lại traffic sau kiểm tra.

Drill DB/media/app gần nhất mất **84,91 giây** với 7 users/36 sessions/3 media. Đây là thời gian drill nhỏ,
không phải RTO cam kết cho khôi phục VPS, DNS, TLS, tải lớn hoặc provider. Chrome profile/cookies không nằm trong backup DB;
bridge cần desktop session và xác minh đăng nhập/handoff riêng. Không khôi phục profile qua Git.

## Phát hành và rollback

- `update-windows-vps.ps1` yêu cầu preflight invariant, backup DB/media, restore/app smoke rồi mới đổi JAR.
- Giữ artifact cũ và checksum. Rollback JAR **không rollback schema**. Chỉ dùng artifact cũ sau test tương thích trên DB sao đã migrate.
- Nếu schema hoặc data không tương thích, dừng và lập phương án phục hồi; không sửa lịch sử migration hoặc xóa dữ liệu để ép khởi động.

## Tăng tải và mở rộng

- Tăng một bước hữu hạn, đo completion, p95/p99/errors/drops và tài nguyên; giữ kết quả thất bại. Không có số tối đa được chứng minh.
- Reads, hành trình luyện, AI, Room signaling và WebRTC media là các tải khác nhau. Số account đã seed không phải người đồng thời.
- Co-located k6 chiếm RAM/CPU cùng app. Sau thử tải cần máy sinh tải riêng ổn định để phân biệt nghẽn generator với server.
- Ưu tiên headroom và tách dịch vụ dùng chung khi số liệu cho thấy tranh chấp; không đổi sang cluster chỉ để có kiến trúc lớn.
- Nhiều app instance cần quyết định session store, Room routing/state, rate limit toàn cluster, job claim và media dùng chung.
  Không tăng replica khi Room còn in-memory mà chưa xử lý các điều kiện này.
- Trace exporter tắt mặc định; chỉ bật khi collector sẵn sàng. Hiện chưa có link durable qua restart hoặc instrumentation mọi JDBC statement.

## Kết thúc một lượt thử tải

Người dùng đã cho phép **một lượt thử tải tối 03/10/2026** sau khi tạm đóng MoreLogin và tool_auto_register-hoang,
giữ BilingualBackend/WebLens chạy. Lượt đã kết thúc: 300 mixed VUs/120 giây đạt, 500 bị CPU guard dừng;
xem `capacity-report-2026-10-03-evening.md`. Không có job/lịch tải còn chạy. Chỉ chạy lại khi được yêu cầu,
không dừng WebLens hoặc dịch vụ khác để lấy dư địa nếu chưa được cho phép.

Khi được yêu cầu tiếp tục, chạy controller một lần với `-WarmMixedOnly -RequireLowBackgroundLoad` và task
không trigger lặp, execution limit 30 phút; `-MixedUsers` cho phép chọn các bước hữu hạn (mặc định 100/200/300,
mỗi giá trị chia hết cho 5, budget script tối đa 1.000, không phải cap website). Preflight lấy mẫu trong 60 giây: ít nhất 10 mẫu, CPU trung bình
không quá 35%, đỉnh không quá 60%, RAM khả dụng ít nhất 1.800 MiB, disk queue không quá 2 và paging không quá
100 pages/s. Đây là ngưỡng bảo vệ thử nghiệm trên máy dùng chung, không phải giới hạn website. CIM có thể làm
thời gian thực dài hơn 60 giây; task vẫn phải có deadline. Nếu preflight không đạt, lưu CSV và kết thúc;
không tự lặp hay tự hẹn lại. Watchdog app tối đa 1.500 giây, dừng tại CPU >=85% sáu mẫu liên tiếp, RAM <500 MiB
hoặc disk trống <10 GiB. Mọi lỗi warm-up, threshold hoặc lỗi HTTP/nghiệp vụ được quan sát đều dừng tăng tải.

Thu JSON/stdout/stderr/resources trước khi ghi đè. Dừng đúng app/generator/controller/watchdog của lượt thử;
gỡ riêng hai task one-shot capacity/load, giữ daily backup. Đóng browser session/tunnel của test.
Xác minh production UP và dữ liệu không bị seed nhầm. Chỉ tắt máy HP khi công việc đã hoàn tất; không tắt VPS.

## Cơ sở dữ liệu tạm không đáp ứng

Lỗi không tạo được transaction hoặc không lấy được JDBC connection trả 503, `Retry-After: 3`, thông báo cố định
và errorId; không đưa URL DB, credential hay exception detail vào response/log cảnh báo. Lớp account validation
trước controller cũng được bảo vệ: không chạy handler cần quyền khi chưa xác minh tài khoản. Response đã gửi
không bị viết lại; lỗi khác vẫn đi theo xử lý hiện có. MDC/requestId được phục hồi sau request.

503 không chứng minh thao tác chưa ghi dữ liệu. Chỉ retry tự động thao tác đọc hoặc ghi có idempotency contract;
không tự phát lại POST tùy ý. Kiểm tra CPU/paging/disk/queue và pool wait trước khi tăng pool; xử lý tranh chấp
tài nguyên mới là bước khắc phục công suất, không nới threshold để biến một lượt lỗi thành đạt.
