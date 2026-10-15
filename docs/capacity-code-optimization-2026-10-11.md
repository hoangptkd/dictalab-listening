# Tối ưu code chịu tải DictaLab — 11/10/2026

## Kết quả và phạm vi

Đã sửa code local, bổ sung regression/query-budget tests và build JAR thành công. Không thêm dependency, cache xuyên request, migration hay thay đổi cấu hình production. Chưa deploy hoặc chạy lại k6 trong đợt này; chưa chứng minh đạt 100 VU/30 phút.

## Bottleneck có bằng chứng trước sửa

Nguồn: `target/production-load-20261011/hint-fixed-20.analysis.json`, `hint-fixed-50.analysis.json` và telemetry máy chủ của cùng campaign.

| Mức đã đo | Thời gian | RPS | p95 HTTP | HTTP/business errors | Kết luận |
|---|---:|---:|---:|---:|---|
| 20 VU | 120 giây | 17,417 | 697,131 ms | 0 | Đạt thresholds của campaign |
| 50 VU | 120 giây | 35,375 | 1.098,483 ms | 0 | Không đạt latency SLO |

50 VU có p99 1.440,496 ms và 1.252/1.252 journeys thành công. Hikari pool tối đa 5 connection; pending đạt 22, xuất hiện trong cả 23 mẫu tải. Thời gian acquire trung bình tăng từ 9,24 lên 101,50 ms, còn usage trung bình gần như giữ nguyên (46,08 → 44,10 ms). Busy-time tương đương khoảng 97,3% công suất pool trong cửa sổ telemetry 50 VU. Đây là bằng chứng nghẽn pool, không phải bằng chứng PostgreSQL hết CPU.

Catalog/history/dashboard chiếm 85,5% request. CPU/JVM không cho thấy bão hòa hoặc OOM trong mẫu; CPU steal có lúc 28,5%, RAM khả dụng khoảng 110 MiB. Không thấy lock waiters hoặc tăng deadlocks trong mẫu PostgreSQL. Chưa đo riêng Redis latency.

Workload: 80% reader/20% dictation, think time 2 giây, mô phỏng playback 3 giây hai lần. Không có real AI calls, audio streaming, Room hoặc broker load. Không ngoại suy sang các workload đó.

## Code đã tối ưu

| Luồng | Trước sửa | Sau sửa | Giới hạn cách đọc số liệu |
|---|---:|---:|---|
| Xác thực JWT | 2 account SELECT/request | 1 | Vẫn kiểm tra Redis và DB mới trên mỗi request |
| Catalog | 5 SELECT | 4 | Service-only, chưa tính authentication |
| Assessment, 2 bài bằng chứng | 6 SELECT | 5 | Service-only |
| Assessment, 10 bài bằng chứng | 14 SELECT | 5 | Batch vocabulary thay N+1 |
| Dashboard không có lịch sử | 5 SELECT | 4 | Service-only |
| Dashboard có 10 bài bằng chứng | 11 SELECT | 10 | So với trạng thái đã batch assessment; không cộng trùng lợi ích |
| Kết thúc bài 20 câu | 22 word + 22 preview SELECT | 3 word + 3 preview SELECT | Chỉ hai nhóm query này, không phải tổng query/action |
| Replay | 2 word + 2 preview + 2 count SELECT | 1 + 1 + 1 | Tái sử dụng word slots và lesson size trong một action |
| Check/hint/reveal | 2 preview SELECT/action | 1 | Không thay persisted answer events |
| Hoàn thành câu chưa cuối bài | 3 sentence-count SELECT | 2 | State hiện tại và state câu tiếp theo |

Các thay đổi chính:

- `JwtTokenService` tạo authentication đã kiểm tra account; `AccountStateFilter` chỉ bỏ lookup trùng cho token nội bộ đó. Các đường authentication khác vẫn kiểm tra account. Không dùng HTTP session hoặc cache account qua request.
- `PracticeSessionRepository` trả scalar catalog progress một dòng/bài, không hydrate toàn bộ active sessions; chọn active mới nhất theo startedAt/id, giữ cả room sessions, completed flag và ownership.
- `ListeningAssessmentService`/`PreviewVocabularyService` gom vocabulary của tất cả lesson bằng chứng trong một query.
- `DashboardService`/`RecommendationService` dùng lại latest solo session đã có, không fetch trùng.
- `PracticeService` batch word attempts khi tính kết quả cuối bài; load proper names một lần/bài; dùng lại dữ liệu không đổi trong replay/check/hint/complete. Giữ transaction boundaries, aggregate locking, scoring và yêu cầu nghe lại sau reveal.

## Verification

Test mới: `src/test/java/com/dictalab/web/CapacityReadPathIntegrationTest.java`, được đưa vào profile reliability.

Đếm SELECT thực tế bằng Hibernate StatementInspector trên request thread; fixture được commit và flush/clear. Không dùng test-level transaction để che lỗi lazy loading hoặc rollback. RedisTokenStore được mock trong lớp query-budget; suite JwtRedisIntegrationTest riêng dùng Redis thật.

| Suite chọn lọc | Đã chạy | Fail/error | Skip |
|---|---:|---:|---:|
| H2: JWT, correlation, auth flow, catalog/history/dashboard, assessment, practice, admin và capacity | 60 | 0/0 | 1 PostgreSQL-only test |
| PostgreSQL 18.4/Testcontainers: capacity, backend read, assessment, practice, JWT/Redis, concurrency matrix | 78 | 0/0 | 0 |

Đây là các suite chọn lọc, không phải toàn bộ test của repository. ConcurrencyMatrix gồm 28 case, contender 2/5/10/20, chạy với pool test 30; không tương đương 100 VU trên production pool 5.

Đã kiểm tra: account disable/delete/password/email/role/logout-all bị từ chối ở request tiếp theo; role DB hiện tại được dùng; Redis revoke/outage fail closed; CSRF giữ nguyên; stale principal từ đường authentication khác bị từ chối. Catalog giữ latest progress/completed/ownership. Dashboard recommendation giữ tương đương. Kết quả bài 2/20 câu khớp history detail, accuracy đúng và query budget không tăng theo số câu. Existing practice/concurrency tests vẫn pass.

PostgreSQL-only fixture thêm 10.000 completed + 101 active sessions cho một bài: 4 SELECT, một dòng progress, đúng 25% và completed=true. EXPLAIN ANALYZE/BUFFERS/JSON được lưu trong XML test report. Mẫu cuối có execution time 23,484 ms sau ANALYZE hai bảng fixture; chỉ là một quan sát trên PostgreSQL local, không phải benchmark production hoặc latency API. Test không áp đặt threshold timing thiếu ổn định.

Lệnh chạy, PowerShell tại root repository:

```powershell
mvn -q '-Dtest=CapacityReadPathIntegrationTest,JwtTokenServiceTest,RequestCorrelationFilterTest,BackendReadIntegrationTest,ListeningAssessmentIntegrationTest,AuthFlowIntegrationTest,PracticeServiceIntegrationTest,AdminContentIntegrationTest' '-Ddictalab.build.directory=target/capacity-optimization' test
mvn -q -P reliability '-Ddictalab.testcontainers.postgres-image=postgres:18.4-alpine' '-Ddictalab.race-evidence=target/capacity-optimization-postgres/concurrency-matrix.csv' '-Dtest=CapacityReadPathIntegrationTest,BackendReadIntegrationTest,ListeningAssessmentIntegrationTest,PracticeServiceIntegrationTest,JwtRedisIntegrationTest,ConcurrencyMatrixTest' '-Ddictalab.build.directory=target/capacity-optimization-postgres' test
mvn -q '-DskipTests' '-Ddictalab.build.directory=target/capacity-optimization' package
```

Hai lệnh test trên đã chạy lại với source cuối, đều exit 0. Reports: `target/capacity-optimization/surefire-reports/` và `target/capacity-optimization-postgres/surefire-reports/`; race evidence: `target/capacity-optimization-postgres/concurrency-matrix.csv`. Testcontainers khởi động cả RabbitMQ, nhưng selection này không nghiệm thu broker load/recovery.

JAR: `target/capacity-optimization/english-dictation-0.1.0-SNAPSHOT.jar`.
SHA-256: `836BAF26220E655D1EC20633B07404C9CCE2704F6661A1F445EB02B57971D419`.
Build/test dùng JDK 21, compiler target Java 17; chưa kiểm chứng runtime Java 17. JAR chứa working tree hiện tại, bao gồm thay đổi có sẵn trước đợt tối ưu; không phải artifact chỉ chứa diff tối ưu.

UTF-8 check pass cho 11 file code/test/pom liên quan và báo cáo này. Diff whitespace check pass trong phạm vi thay đổi. Không sửa tài liệu khác bị hỏng hoặc thay đổi unrelated.

## Bước tiếp theo để nghiệm thu mục tiêu

1. Deploy đúng artifact, backup và có rollback; smoke test auth/catalog/history/dashboard/dictation trước khi đo.
2. Giữ cùng workload, SLO và production config; generator ngoài VPS. Chạy 20 → 50 → 100 VU ngắn rồi 100 VU/30 phút, đối chiếu với baseline 50 VU/120 giây.
3. Thu RPS, p95/p99 theo route, HTTP/business errors, Hikari acquire/usage/pending/timeouts, PostgreSQL query/locks, CPU/steal/RAM/swap, JVM/GC/restarts và Redis latency. Đánh dấu RabbitMQ/AI chưa test nếu vẫn tắt.
4. Kiểm tra invariants sau tải. Chỉ đạt khi p95 theo SLO hiện tại, p99 <2 giây, errors <1%, không DB timeout/OOM/restart và không vi phạm invariants.
5. Nếu còn nghẽn pool: đo từng SQL và network RTT trước. Chỉ thử điều chỉnh pool có giới hạn sau khi xác nhận Neon connection budget, DB CPU/RAM và headroom VPS; không tự tăng pool/thread lên 100. Chỉ thêm index hoặc cache khi có EXPLAIN/latency/invalidation evidence.

Giảm SELECT không chứng minh mức tăng RPS hay giảm latency tương ứng. Mục tiêu 100 VU/30 phút vẫn chưa đạt nghiệm thu; production hiện chưa có các tối ưu của đợt này.
