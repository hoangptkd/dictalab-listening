# Redis và RabbitMQ trong DictaLab

## Phạm vi

Redis quản lý phiên JWT access/refresh token (xem [JWT/Redis](jwt-redis-auth.md)) và lưu token bucket
cho các nhóm auth, AI và upload; các backend dùng chung ngân sách nếu nối
cùng Redis database. RabbitMQ phân phối việc chấm nhớ ý Listening. PostgreSQL giữ đáp án, số lần
thử, quyền sở hữu, lease và kết quả. Thuật toán chấm điểm giữ nguyên.

HTTP session đã được thay bằng JWT và phiên token Redis. Room, email, TTS và Coach chưa chuyển
sang hạ tầng dùng chung. Room routing/state và media vẫn cần thiết kế riêng khi chạy nhiều backend.

## Chạy local

1. Thêm các biến mới từ `.env.example` vào `.env` hiện có; giữ nguyên các cấu hình và secret khác.
2. Đặt `RABBITMQ_USER`, `RABBITMQ_PASSWORD` bằng tài khoản local riêng. Nếu đã tạo volume RabbitMQ,
   đổi biến môi trường không tự đổi mật khẩu trong volume; cập nhật tài khoản broker tương ứng.
3. Mở Docker Desktop và dừng ứng dụng local trước khi bật cấu hình mới.
4. Chạy `pwsh -File scripts/run-local.ps1 -WithMessaging` (thêm `-WithCoach` nếu muốn).

Cổng mặc định: Redis `16379`, AMQP `15672`, trang quản trị RabbitMQ `25672`; tất cả bind
`127.0.0.1`. Redis và RabbitMQ dùng volume riêng, profile Compose `messaging`.
`docker compose --profile messaging up -d --wait redis rabbitmq` chỉ khởi động các dịch vụ.
Redis giới hạn dữ liệu 128 MiB với noeviction; container Redis/RabbitMQ có trần 256/512 MiB.
Đây là cấu hình local, không phải bằng chứng đủ tài nguyên cho VPS production.

Khi chạy backend bằng cách khác, cấu hình:

```text
DICTALAB_RATE_LIMIT_BACKEND=redis
REDIS_HOST=localhost
REDIS_PORT=16379
DICTALAB_LISTENING_QUEUE_ENABLED=true
RABBITMQ_HOST=localhost
RABBITMQ_PORT=15672
RABBITMQ_USER=<backend account>
RABBITMQ_PASSWORD=<backend secret>
```

`REDIS_URL` và `RABBITMQ_URL` là tùy chọn ghi đè host/port/credential; để trống nếu dùng các biến riêng.
Credential trong URL cần percent-encode. Dùng kết nối TLS và tài khoản có quyền tối thiểu nếu nối máy khác;
không công khai Redis, AMQP hoặc trang quản trị. Không ghi URL có password vào log/tài liệu.

## Redis rate limit

- Giữ nguyên cách chọn actor: user đã xác thực hoặc địa chỉ từ cấu hình proxy đáng tin cậy.
- Script Lua dùng đồng hồ Redis, đọc/cập nhật/ngăn vượt ngân sách nguyên tử. Refill theo phút,
  burst giữ các cấu hình `AUTH_RATE_BURST`, `AI_RATE_BURST`, `UPLOAD_RATE_BURST` hiện có.
- Hash và index sử dụng cùng hash tag `{dictalab-rate}`, TTL 10 phút. `RATE_LIMIT_MAX_ENTRIES`
  áp dụng toàn Redis namespace; các backend nên cấu hình cùng giá trị và dùng Redis database riêng.
- Redis lỗi hoặc hết bộ nhớ: thao tác ghi thuộc nhóm bảo vệ trả 503 và `Retry-After: 3`.
  Không tự chuyển sang bộ đếm RAM. GET và các luồng không thuộc nhóm này tiếp tục như hiện có.
- TTL tự dọn key khi không còn request. Đừng xóa namespace hoặc đổi Redis database trong lúc vận hành
  vì việc đó đặt lại ngân sách. Đây không phải hệ thống quota thanh toán hay giới hạn provider toàn cluster.

## RabbitMQ Listening

Bản ghi `listening_passes` đóng vai trò outbox cụ thể của Listening: trạng thái `PENDING` commit cùng
đáp án hoặc yêu cầu retry. Không cần bảng outbox/framework chung. Migration V25 chỉ thêm cột nullable
`grading_publish_after` phục vụ nhận quyền gửi và thời gian gửi lại, không backfill hay sửa kết quả cũ.

1. Dispatcher lấy việc tới hạn, giữ quyền gửi trong 15 giây bằng cập nhật có điều kiện.
2. Gửi message chỉ gồm UUID session/pass/retry revision/attempt, persistent, mandatory.
3. Chờ publisher confirm tối đa 5 giây; message không có tuyến hoặc bị broker từ chối vẫn giữ PENDING.
4. Sau broker xác nhận, việc chưa được xử lý có thể gửi lại sau 60 giây. Mất kết nối hoặc restart
   không xóa bản ghi PENDING. Việc chấm không cần giữ transaction DB trong lúc gọi broker/provider.
5. Consumer kiểm tra cấu trúc message, trạng thái, revision và attempt; nhận lease nguyên tử.
   Message trùng, cũ hoặc phiên đã xóa được bỏ qua. ACK sau khi xử lý và ghi trạng thái DB.
6. AI đã được gọi nhưng worker mất quá 120 giây: `FAILED / OUTCOME_UNKNOWN`, giữ recall để retry
   có chủ ý. Không gọi lại AI chỉ vì RabbitMQ redeliver. Recall rỗng có thể phục hồi tự động vì không gọi AI.

Queue mặc định `dictalab.listening.grading` nối exchange `dictalab.jobs`; DLQ
`dictalab.listening.grading.dlq`. Queue durable, tối đa 1.000 message; queue công việc từ chối publish
khi đầy. DLQ giữ tối đa 1.000 message lỗi, loại message cũ khi đầy; PostgreSQL vẫn giữ trạng thái nghiệp vụ.
Các tên có thể đổi bằng `DICTALAB_LISTENING_QUEUE`, `DICTALAB_LISTENING_EXCHANGE`, `DICTALAB_LISTENING_DLQ`.
Consumer concurrency dùng `LISTENING_WORKERS` (mặc định 2), prefetch 1.

DLQ dành cho message không hợp lệ/lỗi xử lý ngoài trạng thái nghiệp vụ. Lỗi AI được lưu FAILED trong DB,
không tự đưa toàn bộ đáp án lên broker. Lỗi DB giữ việc PENDING để dispatcher đối soát/gửi lại.
Không replay mù DLQ: xác minh trạng thái và revision hiện tại trước khi yêu cầu xử lý lại.

## Giám sát

Metrics hiện có `dictalab.jobs`, `dictalab.jobs.oldest.seconds` tiếp tục đo tuổi việc qua PostgreSQL.
Metrics mới: `dictalab.rate_limit.redis_failures`, `dictalab.listening.queue.published`,
`publish_failures`, `consumed`, `invalid`. Meter label không chứa user/session/IP/đáp án.
Xem queue depth và unacked qua trang quản trị RabbitMQ trên loopback.

Health Redis luôn tham gia vì xác thực JWT phụ thuộc Redis; health RabbitMQ bật khi bật queue.
AI outage vẫn đi theo trạng thái/fallback hiện có. Tắt Redis rate limit không tắt Redis xác thực.

## Kiểm thử

```powershell
pwsh -File scripts/test-messaging.ps1
```

Script tạo project Compose ngẫu nhiên, PostgreSQL/Redis/RabbitMQ riêng và cổng loopback ngẫu nhiên.
Kiểm thử dùng AI giả lập, không gọi provider tính phí. Bao gồm ngân sách Redis giữa hai instance,
TTL/trần số actor, lỗi Redis, lỗi nghiệp vụ phía sau filter, transaction rollback, message trùng/cũ,
gửi không có tuyến, phục hồi quyền gửi, worker có outcome không rõ, DLQ và phiên đã xóa.
Sau chạy, chỉ xóa container/volume dữ liệu thử của project đó. Báo cáo giữ ở
`target/messaging-test-<UUID>/surefire-reports`; không xóa chứng cứ thử tải khác.

## Phát hành và quay lui

Migration V25 cần lock ALTER TABLE dù chỉ thêm metadata. Giả định PostgreSQL 17/18 và triển khai
trong maintenance window; không cam kết lock-free hoặc zero downtime khi chưa biết tải/kích thước DB.
Kiểm thử migration trên DB sao, có backup và theo dõi lock wait trước production.

Để quay lại chế độ cũ:

1. Dừng backend/consumer liên quan; kiểm tra việc RUNNING và chờ hoàn tất hoặc hết lease.
2. Đặt `DICTALAB_RATE_LIMIT_BACKEND=memory`, `DICTALAB_LISTENING_QUEUE_ENABLED=false`, rồi khởi động lại.
3. Dispatcher PostgreSQL xử lý PENDING, không phụ thuộc `grading_publish_after`; việc billable mất lease
   vẫn FAILED/OUTCOME_UNKNOWN. Không tự reset attempts/retry budget.
4. Giữ queue và volume; đối soát message trước khi bật RabbitMQ lại. Không bật đồng thời artifact cũ
   và mới để thử quay lui trên production. Schema V25 giữ nguyên, không rollback bằng xóa cột.

Redis memory mode chỉ giới hạn từng JVM. Khi đổi chế độ, ngân sách rate limit có thể bắt đầu lại;
chuyển có kiểm soát. Việc phát hành production và chọn máy chạy broker cần kế hoạch tài nguyên riêng.
