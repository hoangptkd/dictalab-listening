# Backend portfolio evidence — 2026-10-01

## Phạm vi và kết quả

Đợt này hoàn thành ba việc: sửa tranh chấp membership của Room, chạy cùng bộ kiểm thử trên
PostgreSQL thật, và tối ưu các luồng đọc History/Dashboard bằng truy vấn có giới hạn và SQL aggregate.
Không deploy, commit hoặc push. Các thay đổi có sẵn trong workspace được giữ nguyên.

### 1. Room concurrency

- Tạo phòng và giữ chỗ membership được thực hiện nguyên tử, kể cả guest đang chờ duyệt.
- Một user không thể đồng thời tạo/tham gia hai phòng; tạo đồng thời không vượt giới hạn 200 phòng.
- Reject, leave và cleanup giải phóng đúng membership; cleanup phòng cũ không xóa membership phòng mới.
- Thứ tự khóa: khóa phòng trước, khóa membership sau. Creation chỉ lấy khóa membership.
- Không tạo thêm khóa cho room ID không tồn tại.

`RoomRegistryTest` có bốn kiểm thử, gồm 30 vòng tạo cùng user với 16 yêu cầu đồng thời,
đua tạo phòng ở mốc 199/200, đua join/create cùng guest, và giải phóng membership.
16 kiểm thử controller Room hiện có cũng đạt.

Giới hạn có chủ ý: registry nằm trong bộ nhớ của **một JVM**, mất khi restart và chưa hỗ trợ
chia sẻ Room giữa nhiều instance. Khóa membership chung phù hợp giới hạn hiện tại; chưa đo throughput
của khóa này. Không bổ sung Redis hay kiến trúc phân tán trong đợt này.

### 2. PostgreSQL và CI

- Profile `postgres-test` dùng PostgreSQL JDBC, không dùng H2.
- CI trên pull request vào `main` chạy JavaScript, H2/build và PostgreSQL service container
  `postgres:18.4-alpine`, rồi lưu riêng báo cáo H2/PostgreSQL.
- Job deploy phụ thuộc kiểm thử thành công, chỉ dành cho `main` ngoài pull request.
- Windows local dùng `scripts/test-postgres.ps1`: tạo cluster riêng dưới `target/`, chỉ bind
  `127.0.0.1`, mặc định cổng 15434, database/user `dictalab_test`, dừng cluster đó trong `finally`.
  Không thay đổi database/service đang chạy của ứng dụng. Log và cluster test được giữ lại để kiểm tra.
- Cluster tạm dùng trust authentication trên loopback; không dùng cấu hình này cho production.

Kết quả thực chạy local: **126 kiểm thử Java đạt trên H2 và 126 đạt trên PostgreSQL 18.4**;
mỗi lần có một benchmark opt-in được bỏ qua. **63 kiểm thử JavaScript đạt**.
PostgreSQL chạy Flyway từ schema trống, áp dụng thành công đủ **22 migration**, Hibernate validate đạt.
JAR đã build thành công. YAML workflow đã parse thành công, script PowerShell đã kiểm tra cú pháp.
Chưa chạy workflow trên GitHub Actions; chưa xác nhận job deploy ngoài local.

Giá trị thực tế của kiểm thử PostgreSQL: phát hiện năm ca lỗi mà H2 không bắt được.
Nguyên nhân là truyền `Instant` trực tiếp vào JDBC ở luồng Support/Account và fixture test.
Đã dùng `java.sql.Timestamp.from(...)` tại ranh giới JDBC, giữ nguyên thời điểm tuyệt đối,
kiểm tra quyền, hạn token, token dùng một lần và giới hạn yêu cầu. Bộ kiểm thử PostgreSQL sau sửa đạt.

### 3. History/Dashboard

- History lọc user/status/level **trước** phân trang trong database, không tải toàn bộ lịch sử.
  Thứ tự `completedAt DESC, id DESC` có tie-break ổn định.
- Dashboard tổng hợp toàn bộ phiên solo theo level trong database, giữ trung bình có trọng số
  theo số phiên; biểu đồ chỉ lấy tám phiên solo gần nhất, đúng số điểm UI đang hiển thị.
- Recommendation chỉ tải phiên solo mới nhất và ba phiên gần nhất cùng level;
  bài đã hoàn thành lấy bằng danh sách ID distinct.
- Listening assessment chọn tối đa mười first exposure mới nhất, mỗi lesson một phiên,
  loại redo/shared/khác user trước khi áp dụng giới hạn.
- Frontend History dùng phân trang server; giữ URL/filter, chặn response cũ ghi đè,
  sửa deep link vượt trang, cho thử lại sau lỗi và giữ focus bàn phím khi đổi level/trang.

Sáu kiểm thử `BackendReadIntegrationTest` kiểm tra phân trang/lọc/ownership, tham số sai trả 400,
weighted totals, dashboard rỗng, ngưỡng recommendation và evidence duy nhất trước limit.
Fixture dùng transaction riêng, flush/clear trước khi đọc. Kiểm thử History đặt ngân sách tối đa
ba prepared statement JPA và 12 entity cho một trang mười phiên cùng lesson.
Sáu kiểm thử JavaScript mới kiểm tra request, stale response, deep link, retry và focus.

## Benchmark trước/sau

Môi trường: Windows 11, AMD Ryzen 7 PRO 6850HS, khoảng 32 GB RAM, Temurin 21.0.12.1,
Maven 3.9.12, target Java 17, Spring Boot 4.1.1, Hibernate 7.4.5, PostgreSQL native 18.4.
Cùng máy và cùng harness, cluster riêng mỗi lần; baseline được đo trước khi sửa các luồng đọc.
Các ứng dụng khác trên máy không được dừng, nên latency có thể dao động.

| Phiên của một user | Endpoint | p50 trước/sau (ms) | p95 trước/sau (ms) | Giảm p95 |
|---|---|---|---|---|
| 1.000 | History | 27,226 / 14,988 | 45,019 / 19,471 | 56,7% |
| 1.000 | Dashboard | 34,722 / 33,655 | 45,265 / 42,051 | 7,1% |
| 10.000 | History | 120,262 / 62,571 | 141,306 / 63,758 | 54,9% |
| 10.000 | Dashboard | 216,502 / 138,624 | 220,574 / 145,792 | 33,9% |

| 10.000 phiên | Prepared statement JPA trước/sau | Entity tải trước/sau | JSON bytes trước/sau |
|---|---|---|---|
| History | 2 / 3 | 10.021 / 21 | 3.126.981 / 3.159 |
| Dashboard | 18 / 21 | 10.021 / 29 | 1.408.522 / 2.619 |

Số truy vấn tăng vì thêm count/phân chia các truy vấn có giới hạn. Lợi ích đo được nằm ở giảm
hydration, kích thước JSON và latency, **không phải giảm số truy vấn**.
Entity gồm phiên, lesson và user; số entity không phải số hàng database phải quét.

### Phương pháp và giới hạn

- `BackendBenchmarkTest` dùng MockMvc qua security filter/controller/service/serialization thật,
  principal khớp user trong database; không đo login hay thời gian trình duyệt/mạng.
- Seed 1.000/10.000 phiên COMPLETED solo, xoay tối đa 20 lesson có sẵn; first exposure chỉ cho
  lượt đầu mỗi lesson. Không seed sentence/word telemetry; không gọi AI bên ngoài.
- Mỗi endpoint/dataset: năm warm-up, 30 request **tuần tự**; p50 là mẫu thứ 15 và p95 mẫu thứ 29
  sau sắp xếp (nearest-rank). Không phải concurrent load test hay chứng minh số user đồng thời.
- Hibernate statistics chỉ đếm prepared statement JPA, không đếm JdbcTemplate. Không đo CPU,
  RAM sử dụng, throughput hay SLA production; không suy diễn những số đó từ kết quả này.
- API trước trả cả lịch sử; API sau trả trang mặc định mười mục. Dashboard trước gửi cả trend,
  sau gửi tám điểm UI dùng. Đây là tối ưu khối lượng công việc/response, không phải so cùng payload.
- Aggregate/count vẫn có chi phí theo số hàng liên quan; deep offset và lượng telemetry lớn chưa đo.
- Kiểm tra `EXPLAIN (ANALYZE, BUFFERS)` riêng với 10.000 phiên và `ANALYZE` trên cluster test:
  truy vấn History đại diện dùng backward scan trên `idx_sessions_user_status_completed`
  và incremental sort (0,130 ms); aggregate trả bốn nhóm sau quét 10.000 phiên (5,463 ms);
  evidence anti-join trả mười mục (2,785 ms). Đây là SQL đại diện, không phải timing endpoint,
  không phải exact SQL trace của Hibernate hay cam kết query plan production.
  Index hiện có được tái sử dụng; không thêm migration/index thiếu bằng chứng.

### Dữ liệu đo gốc

```csv
label,sessions,endpoint,iterations,p50_ms,p95_ms,jpa_statements,entities_loaded,response_bytes
before,1000,history,30,27.226,45.019,2,1021,312681
before,1000,dashboard,30,34.722,45.265,18,1021,142214
before,10000,history,30,120.262,141.306,2,10021,3126981
before,10000,dashboard,30,216.502,220.574,18,10021,1408522
after,1000,history,30,14.988,19.471,3,21,3157
after,1000,dashboard,30,33.655,42.051,21,29,2611
after,10000,history,30,62.571,63.758,3,21,3159
after,10000,dashboard,30,138.624,145.792,21,29,2619
```

## Chạy lại

Tại root repository, cần JDK/Maven/Node và PostgreSQL native Windows (`initdb`, `pg_ctl`, `createdb`)
trên PATH. Script tạo database tạm hoàn toàn mới; không dùng URL hay thông tin đăng nhập production.
Không chạy hai Maven task chung `target/` cùng lúc. Tránh `clean` trong script cluster vì cluster nằm
dưới `target/`; không xóa `target/` khi PostgreSQL test đang chạy.

```powershell
# H2 và build
mvn --batch-mode --no-transfer-progress verify
node --check src/main/resources/static/js/app.js
node --test src/test/js/*.cjs

# Toàn bộ suite trên PostgreSQL riêng, Flyway chạy từ đầu
& ./scripts/test-postgres.ps1

# Benchmark opt-in, ghi target/backend-benchmark-current.csv
& ./scripts/test-postgres.ps1 -MavenArgs @(
  '-Dtest=BackendBenchmarkTest',
  '-Ddictalab.benchmark=true',
  '-Ddictalab.benchmark.label=current',
  'test'
)
```

Có thể chỉ định `-PostgresBin 'C:\path\to\pgsql\bin'` hoặc đổi `-Port 15435` khi cổng mặc định bận.
Benchmark xác nhận `current_database() = 'dictalab_test'`, chỉ seed/xóa dữ liệu của user benchmark.
CSV trong `target/` bị Git ignore và mất sau `clean`; số liệu trước/sau đã được giữ nguyên ở tài liệu này.
Lệnh trên tái chạy **phiên bản hiện tại**. Đổi label thành `before` không khôi phục code trước tối ưu.
Để tái tạo baseline phải lưu phiên bản code trước tối ưu và chạy cùng harness ở checkout riêng;
đợt này baseline được đo trong working tree trước sửa, không tạo commit/snapshot Git cho baseline.

CI tạo PostgreSQL service container thay cho native cluster; profile nhận `TEST_DB_URL`,
`TEST_DB_USERNAME`, `TEST_DB_PASSWORD`. Chỉ trỏ profile này tới database test có thể ghi/xóa.

## Dòng CV có bằng chứng

Sau khi bạn hiểu và giải thích được các thay đổi, có thể dùng:

> Optimized Spring Boot/PostgreSQL read paths with server-side pagination and SQL aggregation;
> reduced local History p95 by 55% and Dashboard p95 by 34% on a 10,000-session synthetic dataset,
> with reproducible benchmarks and integration tests against PostgreSQL.

Kèm repository, tài liệu này và link CI khi workflow đã thực chạy. Không viết “chịu tải 10.000 user”:
10.000 ở đây là số phiên của một user, không phải số người dùng đồng thời.
