# Neon pool và tối ưu catalog/auth — 12/10/2026

Trạng thái: đã deploy tối ưu và hoàn thành so sánh pool5/8/10. Đã đạt100VU/120giây, nhưng CHƯA đạt100VU/30phút. Soak dừng khi phát sinh DB timeout;125/150VU chưa thử theo gate đã duyệt. Production hiệnUP, pool10, không có generator DictaLab đang chạy. Không nâng gói Neon hoặc VPS.

## Kết luận nghiệm thu

- Mức cao nhất đã pass đầy đủ trong đợt này:100VU/120giây,95,40RPS, p95/p99=577,97/779,48ms, HTTP/business errors0%, DBtimeout/OOM/restart0. Không coi đây là giới hạn tối đa tuyệt đối hoặc kết quả30phút.
- Soak đầu hỏng vì pin yếu/hibernate máy Windows, không phải backend. Đợt chạy lại bị dừng sau khoảng671giây evidence vì8DBconnectiontimeouts/8HTTP503; không đủ30phút. Không loại các đợt hỏng khỏi báo cáo.
- Bottleneck mới: SerialGC major pause11,319giây trong lúc paging mạnh/RAMavailablemin57,75MiB, kéo theo pool cạn. Chưa có bằng chứng Neon chạm compute/connection limit; nâng DB không phải giải pháp duy nhất hoặc đã được chứng minh cần thiết.
- Cả6data invariants sau các đợt đều0; synthetic data được giữ, không xóa. Sau soak2 đã logout100/100, lỗi cleanup0%; idleTx/lockwaiters0. Production tiếp tục dùng artifact tối ưu và pool10; chưa đổiGC/heap/swappiness/service memory limit.

## Phạm vi và môi trường

- Production: https://184.194.89.90, VPS 1 GB RAM / 2 vCPU, Java 17; load generator Windows bên ngoài VPS.
- Neon project `spring-waterfall-47654398`, branch `production`, endpoint `ep-royal-truth-b51aodjz`, AWS Ohio; Free, autoscale 0,25–2 CU, khoảng 1–8 GB RAM.
- Console: 894 direct / 10.000 pooled connections. SQL: max_connections=901, superuser reserved=4; role/database connection limit=-1. Các giá trị này không phải throughput cam kết. Compute dùng chung 4 database, phải giữ headroom cho workload khác.
- Billing: 100 CU-hours/project/tháng và 1 GB storage; Overview lúc chuẩn bị hiển thị 11,1 CU-hours đã dùng. Chưa nâng gói hoặc đổi compute Neon.
- Workload: 80% reader VU / 20% dictation VU, think 2 giây, playback mô phỏng 3 giây hai lần. Không gọi AI thật, không streaming audio, không tải Room hoặc broker production. Hai queue flags production vẫn false.
- SLO giữ nguyên: HTTP work p95<1.000 ms, p99<2.000 ms, từng 11 business route p95<1.000 ms; HTTP/business errors<1%, cleanup/session accounting đầy đủ.

## Thay đổi đã triển khai

1. Catalog: 4 SELECT batch thành 2 SELECT, gồm summary/progress và preview. Scalar sentence subqueries tránh nhân hàng; completed history aggregate một lần; newest active theo started_at rồi id, bao gồm room sessions. Visibility/personal-content filter nằm trong SQL. Thứ tự display_order có id làm tie-break.
2. JWT/account filter/refresh: projection lấy id/email/displayName/role/enabled/authVersion. Mỗi request vẫn đọc tài khoản mới và kiểm tra Redis; không cache quyền, không bỏ revocation/CSRF. Đường projection một SELECT không mở transaction CRUD read-only riêng. Write locks không thay đổi.
3. Timer `dictalab.auth.lookup` (postgres/redis) và `dictalab.catalog.read` (summary/preview), không gắn user ID hoặc secrets vào metrics. Thời gian timer gồm repository/hydration và chờ connection; không coi là PostgreSQL execution time.
4. Công cụ đổi pool chỉ cho 5/8/10, backup config trước sửa, kiểm tra health và effective Hikari max; tự khôi phục config khi failure. Không truyền credentials qua process arguments.
5. Mỗi đợt k6 ghi artifact hash và pool thực tế; sampler bổ sung paging và cgroup memory-event counters. Workload/thresholds không nới.

Artifact production: `df703246ff9db6a4670a90fbdc86ede9e8988243cc283a2fdb8d9a2bb57249e1`.
Rollback: `/var/lib/dictalab/backups/release-i7d8ktde`. Release dừng service, backup quiescent, verify hash, activate và health check. Không thêm dependency hoặc migration.

## Kiểm chứng local

- Baseline PostgreSQL 18.6: 19 test read-path pass trước sửa.
- Bản cuối: Java 17, PostgreSQL 18.6/Redis/RabbitMQ Testcontainers, 93 test / 0 failures / 0 errors / 0 skipped, 10 suite liên quan. Bao gồm JWT real Redis, query budgets, concurrency, deadlines/rollback và infrastructure.
- H2 bản cuối: 21 pass, 1 PostgreSQL-only test skipped; bộ bổ sung trước đó 33 pass, 1 skipped. Không cộng các lần rerun thành số test độc lập.
- SQL catalog budget 2, empty catalog 1; đối chiếu với implementation cũ cho user/admin/anonymous, preview/audio, bài rỗng, draft/private, tiến độ và active ties. Đọc lại commit/rollback/unpublish pass.
- Auth JDBC controls baseline: setReadOnly(true), setAutoCommit(false), commit, setAutoCommit(true), setReadOnly(false); projection: không có các controls này, vẫn một SELECT. Đây là JDBC method evidence, không phải packet trace.
- EXPLAIN fixture 10.000 completed/101 active: baseline 8,980 ms; bản scalar EXISTS đầu 37,684 ms do quét lịch sử lặp theo bài; đã sửa sang completed aggregate, bản cuối 4,282 ms. Các sample local khác thời điểm; không coi là benchmark production hoặc ratio cam kết.
- Pool/config/release tool checks: 25 pass / 1 POSIX skipped ở Windows; 23 pass ở Linux bao gồm real atomic replace. Transfer self-test pass, 7 JS load runner/report tests pass (bao gồm strict work SLO và CPU initial tick). UTF-8 check 12 file pass.
- Hai lượt lỗi đã sửa: import test thiếu; JDBC control instrumentation ban đầu trộn vào SQL list làm EXPLAIN test lấy sai statement. Bản cuối tách controls khỏi SQL list và rerun pass.

## Baseline production trước deploy

Evidence: `target/production-load-20261011/pre-neon-plan-50.*`. Artifact cũ `d4c4db4684ef9357cc1744e9f12d21e35ca5f55fdf0672ffd09f31e7a9f6b2af`, pool 5.

| VU / thời gian | RPS | p95 / p99 | HTTP errors | SLO |
|---|---:|---:|---:|---|
| 50 / 120 giây | 43,39 | 806,17 / 1.112,96 ms | 0% | Pass tất cả thresholds |

5.207 work requests; pool pending max 22, acquire avg 52,83 ms, usage avg 49,39 ms, timeout 0. CPU avg/max 16,79/44,47%, steal max31,31%; available RAM min247,70 MiB, RSS max329,16 MiB, heap used max170,64/371,25 MiB, swap max511,94 MiB. PostgreSQL lock waiters/deadlocks/rollback delta0, Redis4 probes không failure, restart0. Đợt cũ 50VU có route start vượt SLO; khác biệt theo thời điểm nên không coi 50VU là giới hạn tuyệt đối.

Neon Monitoring DOM có lỗi tooltip `Invalid Date`, nhưng screenshot hiển thị chart và hover cho số liệu. Mẫu 10:29:49 (UTC+7) trong baseline: CPU used0,05/allocated0,25 vCPU; RAM used369,81MB, cached399,77MB, allocated1,07GB. Đây là một mẫu compute chung, chưa phải p95/max toàn window. Pooler client/server metrics báo thiếu dữ liệu; không suy ra số 0.

## Kết quả sau deploy

| Artifact mới / pool | VU / thời gian | RPS | p95 / p99 work | HTTP errors | SLO |
|---|---|---:|---:|---:|---|
| Mới restart / 5 | 50 / 120 giây | 44,40 | 717,98 / 1.509,09 ms | 0% | Start/resume p95 chưa đạt |
| Warm / 5 | 50 / 120 giây | 46,40 | 611,13 / 825,93 ms | 0% | Pass tất cả thresholds |
| Warm / 5 | 75 / 120 giây | 59,27 | 909,12 / 1.171,89 ms | 0% | Resume/hint và work replay-start chưa đạt |
| Mới restart / 8 | 50 / 120 giây | 47,25 | 574,56 / 1.235,48 ms | 0% | Start p95=1.419,46ms chưa đạt |
| Warm / 8 | 50 / 120 giây | 48,68 | 485,23 / 678,68 ms | 0% | Pass k6 và strict work SLO |
| Warm / 8 | 75 / 120 giây | 72,19 | 538,31 / 746,26 ms | 0% | Pass k6 và strict work SLO |
| Warm / 8 | 100 / 120 giây | 91,53 | 704,56 / 1.058,88 ms | 0% | Resume p95=1.139,42ms chưa đạt |
| Mới restart / 10 | 50 / 120 giây | 47,57 | 533,13 / 1.263,01 ms | 0% | Start chưa đạt |
| Warm / 10 | 50 / 120 giây | 48,53 | 472,61 / 690,95 ms | 0% | Pass k6 và strict work SLO |
| Warm / 10 | 75 / 120 giây | 72,14 | 518,27 / 727,25 ms | 0% | Pass k6 và strict work SLO |
| Warm / 10 | 100 / 120 giây | 95,40 | 577,97 / 779,48 ms | 0% | Pass k6 và strict work SLO |
| Warm / 10, soak2 bị dừng | 100 / ~671 giây evidence, dự kiến1.800 | Không nghiệm thuRPS30phút | 495,15 / 777,94 ms (partial raw) | ~0,01254% (partial raw) | Không đạt:8DBtimeouts; không đủ30phút |

Evidence `optimized-p5-50.*`: start p95 1.404,15 ms, resume1.154,50ms; catalog p99 4.744,55ms trong đợt đầu sau restart. CPU avg44,21%, max83,49%, appCPUmax77,74%; paging pswpin+10.752/pswpout+14.218 pages và major faults+6.741, không OOM/restart/DB timeout. Pool pending37, acquire37,81ms, usage42,32ms. Auth PG65,01ms/Redis3,35ms, catalog summary17,01ms/preview17,21ms; timer gồm pool wait, không chỉ SQL.

Windows có một k6 của dự án khác chạy trong một phần window; CPU toàn máy max87%, generator còn12,6GiB RAM. Không dừng process dự án khác, không coi phép đo này là A/B cô lập. Runner chặn generator DictaLab trùng, vẫn ghi tài nguyên toàn máy.

Warm50 evidence `optimized-p5-warm-50.*`: pending21, acquire27,34ms, usage40,89ms, CPUavg22,73/max46,90%, appCPUmax32,81%; available RAMmin104,37MiB, RSSmax492,59MiB, heap148,92/371,25MiB. Paging vẫn có: pswpin+1.275/pswpout+10.205 pages; không OOM/restart/timeout. AuthPG47,18ms/Redis2,20ms, catalog summary15,86ms/preview15,77ms. So baseline50 cùng120s: RPS+6,93%, p95-24,19%, acquire-48,25%; hai phép đo khác thời điểm, chưa cô lập network/steal/cache/data growth.

Warm75 evidence `optimized-p5-warm-75.*`: 7.112 work requests; acquire122,52ms, usage40,57ms, pending35. Pool sử dụng trung bình4,83/5 (~96,6%) trong các ranh giới telemetry. AuthPG152,29ms/Redis2,08ms; catalog summary15,59ms/preview15,17ms. Resume p95=1.194,34ms, hint=1.219,72ms, work replay-start=1.001,85ms. CPUavg21,75/max37,39%, available RAMmin144,68MiB; OOM/restart/DBtimeout0. PostgreSQL deadlocks/rollback/lockwaiters0, Redis4 probes không failure, logout75/75. Nghiệm thu yêu cầu cả k6 thresholds lẫn SLO trong đúng work window; không dùng grace window để che route chậm.

Sau cold50 và warm75: 6 invariants0, idle-in-transaction0, lockWaiters0. Pool8 đã được áp dụng và kiểm tra effective max=8; backup config `/var/lib/dictalab/backups/pool-config-0c47c45a257c4dd8b405b1a03871469d.env`. Đang test pool8; chưa nghiệm thu pool tối ưu hoặc100VU/30phút cho bản mới.

Pool8 cold50: acquire11,23ms, usage41,79ms, pending32; CPUavg45,99/max85,62%, RAMavailablemin128,35MiB, RSS468,30MiB. Paging+5.057/+6.430pages, majorfaults+1.936, socket throttling+22; OOM/restart/timeout0. Sau tải, 6 invariants0 và idleTx/lockwaiters0. Pool8 warm50: acquire5,82ms, usage39,28ms, pending6; CPUavg22,70/max36,84%, RAMavailablemin146,53MiB, RSS451,02MiB, heap152,95/371,25MiB. AuthPG21,88/Redis1,91ms, summary15,33/preview14,99ms. Paging+822/0pages, socket throttling0; logout50/50, interrupted0 và success100%. Cả hai evidence `optimized-p8-50.*` và `optimized-p8-warm-50.*` được giữ riêng, không bỏ cold failure. Neon sample11:10:22: CPU0,07/allocated0,25vCPU; chỉ là một mẫu, compute dùng chung.

## Đối chiếu pool và tài nguyên

Pool8 warm75: 8.663 requests, logout75/75, success100%, interrupted0; pending11, acquire12,39ms, usage38,51ms, mean busy5,59/8. CPUavg24,14/max47,03% (appCPUmax34,71%), stealmax22,15%; RAMavailablemin129,79MiB, RSS453,75MiB, heap154,79/371,25MiB. Paging+247/0pages, majorfaults112, socket throttling0, OOM/restart/timeout0. AuthPG28,87/Redis1,56ms, summary15,01/preview14,53ms. PGlockwaiters/deadlocks/rollback0; Redis4probes0failure. Evidence `optimized-p8-warm-75.*`. Đang kiểm tra100VU120s trước khi so sánh pool10 và soak30phút.

CPU reporting loại riêng initial tick của sampler vì lần đọc đầu chưa có khoảng đo đầy đủ; raw tick100% vẫn được giữ. 22 CPU samples còn lại của pool8/75 cho max47,03%. Có runnable test chứng minh mẫu100% thật ở những interval sau không bị loại. Các analysis trước đã được tạo lại; HTTP metrics và kết luận SLO không thay đổi. Invariant evidence sau pool8/75: `target/neon-pool-20261012/invariants-after-p8-75.json`, cả6zero, idleTx/lockwaiters0.

Pool8 warm100: 10.984 requests, logout100/100, success100%, interrupted0; pending32, acquire34,14ms, usage38,41ms, mean busy7,08/8. CPUavg29,03/max67,99%, appCPUmax52,65%, stealmax46,16%; RAMavailablemin125,34MiB, RSS458,21MiB, heap155,84/371,25MiB. AuthPG54,16/Redis1,79ms, summary14,75/preview14,49ms. OOM/restart/timeout/socket throttle0, Redis4probes0failure, PGdeadlocks/rollback/lockwaiters0. Sáu invariants và idleTx sau tải đều0 trong `invariants-after-p8-100.json`. Đang chuyển pool10 để so sánh; pool8 chưa đáp ứng mục tiêu100VU theo SLO từng API.

Pool10 đã được áp dụng với backup config `/var/lib/dictalab/backups/pool-config-451bbf37ae364bed861f3056e6b879a2.env`. Cold50 evidence `optimized-p10-50.*`: acquire4,24ms, usage41,39ms, pending16; CPUavg43,77/max72,25%, RAMavailablemin108,51MiB, RSS495,18MiB, heap195,81/371,25MiB. Paging+3.121/+2.780pages, majorfault933, socket throttling32; OOM/restart/timeout0. AuthPG21,00/Redis4,03ms, summary16,08/preview16,29ms. Đang kiểm tra warm50; không đổi compute Neon.

Pool10 warm50 evidence `optimized-p10-warm-50.*`: 5.823requests, acquire4,34ms, usage39,50ms, pending6; CPUavg24,25/max44,58%, RAMavailablemin98,21MiB, RSS500,82MiB, heap195,57/371,25MiB. Paging+195/0pages, majorfault104, socket throttling0, OOM/restart/timeout0. AuthPG20,77/Redis2,75ms, summary15,82/preview14,92ms. Logout50/50, success100%, interrupted0; PGdeadlocks/rollback/lockwaiters0, Redis4probes0failure. Đang kiểm tra75VU.

Pool10 warm75 evidence `optimized-p10-warm-75.*`: 8.657requests, acquire10,45ms, usage39,14ms, pending17, mean busy5,67/10. CPUavg24,63/max64,24%, appCPUmax51,72%, stealmax41,15%; RAMavailablemin97,75MiB, RSS502,44MiB, heap190,24/371,25MiB. Paging+136/+378pages, majorfault183, socket throttling0, OOM/restart/timeout0. AuthPG27,37/Redis2,67ms, summary15,27/preview15,17ms. Logout75/75, success100%, interrupted0, PGdeadlocks/rollback/lockwaiters0, Redis4probes0failure. Đang kiểm tra100VU.

## Cache

Chưa thêm cache: catalog summary/preview chỉ khoảng15ms mỗi phần, trong khi chờ pool giảm rõ khi tăng5→8→10. Giữ đường đọc trực tiếp để tránh thêm RAM và invalidation/cross-instance consistency; progress/auth/visibility không dùng TTL cache. Cache có thể xét lại khi catalog lớn hơn hoặc profiling mới chỉ ra metadata SQL là bottleneck, không tự loại bỏ auth/revocation để đạt tải.

## Pool được chọn và nghiệm thu dài hạn

Chọn pool10: nhỏ nhất trong5/8/10 đã thử đạt100VU/120s theo cả k6 lẫn strict work SLO. Đây không phải chứng minh pool9 không đạt, hoặc10 tối ưu cho mọi workload. Chưa đổi gói/compute Neon. Pool8 đạt75VU nhưng100VU resume vượt SLO; pool5 đạt50VU nhưng75VU thất bại. Khác thời điểm, CPU steal/Neon compute chung/network không cô lập; không coi chênh lệch là tác động nhân quả thuần của pool.

Pool10 warm100: 11.448requests, mean busy7,40/10, pending21, acquire14,62ms, usage38,73ms. CPUavg27,59/max51,21%, appCPUmax36,73%, stealmax27,99%; RAMavailablemin97,54MiB, RSS495,01MiB, heap197,51/371,25MiB. Paging+335/0pages, majorfault204; OOM/restart/socket throttling/DBtimeout0. AuthPG31,30/Redis2,20ms, summary15,01/preview14,58ms. PGlockwaiters/deadlocks/rollback0, Redis4probes0failure. Logout100/100, interrupted0, success100%; evidence `optimized-p10-warm-100.*`.

Sau khi100VU/120s đạt, đã thử bài100VU/1800s. Chỉ khi nghiệm thu30phút đạt và còn headroom mới thử125→150VU/120s; gate này chưa đạt nên chưa tăngVU.

Đợt `optimized-p10-soak-100` bị gián đoạn phía generator sau khoảng10phút40giây, không nghiệm thu30phút: Windows System events524/42 báo critical battery và sleep12:13:32; event1 báo wake12:59:23. K6 báo unreachable network, SSH sampler bị reset và runner dừng vì quá bounded runtime; không có summary hoàn chỉnh. Không coi các lỗi mạng này là lỗi backend hoặc bỏ evidence. Sau gián đoạn VPS vẫnUP, PID102403/NRestarts0, sáu invariants/idleTx/lockwaiters0. Máy local hiện đã cắm nguồn; runner được bổ sung kiểm tra AC cho bài>=10phút và keep-awake theo thread, reset trong finally; không đổi power plan hay tắt bảo vệ pin yếu. Chỉ chạy lại bằng phiên mới sau khi logout100phiên cũ; không retry request ghi có kết quả không chắc chắn.

Cleanup đợt gián đoạn đã đạt100/100logout, cleanup failures0%, HTTP0%; evidence `target/neon-pool-20261012/interrupted-cleanup.summary.json`. Bắt đầu lại bằng label mới `optimized-p10-soak2-100`, không ghi đè đợt hỏng. Chrome đóng/mất kết nối sau hibernate, nên chart chỉ có các screenshot đã lưu; sampler PostgreSQL/Redis/JVM tiếp tục dùng SSH độc lập.

### Soak2: bằng chứng thất bại và hồi phục

- Work bắt đầu13:08:25(UTC+7). HTTP failure đầu sau557,302giây (~phút9:17). Log backend13:17:43 ghi Hikari timeout3.310–3.469ms, total10/active10/idle0/waiting14; lỗi được map503. Đã dừng riêng PID24344 (generator DictaLab), không dừng process dự án khác. K6 bản cài hiện tại không bật REST control mặc định; force-stop làm thiếu full summary và có thể mất raw buffered tail. Runner cũng dừng sampler sau bound; không giả mạo summary hoàn chỉnh.
- Raw còn đọc được671,559giây,63.790request, status200=63.550/204=232/503=8; HTTP error~0,012541%, business~0,012541%. p95=495,15/p99=777,94ms và các route p95 dưới1giây trong phần raw đọc được. Đây chỉ là diagnostic partial, không phải pass soak hoặc số liệu toàn bộ30phút. Parser dùng flush cho truncatedgzip, có1partialJSONline bị bỏ; chưa đảm bảo không mất phần cuối.
- Telemetry trong phần quan sát:130samples, DBtimeoutdelta8, GCpause totaldelta13,426s, RAMavailablemin57,75MiB, RSSmax560,50MiB, OOM/restart0. Paging+71.841swap-in/+64.001swap-outpages, majorfault+41.753; socketthrottlingdelta278. Pool bị timeout trong lúcCPU không bão hòa liên tục.
- GC metrics xác nhận1majorGC `MarkSweepCompact`, cause`Allocation Failure`, pause11,319s; youngGC `Copy`. Từ13:17:23→13:17:43, aggregateGCpause tăng5,452→16,911s; sampler metrics bị timeout trong hai mẫu giữa, RAM/Paging vẫn thu được. Cùng thời điểm, availableRAM133,57→57,75MiB rồi hồi phục, RSS453,27→560,50MiB. Đây là bằng chứng stall/áp lực bộ nhớ; chưa có heapdump/allocation profile để kết luận loại object hoặc memory leak.
- Sau dừng: publicHTTPShealthUP, cùngPID102403/NRestarts0, Hikari active0/pending0/max10; timeoutcounter8 là cumulative, không reset để che lỗi. Config MemoryMax/High/CPUQuota vẫninfinity. Sáuinvariants0, idleTx0, lockwaiters0;418syntheticpractice sessions,7.071sentence attempts. Logout100/100, cleanupHTTP/business0; không replay uncertain businesswrites.
- Evidence: `optimized-p10-soak2-100.incomplete-diagnostic.json`, raw/server/manifest cùngprefix; `target/neon-pool-20261012/post-soak-gc-pool.prom`, `soak-pool-timeout.log`, `invariants-after-soak2.json`, `soak2-cleanup.summary.json`. Privateauth/fixture không đưa vào report/Git.

### Bước xử lý còn lại để đạt100VU/30phút

1. Đo tổng memory budget (heap,~150MiBmetaspace,codecache,threads/native vàservices), allocation rate/live-set, paging và fullGC. Không chỉ tăngHikari vì pooltimeout ở soak là hệ quả khiJVMstall.
2. ThửGC/runtime cấu hình córollback, thay từng biến: đánh giáG1 thaySerialGC và heap phù hợp headroom1GB; giữ TLS/auth/SLO/rate-limit. Chưa áp dụng các thay đổi này trong đợt pool đã duyệt. G1 đổi CPU/throughput lấy pause ngắn hơn và không đảm bảo mọi pause đạt mục tiêu; phải benchmark chứ không hứa kết quả. Tham khảo [Java17G1GC](https://docs.oracle.com/en/java/javase/17/gctuning/garbage-first-g1-garbage-collector1.html).
3. Khi runtime ổn định, chạy lại100VU/30phút, bắt buộc DBtimeout0/OOM0/restart0, đủmetrics/cleanup/invariants. Nên bật riêng k6control loopback có kiểm traPID để dừng gracefully và giữsummary nếu gatefail; hiện tại stoppedraw được ghi rõpartial.
4. Chỉ sau khi100VU/30phút đạt mới đo125→150VU. Không coi tăngNeoncompute, thêmcache hoặc nângVPS là bước bắt buộc khi chưa kiểm chứng bottleneck tương ứng.

## Evidence và giới hạn

- Build/XML: `target/neon-pool-20261012/final`, `h2-final`, `baseline-read-path-tests.xml`.
- Neon screenshots: `target/neon-pool-20261012/neon-monitoring-before.png`.
- Raw auth/fixture chứa credentials synthetic và có ACL riêng; không đưa vào Git hoặc chia sẻ. Raw evidence giữ trong target, cần sao lưu trước khi dọn.
- Chưa chứng minh workload Listening/Room/AI/audio streaming, >100VU hoặc production RabbitMQ trong đợt này. Test local RabbitMQ không thay thế tải broker production.
