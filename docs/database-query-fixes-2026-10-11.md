# Sửa truy vấn và transaction DictaLab — 11/10/2026

## Trạng thái

Đã sửa code, kiểm chứng bằng test, deploy lên VPS 184.194.89.90 và hoàn thành k6 đến 100 VU/30 phút. VPS thực tế vẫn là máy 1 GB RAM, 2 vCPU, pool tối đa 5 connections.

**Mục tiêu 100 VU/30 phút chưa đạt SLO:** 49,79 RPS, p95 1.768,69 ms, p99 2.034,97 ms. HTTP/business errors, pool timeout, OOM/restart và 6 invariants được kiểm tra đều không có lỗi. Mức cao nhất đạt toàn bộ thresholds đã kiểm thử là 20 VU/120 giây; chưa có mức nào đạt toàn bộ SLO trong 30 phút. Đây không phải chứng minh giới hạn tuyệt đối của web.

Artifact SHA-256: `d4c4db4684ef9357cc1744e9f12d21e35ca5f55fdf0672ffd09f31e7a9f6b2af`.
Rollback: `/var/lib/dictalab/backups/release-_nmcj_6d`.
Đã backup quiescent trước khi thay JAR; health sau deploy UP. 27 file SQL migration trong hai JAR có cùng hash; Flyway có 28 migrations tính cả migration Java. Hash cấu hình runtime trước/sau giữ nguyên. Production chạy Java 17; build/test local chạy JDK 21 với target Java 17.

Release này cũng đưa lên production các tối ưu read path đã hoàn thành local ở đợt trước: JWT bỏ account lookup trùng, scalar catalog progress, batch assessment/preview và tái sử dụng dữ liệu trong PracticeService. Chi tiết ở `docs/capacity-code-optimization-2026-10-11.md`. Vì vậy so sánh k6 trước/sau là tác động của toàn release, không riêng các bản sửa Listening/Coach bên dưới.

## Các bản sửa

| Nhóm | Kết quả | Bằng chứng/giới hạn |
|---|---|---|
| P1 Listening | Tái sử dụng session/unit trong một action; đọc lại pass sau UPDATE để trả dữ liệu mới | Đếm SQL thực thi: recall 4→3 SELECT; answers 5→3; review 8→6. Mỗi autosave vẫn có 1 UPDATE. Giữ parent lock, ownership, stage và validation |
| P1 Coach | Kiểm tra ownership/completed và report bằng một JOIN trước khi dựng lịch sử; cache miss bỏ assessment không được dùng | Cache hit 11→1 SELECT nghiệp vụ. Regression kiểm tra cache miss, report, error bank, AI fallback, khác user và session không hợp lệ |
| P1 Polling | Idle backoff 1/2/4/5 giây, wake sau commit; recovery/cleanup tách thành chu kỳ 30 giây | Test không mất wakeup khi enqueue trùng lúc poll; rollback không đánh thức; lost worker vẫn được recovery và không lặp paid call có outcome unknown |
| P1 Auth | Hash/verify ngoài transaction ghi; login/password change revalidate trạng thái trước khi commit; register hash một lần | Test latch với pool nhỏ, login-disable/reset/revoke, token consumed trong lúc hash và concurrent registration. JDBC đọc token kết thúc transaction riêng trước BCrypt, tránh connection bị giữ bởi synchronization của NOT_SUPPORTED |
| P2 Admin legacy | Một projection SQL trả đủ lesson/count/lock thay query theo từng lesson | 1 SELECT nghiệp vụ; đối chiếu tất cả sentence counts/content_locked với dữ liệu thật trong test. Giữ API legacy không phân trang |
| P2 Listening catalog | Aggregate theo audio một lần, distinct lesson/audio để không đếm trùng phiên | Test kết quả giống query cũ; PostgreSQL fixture 1.009 units và 100.000 practice sessions trả đúng trong 1 SELECT. Plan được lưu trong XML test; đây là dữ liệu local, không phải benchmark API production |
| P2 Audio | Một ownership JOIN lấy WAV; single-flight theo key bằng ConcurrentHashMap, DB I/O ngoài global monitor | Test 1 SELECT, khác user bị từ chối, cùng key dùng chung công việc và lookup chậm không chặn session khác |
| Metrics | Gauge S3/AI đọc snapshot thay truy vấn ngay lúc scrape | Refresh 30 giây; gauge có độ trễ tới chu kỳ refresh. Test scrape gauge S3 không phát SQL |

Không thêm dependency, migration, cache mới xuyên request hoặc đổi pool/VPS. Index chồng lặp, keyset pagination và chuyển WAV sang S3 được giữ nguyên: cần benchmark riêng chứng minh lợi ích trước khi đổi, nhất là unique constraints và API phân trang.

## Verification

- Test trước sửa tái hiện 2 budget failures; số đo JDBC/JPA thực thi là 4/5/8 SELECT ở Listening và 11 ở Coach cache hit.
- Test mới `JdbcQueryCounter` đếm SQL execute trên request thread, bao phủ JDBC và Hibernate; fixture nằm ngoài cửa sổ đo.
- PostgreSQL 18.6/Redis/RabbitMQ: 213 test cases nghiệm thu, không failure/error/skip sau khi sửa fixture và chạy lại test bị ảnh hưởng. Đây là hợp các suite được chọn, không phải toàn bộ repository.
- Lượt suite PostgreSQL lớn có một lỗi fixture expires_at < created_at; đã sửa fixture giữ constraint và chạy lại toàn bộ EmailOutboxRecoveryTest (9 cases pass), cùng 8 Coach cases pass. Báo cáo ban đầu vẫn được giữ để kiểm tra.
- H2: 120 cases của các suite được chọn, 115 pass và 5 PostgreSQL-only skips; thêm 8 Coach cases pass. Không dùng H2 thay bằng chứng locking PostgreSQL.
- Regression gồm 28 concurrency-matrix cases, Rabbit crash/network recovery, stale lease/fencing, email child process death/restart, transaction rollback và xác thực Redis.
- JavaScript production runner: 5 tests pass, kiểm tra thời lượng 30 phút và giữ nguyên thresholds. Release helper: 5 pass, 1 skip; artifact transfer self-test pass.
- UTF-8 và diff whitespace check đã pass trong phạm vi sửa.

Evidence: `target/query-optimization/` (trước sửa), `target/query-optimization-h2-verified/`, `target/query-optimization-coach-verified/`, `target/query-optimization-postgres-final/`, `target/query-optimization-postgres-checks/`, `target/query-optimization-release/`.

## Production load — kết quả cuối

Generator trên Windows, ngoài VPS; TLS được xác minh. Workload giữ 80% reader / 20% dictation VUs, think time 2 giây và playback mô phỏng 3 giây hai lần. AI/Rabbit flags vẫn false; không suy kết quả này sang AI, audio streaming hoặc Room. Redis được đo PING trên TLS qua probe riêng, không phải latency mỗi thao tác Redis trong ứng dụng. Neon CPU/RAM không có trong bộ đo SQL này.

SLO của `scripts/load/production.js`: p95 HTTP work <1.000 ms, p99 <2.000 ms; HTTP/business errors <1%; không pool timeout, OOM, unexpected restart hoặc vi phạm invariants. Mỗi route cũng có p95 <1.000 ms.

Trước deploy, mẫu idle 20,06 giây có 123 acquires (~6,13/s), HTTP counter tăng 1. Đây là chi phí nền có thể quan sát, không phải phép đo zero-traffic tuyệt đối.

| Stage | Thời lượng | RPS | p95 | p99 | HTTP errors | Kết quả |
|---|---:|---:|---:|---:|---:|---|
| 10 VU smoke | 30 giây | 8,80 | 821,38 ms | 939,91 ms | 0% | Đạt; mới khởi động |
| 20 VU | 120 giây | 18,77 | 475,18 ms | 593,87 ms | 0% | Đạt; 664/664 journeys |
| 50 VU | 120 giây | 42,96 | 804,05 ms | 1.092,01 ms | 0% | Chưa đạt toàn bộ: start p95 1.021,17 ms; 1.523/1.523 journeys |
| 100 VU | 120 giây | 50,48 | 1.806,34 ms | 2.064,29 ms | 0% | Chưa đạt latency tổng và 11 route; 1.791/1.791 journeys |
| 100 VU / 30 phút | 1.800 giây | 49,79 | 1.768,69 ms | 2.034,97 ms | 0% | Chưa đạt latency tổng và 11 route; 25.673/25.673 journeys |

Mẫu `idle in transaction` trong lúc tải có thể là khoảng giữa hai SQL của transaction đang chạy. Cần kiểm tra tuổi transaction và trạng thái sau tải để kết luận leak; không coi mọi snapshot khác 0 là lỗi.

Mức 20 VU: CPU trung bình 22,74%, tối đa 40,15%; RAM available thấp nhất 94,05 MiB, RSS cao nhất 515,69 MiB, swap cao nhất 380,05 MiB. Heap used tối đa 140,58/371,25 MiB; GC pause tăng 0,198 giây. Pool active tối đa 5, pending 0, timeout 0, acquire trung bình 3,10 ms. PostgreSQL không lock waiter/deadlock; Redis TLS PING 4 samples, không failure, p95 1,55 ms. JVM không restart. Đây là số đo trong cửa sổ work, không tính đăng nhập chuẩn bị và logout.

Mức 50 VU: CPU trung bình 25,58%, tối đa 43,55%; CPU steal tối đa 19,16%. RAM available thấp nhất 143,04 MiB, RSS 452,10 MiB, swap 416,50 MiB; heap used 143,41/371,25 MiB. Pool pending tối đa 15, acquire trung bình 46,67 ms, usage 48,34 ms, timeout 0. PostgreSQL lock waiters/deadlocks/rollback delta đều 0; Redis PING p95 1,68 ms, 4 samples không failure. JVM không restart. Generator CPU tối đa 43%, còn ít nhất 9.797 MiB RAM. API hint p95 998,43 ms và play 989,71 ms sát SLO; không dùng p95 tổng đạt để che route start chưa đạt.

Mức 100 VU/120 giây: CPU trung bình 20,57%, tối đa 35,79%; RAM available thấp nhất 136,20 MiB, RSS 452,81 MiB, swap 430,54 MiB; heap used 139,47/371,25 MiB. Pool pending tối đa 36, acquire trung bình 335,96 ms, usage 48,44 ms, timeout 0. PostgreSQL lock waiters/deadlocks/rollback delta đều 0; Redis PING p95 1,82 ms, 4 samples không failure; JVM không restart. Generator CPU tối đa 57%, RAM available ít nhất 9.834 MiB. 100 VU giữ đủ 120 samples, không interrupted journey. Throughput chỉ tăng 17,5% so với 50 VU dù concurrency gấp đôi, cùng hàng chờ pool tăng mạnh: đường DB/pool đang giới hạn throughput; CPU/heap không bão hòa trong cửa sổ này. Chưa tách được đầy đủ SQL, network/proxy Neon, transaction control và auth.

### Nghiệm thu 100 VU/30 phút

Cửa sổ work: 11/10/2026 23:03:44,889–23:33:44,889 (UTC+7). Generator ngoài VPS; có đủ 1.800 samples VU, tất cả đều 100. 89.624 request trong cửa sổ work; 25.673 journeys hoàn tất tính cả graceful completion, không interrupted. Có 656 refresh trong cửa sổ work, 657 tính cả phần graceful completion. Logout đủ 100 phiên, cleanup errors 0. K6 exit 99 do 13 latency thresholds không đạt, không phải runner crash.

| Chỉ số | Đo được | Đánh giá |
|---|---:|---|
| HTTP/business errors | 0% / 0% | Đạt <1% |
| p95 / p99 HTTP work | 1.768,69 / 2.034,97 ms | Không đạt <1.000 / <2.000 ms |
| CPU host trung bình / tối đa | 17,92% / 46,18% | Không có bằng chứng CPU bão hòa liên tục |
| CPU ứng dụng tối đa / steal tối đa | 33,26% / 31,36% | Steal là nhiễu cần theo dõi; không quy toàn bộ latency cho nó |
| RAM available thấp nhất / RSS cao nhất | 122,81 / 447,64 MiB | Còn headroom nhưng máy nhỏ |
| Swap cao nhất | 467,99 MiB | Có dùng swap; chưa đo paging đủ để quy là nguyên nhân chính |
| JVM heap used cao nhất / heap max | 163,16 / 371,25 MiB | Không bão hòa heap trong mẫu |
| GC pause / số GC tăng trong mẫu | 4,221 giây / 435 | Không thấy GC chiếm phần lớn thời gian |
| Pool active / pending tối đa | 5 / 37 | Pool đạt trần, có hàng chờ |
| Acquire / usage trung bình | 352,22 / 49,71 ms | Chi phí chờ lấy connection lớn |
| Pool timeout / unexpected restart | 0 / 0 | Đạt |
| PostgreSQL samples / sessions tối đa | 118 / 14 | Thống kê toàn database, không phải 14 app connections; app pool vẫn 5 |
| PostgreSQL lock waiters max / deadlocks delta | 0 / 0 | Không phát hiện trong samples |
| PostgreSQL rollback delta / probe max | 0 / 510,35 ms | Probe gồm mở kết nối và truy vấn; không phải query execution time |
| Redis TLS PING p95 / max | 1,72 / 2,26 ms | 59 probes, 0 failure; không phải histogram app Redis commands |
| Generator CPU max / k6 RSS max | 85% / 151,44 MiB | CPU toàn Windows có transient peak; chưa chứng minh mọi giới hạn generator bằng riêng số max |
| Generator RAM available thấp nhất | 9.057 MiB | Generator không thiếu RAM trong mẫu |

CPU/JVM/resource metrics bao phủ 352 samples trong cửa sổ work. Các counter delta bao phủ hai biên sample, không phải tracing chính xác từng request. PostgreSQL CPU/RAM phía Neon chưa đo được. Redis INFO dùng để quan sát dữ liệu managed, không suy ra RAM vật lý của máy Redis. RabbitMQ production không được nghiệm thu: cả hai queue flags vẫn false, không có broker throughput/ack/retry metrics của campaign này.

| API | Request trong work | p95 | p99 |
|---|---:|---:|---:|
| catalog | 25.650 | 1.758,64 ms | 2.025,91 ms |
| history | 25.618 | 1.728,38 ms | 1.993,07 ms |
| dashboard | 23.982 | 1.764,28 ms | 2.017,46 ms |
| start | 1.638 | 1.779,65 ms | 2.017,80 ms |
| resume | 1.638 | 1.791,63 ms | 2.011,95 ms |
| play | 1.638 | 1.820,92 ms | 2.049,84 ms |
| check | 1.634 | 1.857,90 ms | 2.142,18 ms |
| hint | 1.632 | 2.056,21 ms | 2.393,16 ms |
| replay-start | 1.631 | 1.868,72 ms | 2.206,26 ms |
| replay-complete | 1.626 | 1.782,37 ms | 2.053,99 ms |
| complete | 1.625 | 1.849,54 ms | 2.138,15 ms |

Tất cả 11 route nghiệp vụ có p95 vượt 1 giây. Auth refresh p95 1.267,31 ms; csrf-refresh p95 691,06 ms. Các route auth này chưa có threshold riêng trong script hiện tại.

| Đoạn của bài soak | RPS | p95 | p99 | HTTP errors |
|---|---:|---:|---:|---:|
| 0–5 phút | 50,60 | 1.787,96 ms | 2.061,14 ms | 0 |
| 5–10 phút | 50,42 | 1.735,93 ms | 2.012,55 ms | 0 |
| 10–15 phút | 50,42 | 1.714,36 ms | 1.985,90 ms | 0 |
| 15–20 phút | 50,11 | 1.727,17 ms | 1.958,57 ms | 0 |
| 20–25 phút | 48,33 | 1.814,61 ms | 2.092,88 ms | 0 |
| 25–30 phút | 48,87 | 1.803,67 ms | 2.098,73 ms | 0 |

Throughput nửa sau giảm nhẹ, không sụp đột ngột. Chưa có đủ bằng chứng để gán riêng dao động này cho dữ liệu tăng, CPU steal hay network.

### Sau tải và idle backoff

- Chiến dịch giữ lại 100 tài khoản tổng hợp, 365 practice sessions, 3.434 sentence attempts, 17.418 word attempts và 3.177 word events. Không xóa dữ liệu test hoặc dữ liệu người dùng.
- 6 invariant counters đều 0: duplicate active solo session, word/event count mismatch, replay mismatch, reveal mismatch, completed duration mismatch và incomplete completed lesson. Phạm vi là tài khoản tổng hợp của chiến dịch, không phải chứng nhận toàn bộ database.
- Snapshot sau tải: idle-in-transaction 0, tuổi idle transaction tối đa 0, lock waiters 0. Pool active/pending 0; service PID vẫn 94131, NRestarts 0.
- Cgroup `oom` và `oom_kill` đều 0. `sock_throttled` tích lũy 207, không có baseline trước test nên chưa quy được cho campaign hoặc dùng để kết luận nguyên nhân latency.
- Idle sau tải 20,03 giây: 19 acquires, khoảng 0,95/s; connection usage tăng 0,266 giây, HTTP delta 0, timeout delta 0. So với mẫu trước deploy khoảng 6,13/s, số acquire nền giảm khoảng 84,5%. Hai mẫu không phải A/B cô lập; mẫu trước có HTTP delta 1. Lợi ích polling không tự giải thích toàn bộ cải thiện k6.

### Bottleneck và bước tối ưu tiếp theo

Trong 1.795,25 giây giữa hai biên telemetry của soak, có 178.156 acquires, khoảng 99,24/s; connection usage tương đương trung bình **4,93/5 connections bận (~98,7% công suất pool)**. So với 49,79 HTTP request/s, bình quân xấp xỉ 2 acquires/request, gồm cả chi phí nền. Cộng với pending max 37 và acquire 352 ms, đây là bằng chứng đường DB/pool giới hạn throughput; không phải chứng minh PostgreSQL thiếu CPU hay mọi SQL thiếu index.

1. Đo thời gian SQL, transaction control, auth và network/proxy theo route trước khi chọn bản sửa. Hiện không có pg_stat_statements hoặc JDBC tracing production đầy đủ.
2. Ưu tiên thử gom catalog dictation: service hiện có 4 SELECT batch, có thể benchmark projection/aggregate ít round-trip hơn trên Testcontainers. Giữ progress, preview, personal-content filtering và quyền admin; thêm query-budget/result-equivalence tests.
3. Đo projection và transaction boundary cho account check trên đường JWT; phải giữ kiểm tra enabled/authVersion/role/Redis mới, không cache quyền hoặc bỏ revoke check để đạt tải.
4. Chỉ thử pool 5/8/10 có kiểm soát sau khi xác nhận budget Neon, DB CPU/RAM và VPS headroom. Đây là đề xuất, **chưa đổi cấu hình production** và chưa chứng minh pool lớn hơn đạt SLO.
5. Chạy lại cùng workload 100 VU/30 phút sau bản sửa tiếp theo, rồi nghiệm thu riêng dữ liệu lịch sử lớn, Listening, Room, AI/audio và broker. Không nới think time hoặc SLO để làm pass.

Chưa kiểm thử: >100 VU; 20/50 VU trong 30 phút; paid AI/provider failures trên production; actual audio streaming; Room; RabbitMQ production. Kết quả load hiện tại không chứng minh các phần đó.

Evidence cuối: `target/production-load-20261011/query-smoke-10.*`, `query-fixed-20.*`, `query-fixed-50.*`, `query-fixed-100.*`, `query-soak-100.*`. File `*.private-auth.json` và `private-fixture.json` chứa phiên/credential tổng hợp, được giữ riêng bằng ACL; không đưa vào Git hoặc chia sẻ. Chỉ chia sẻ analysis/manifest/report đã loại secrets. Các báo cáo cũ ghi “chưa deploy” là trạng thái tại thời điểm đợt local trước; kết quả deploy và load mới nằm trong báo cáo này.

Kiểm tra UTF-8 và whitespace trong phạm vi sửa đã pass; không sửa các thay đổi unrelated trong working tree.

### So sánh trước/sau cùng workload, 120 giây

| Mức tải | RPS trước → sau | p95 trước → sau | Pool pending max trước → sau | Acquire trung bình trước → sau |
|---|---|---|---|---|
| 20 VU | 17,42 → 18,77 | 697,13 → 475,18 ms | 1 → 0 | 9,24 → 3,10 ms |
| 50 VU | 35,38 → 42,96 | 1.098,48 → 804,05 ms | 22 → 15 | 101,50 → 46,67 ms |

50 VU tăng throughput khoảng 21,4%, p95 giảm 26,8%, acquire giảm 54,0%. Đây là hai lần đo production khác thời điểm, không phải A/B cô lập: network, CPU steal, cache và dữ liệu bài làm có thể thay đổi. Không quy toàn bộ chênh lệch cho một bản sửa; vẫn còn route start vượt SLO.
