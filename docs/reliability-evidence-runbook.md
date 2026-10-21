# DictaLab: kế hoạch và cách tái hiện bằng chứng kỹ thuật

Đây là bộ kiểm thử backend, không phải chứng nhận 300 người dùng thật. Báo cáo kết quả nằm trong `reliability-evidence-2026-10-04.md`. Không dùng kết quả 300 VU/120 giây cũ để thay cho bài tải dài mới.

Cập nhật 08/10: [bước 1–3](reliability-evidence-2026-10-08.md), [lease fencing/S3 cleanup/shutdown/deadline](reliability-evidence-2026-10-08-next.md), [restore isolation và Redis memory-loss recovery](reliability-evidence-2026-10-08-restore.md), [late confirm và post-commit/pre-ACK broker crash](reliability-evidence-2026-10-08-rabbit-windows.md), [worker JVM death, broker network isolation và HTTP timeout/rollback](reliability-evidence-2026-10-08-final-local.md). Gate hiện có 17 suite/165 ca mandatory trên PG17/18, Redis và RabbitMQ, không skip. Hai gate cuối dùng broker fault riêng đã đạt 165/165; giữ nguyên các lượt lỗi trong raw evidence. Không đồng nhất broker stall/crash/network isolation với mọi network partition hoặc exactly-once. Restore yêu cầu endpoint Redis riêng/rỗng và key isolation; không dùng DB15 production làm fallback. Cleanup S3 mặc định tắt đến khi operator opt-in sau backup/retention gate. Drill PostgreSQL/Redis đã chạy local; full S3 recovery, VPS cutover và runtime production vẫn chưa nghiệm thu.

## Mục tiêu đề xuất, giữ nguyên khi thất bại

Cập nhật 09/10: [runtime Java 17 local](reliability-evidence-2026-10-09-java17.md) đã chạy hai gate PG17/18, mỗi lượt 165/165 mandatory, không lỗi/skip; default verify 250 ca thực chạy và JavaScript 116/116. Fixture network fault nay có network riêng và reconnect đúng IP ban đầu; giữ timeout/assertions, lưu nguyên lượt PG18 lỗi trước sửa. Đây là nghiệm thu JDK 17 local, chưa phải CI/VPS hoặc cập nhật bản vá runtime. Source production không thay trong batch này; S3 recovery và staging/cutover gates vẫn còn mở.

Cập nhật tiếp 09/10: [S3 release gate](reliability-evidence-2026-10-09-s3-gate.md) nâng gate hiện tại lên **17 suite/166 ca**, mỗi PG17/18 đều không lỗi/skip. Updater mới từ chối release trước khi dừng dịch vụ nếu còn tham chiếu S3 hoặc catalog chưa `DELETED`; kiểm tra lại trên snapshot phục hồi trước smoke. Không có cờ bỏ qua. Đây là chặn recovery chưa được hỗ trợ, **không phải chứng nhận phục hồi object S3**. DB-only drill vẫn được phép nhưng xuất `RESTORE_EXTERNAL_S3_VERIFIED=FALSE`; không dùng nó để mở release gate. Không xóa URL/catalog để làm gate xanh. Các số 165/116 phía trên là kết quả lịch sử của source trước batch này.

Cập nhật CI evidence 09/10: [CSV concurrency trong workflow](reliability-evidence-2026-10-09-ci-evidence.md) nay được ghi vào `target/reliability/concurrency-matrix.csv`, bắt buộc không rỗng trước khi job được chấp nhận và nằm trong artifact thu thập với `always()`. Contract red/green và lệnh shell của gate đã kiểm chứng local; JavaScript 121/121. Chưa push/dispatch hoặc nghiệm thu GitHub Actions; bộ Java mandatory gần nhất vẫn là 166/166 trên mỗi PG ở batch S3 gate, không coi lượt kiểm tra CI config này là một lần chạy lại backend.

Cập nhật production 09/10: người dùng chọn không dựng staging, cho phép thử production. [Inventory chỉ đọc](reliability-evidence-2026-10-09-production-observation.md) xác nhận service UP, artifact lịch sử S3/V25, V26/V27 chưa có và Listening RabbitMQ vẫn tắt. Headroom không đạt: disk 9,48 GiB <10, disk queue peak 6 >2; chưa chạy tải/crash/release hoặc gọi provider/S3. Chốt phạm vi cleanup, cửa sổ gián đoạn, resource synthetic và budget trước các bài có side effect; không tự bỏ guard/gate. Các yêu cầu staging trong báo cáo trước là quyết định lịch sử, không phải lựa chọn hiện tại của người dùng; giới hạn an toàn dữ liệu vẫn giữ nguyên.

Cập nhật disk inventory 09/10: người dùng phê duyệt kiểm kê rộng hơn, **chưa phê duyệt xóa**. [Báo cáo dung lượng](reliability-evidence-2026-10-09-disk-inventory.md) đề xuất sao lưu ra HP rồi xem xét xóa đúng hai ZIP installer Ubuntu trong `C:\DictaLab\turn-wsl`, tổng 1,575 GiB; không đụng DB/backup/media/rollback hoặc ứng dụng khác. Chưa copy/hash nội dung hoặc xóa source. Free disk 9,48 GiB và service UP khi kiểm tra candidate; 11,06 GiB sau dọn chỉ là ước tính. Paging/disk queue vẫn phải kiểm chứng lại trước thử production.

Cập nhật 15:14 UTC+7: hai ZIP Ubuntu không còn tại đường dẫn cũ, **không chạy phương án cleanup hai file của lượt trước**. Không có xóa từ lượt kiểm tra này. Kiểm tra bổ sung trong cùng [báo cáo dung lượng](reliability-evidence-2026-10-09-disk-inventory.md) ghi disk 11,62 GiB; WebLens incoming có 23 ZIP trực tiếp/7,564 GiB là ứng viên cần xét retention/current/rollback/off-host backup riêng. MoreLogin cache chứa Cookies/Login Data/Sessions, không xóa cả folder. Temp nhỏ; chưa có cleanup, restart hoặc full resource preflight mới.

Cập nhật 20:46 UTC+7: [rà ZIP WebLens](reliability-evidence-2026-10-09-weblens-archives.md) xác nhận current junction tới `6ade933…`; đề xuất giữ ba ZIP thuộc hai release đã cài, lưu trữ off-host rồi xem xét xóa **20 exact ZIP / 6,562 GiB** sau phê duyệt. Direct process/task references không chứng minh không có caller gián tiếp. Chưa có checksum/off-host copy, chưa xóa hoặc deploy. Disk 11,26 GiB, DictaLab UP, sáu WebLens services Running; vẫn chưa đo lại paging/disk queue hoặc nghiệm thu production.

| Bài | Cấu hình cần chạy | Trạng thái |
| --- | --- | --- |
| Load | 300 VU, 1.800–3.600 giây | Chờ tài nguyên tách biệt |
| Soak | 160 VU, 7.200 giây trở lên | Chờ tài nguyên tách biệt |
| Spike | 100 VU trong 60 giây, lên 300 trong 10 giây, giữ tải, về 100 trong 60 giây | Chưa đo |
| Stress | Các bước hữu hạn 100/200/300/400/500 VU, mỗi bước 300 giây; dừng tại thất bại đầu | Chưa đo |
| Recovery sau stress | Không tăng tiếp; profile, kiểm tra guard, rồi tối đa một bài 20 VU/120 giây | Chưa đo |

Mục tiêu Core API: p95 ≤500 ms, p99 ≤1.500 ms. Hành trình thành công ≥99,9%. Không đổi ngưỡng để làm xanh kết quả. Một lượt chỉ được chấp nhận khi cả threshold, cửa sổ tải đầy đủ, VU quan sát được, invariant DB và guard server đều qua.

## Thứ tự thực hiện

**Phạm vi hiện hành theo yêu cầu mới của người dùng:** tiếp tục source và kiểm chứng
local, **bỏ qua kiểm tra VPS**. Không SSH/preflight/cleanup/copy/deploy production;
không coi việc bỏ qua kiểm tra là đạt nghiệm thu production. Các mục VPS dưới đây
được giữ làm hướng dẫn tương lai, không phải các bước đang được thực thi.

[Batch local tiếp theo](reliability-evidence-2026-10-09-local-continuation.md) sửa retry
ẩn trong S3 SDK và startup pipeline runner. Gate mới **18 suite/170 ca mỗi PG17/18**,
không lỗi/skip; default 310 tổng/254 thực chạy/56 skip, JS 123/123. Smoke HP 20 VU/60s
đạt 176/176 hành trình và SQL restore 27 bảng, không suy diễn thành sức tải VPS.
Các counts cũ phía trên là lịch sử. [Portfolio](reliability-portfolio.md) có mapping
cơ chế/test/giới hạn và lệnh tái hiện; S3 recovery/CI remote/rollout vẫn mở.

[Bổ sung accounting gate](reliability-evidence-2026-10-09-accounting.md): tái hiện và
chặn sáu trường hợp PASS sai; đối soát completed với success samples và workload với
started, validate số liệu/target, xử lý file thiếu hoặc gzip lỗi theo fail-closed.
JavaScript mới 131/131; replay raw smoke cũ vẫn PASS. Không chạy lại backend hay tải
mới; các giá trị window/peak không chứng minh concurrency duy trì suốt cửa sổ.

1. Đọc luồng và kiểm thử sẵn có; giữ nguyên bảo vệ dữ liệu, CSRF/JWT, ownership và giới hạn tài nguyên.
2. Chạy race 2/5/10/20, mỗi cấu hình hai lần trên PostgreSQL thật. Barrier đăng ký đặt sau đọc email; các aggregate/claim DB dùng khóa hàng giữ bởi coordinator và chứng cứ `pg_stat_activity`. Room dùng barrier/khóa trong bộ nhớ đúng thiết kế.
3. Chạy provider HTTP lỗi có giới hạn, worker bị kết thúc/khởi động lại, migration và restore DB tách biệt.
4. Chạy smoke ở máy phát tải để xác minh script. Không diễn giải số liệu colocated thành sức chịu tải của VPS.
5. Chỉ chạy load/soak/spike/stress khi preflight đạt và app/DB/Redis thử tách biệt đã sẵn sàng. Generator ở HP, không đặt trên VPS.
6. Nếu thất bại: lưu nguyên raw/config, phân loại lỗi script/transport/app/provider, profile điểm tương ứng, sửa nhỏ nhất, chạy regression, rồi tối đa hai lượt đo lại sau sửa. Không tự tăng phần cứng, đóng ứng dụng khác hoặc tăng hạn mức AI.

## Workload và các giới hạn cần đọc đúng

`evidence.js` luân phiên 12 đọc, 6 Dictation, 1 Listening, 1 Room trong mỗi khối 20 vòng của từng VU. Hai account độc lập cho mỗi VU; guest chỉ dùng cho Room. Think time 2–5 giây. Báo cáo tỷ lệ **hành trình bắt đầu quan sát được** từ raw; tỷ lệ HTTP request không phải 60/30/10. Cửa sổ hữu hạn có thể cắt khối, đặc biệt ở smoke ngắn; không gọi tỷ lệ cấu hình là tỷ lệ đã quan sát.

- Đọc: catalog A1–B2, các trang lịch sử và dashboard.
- Dictation: start/resume, một câu gồm check/hint/replay/complete và đọc lịch sử. Đây không phải hoàn tất một lesson trong mỗi vòng. Các check sai riêng biệt là các lần thử hợp lệ, không phải API idempotent.
- Listening: luồng ONE_PASS, giữ cổng thời gian phát thực tế, draft/submit, chờ chấm nền, finish/report. Recall không rỗng để thực sự gọi fake provider.
- Room: hai account, approve, shared practice, WebSocket ping/draft/reconnect và cleanup. Không mô phỏng âm thanh WebRTC/TURN, trình duyệt hay tải file audio.
- Fixture: nhiều lesson và toàn bộ câu/từ, số từ lấy từ tokenizer của ứng dụng; dữ liệu lịch sử tổng hợp có nhãn riêng, không đại diện phân bố điểm/năng lực người học thực tế. Lưu cardinality sau seed và sau chạy.
- JWT: giữ cookie đã xoay theo từng account. Xóa cookie bằng expiry với **đúng Path**, không dùng `clear()` của k6 cho refresh cookie. Logout dùng proof refresh cũ được nhận diện, không replay refresh cũ tại endpoint refresh. Các gia đình token được dọn ở teardown; key rate-limit còn TTL được giữ nguyên.
- SSH làm mọi client xuất hiện từ một địa chỉ nguồn. Rate-limit auth theo IP vẫn bật. Login/cleanup được pace; hạn mức này có thể là bottleneck ở bài dài/refresh. Không đổi hạn mức hay giả IP để ép đạt; nếu cần mô hình proxy/nhiều nguồn, chạy thành profile riêng và ghi rõ topology.

## Tái hiện race/recovery

Yêu cầu Java 17+, Maven và PostgreSQL binary thật. Script dưới đây tạo cluster riêng, chỉ nghe loopback và giữ lại log/data dưới `target/`. Không nhận database production tùy ý cho race matrix.

```powershell
./scripts/test-postgres.ps1 -PostgresBin 'C:/Users/HP/Tools/postgresql-18.4/pgsql/bin' -Port 15435 -MavenArgs @(
  '-Ddictalab.build.directory=target/evidence-replay',
  '-Ddictalab.race-tests=true', '-Ddictalab.race-repeats=2',
  '-Ddictalab.race-evidence=target/evidence-replay/concurrency-matrix.csv',
  '-Dtest=CapacitySeedTest,ConcurrencyMatrixTest,ProductionReliabilityTest,EmailOutboxRecoveryTest,MigrationUpgradeTest,ConversationIntegrationTest,GeminiClientTest,ProviderGuardTest', 'test')
node --test src/test/js/load-contracts.test.cjs
python scripts/load/fake-provider.py --self-test
```

`EmailOutboxRecoveryTest` có tiến trình Java con bị kết thúc thật sau điểm SMTP acceptance giả lập. Khởi động lại trước hết hạn không gửi tiếp; sau khi làm lease hết hạn có kiểm soát thì reclaim. SMTP là at-least-once: acceptance trước crash có thể dẫn tới email trùng theo nghiệp vụ. Thử nghiệm này không chứng nhận SMTP thật hoặc Listening/RabbitMQ process crash.

`GeminiClientTest` thực hiện HTTP timeout 30 giây thật, 429/Retry-After, 500, JSON sai và body quá lớn; xác minh trả permit và gọi lại được. Không sửa timeout production để rút ngắn kiểm thử.

## Smoke và restore ở HP

Chuẩn bị **Redis mới, rỗng, riêng cho test**, loopback port 16480. Có thể dùng runtime thử sẵn có; không cài WSL hoặc Redis lên production cho bài này. Không chia sẻ cloud Redis production, kể cả logical DB khác.

```powershell
./scripts/run-local-evidence-smoke.ps1 -PostgresBin 'C:/Users/HP/Tools/postgresql-18.4/pgsql/bin' -Seconds 60
```

Script build JAR riêng, tạo PostgreSQL riêng, 40 account/6 lịch sử mỗi account, provider giả với ngân sách 200 call và runtime hữu hạn; chạy 20 VU. Chỉ dừng JVM/provider/cluster nó tạo. Kết quả raw, SHA, manifest, metric series và restore giữ trong `target/`.

Restore chỉ thực hiện trên database `dictalab_capacity_<suffix>` đã dừng app. Nó tạo DB đích mới, không ghi đè DB nguồn. So sánh count/hash mọi public table và chạy invariant hai bên. Không chứng minh phục hồi media, Redis hay smoke JAR tương thích trên DB phục hồi.

## Điều kiện chạy ở VPS

Preflight quan sát ít nhất 60 giây: ≥10 mẫu, CPU trung bình ≤35%, peak ≤60%, RAM trống ≥1.800 MiB, disk queue ≤2, paging ≤100/s, disk trống ≥10 GiB. Khi chạy: CPU ≥85% trong sáu mẫu, RAM <500 MiB hoặc disk <10 GiB sẽ dừng đúng JVM capacity. Không tắt MoreLogin/WebLens/Bilingual/DictaLab để làm đạt guard.

Hiện VPS bị guard từ chối, nên các lệnh bên dưới là hướng dẫn cho khi điều kiện thay đổi, **không phải lệnh đã chạy**.

1. Build một JAR mới; copy vào đúng `C:\DictaLab\staging\dictalab-capacity.jar`. Không thay JAR/service production. Copy script capacity và `RedisIsolationCheck.class` đã biên dịch `javac --release 17` vào thư mục capacity; ghi SHA.
2. Chạy fake-provider loopback với call/runtime budget. Có thể đặt ở HP và reverse SSH tunnel loopback; phải ghi vị trí provider trong manifest/topology. Provider chỉ chấm recall synthetic, không gọi AI bên ngoài.
3. Cung cấp endpoint Redis test riêng qua biến `CAPACITY_REDIS_URL` trong phiên riêng, không qua tham số dòng lệnh/log/Git. Startup kiểm tra rỗng và từ chối host Redis production.
4. Dùng Windows one-shot interactive task như lifecycle cũ để app không bị OpenSSH giết khi disconnect. Ví dụ cho load 30 phút: `run-capacity-vps.ps1 -EvidenceSuite -DedicatedRedis -DatabaseName dictalab_capacity_load20261004 -Seconds 7200`. Soak hai giờ cần watchdog 14.400 giây. Giữ app/database mới theo từng phase; không tái dùng fixture lịch sử cũ.
5. Copy runtime manifest về HP. Generator kiểm tra budget còn lại đủ cho setup 30 phút + workload + cleanup 30 phút + grace. Copy cả server resources/metric series và log sau chạy; generator PASS một mình không phải campaign PASS.

```powershell
# Trên HP, sau khi runtime đã qua preflight; minh họa, chưa chạy.
./scripts/run-capacity-check.ps1 -Module evidence -Users 300 -Seconds 1800 -Label load300 -RuntimeManifest target/capacity/runtime-load.json
./scripts/run-capacity-check.ps1 -Module evidence -Users 160 -Seconds 7200 -Label soak160 -RuntimeManifest target/capacity/runtime-soak.json
./scripts/run-capacity-check.ps1 -Module evidence -Profile spike -Users 300 -Seconds 360 -Label spike -RuntimeManifest target/capacity/runtime-spike.json
# Stress: từng bước riêng 100/200/300/400/500, mỗi bước 300 giây.
# Dừng ở lỗi đầu, không chạy vòng lặp tự tăng tiếp. Lưu DB invariant và profile trước recovery.
```

## Artifact và quy tắc công bố

Giữ raw `.raw.json.gz`, summary, accounting, result, manifest (Git dirty/commit, JAR/k6/script SHA), generator/server CSV, Prometheus series, JUnit XML, race CSV, provider count, dump SHA và fingerprint trước/sau. Raw nằm trong `target/` đang bị Git ignore: cần sao lưu trước khi dọn workspace; không đăng token/password, answer hay URL chứa credential.

Summary export của k6 có thể chứa `setup_data` gồm cookie/CSRF account test. Giữ bản gốc nội bộ để truy vết; loại bỏ `setup_data` và rà soát dữ liệu nhạy cảm trên bản sao trước khi công bố. Không sửa bản raw gốc để che lượt thất bại.

Chưa có budget AI thật nên mức chi hiện tại là 0, real-provider mode bị chặn. Muốn chạy AI thật sau này cần proxy/bộ đếm giới hạn số call và trần tiền bên provider; lời xác nhận `AckProviderCalls` không thay cho budget. Không có bằng chứng độ chính xác hay latency AI thật từ fake provider.

Khi chưa có tải dài, câu CV phù hợp là xây dựng bộ kiểm thử concurrency/recovery trên PostgreSQL thật và lưu bằng chứng tái hiện. Không viết “chịu tải 300 người trong một giờ”, “soak hai giờ đạt SLA” hoặc “không mất dữ liệu khi mọi loại crash”.

Tham chiếu: [k6 thresholds](https://grafana.com/docs/k6/latest/using-k6/thresholds/), [k6 cookie API](https://grafana.com/docs/k6/latest/javascript-api/k6-http/cookiejar/cookiejar-set/), [mã cookie jar k6 2.2](https://github.com/grafana/k6/blob/v2.2.0/js/modules/k6/http/cookiejar.go). Checks không thay cho thresholds; raw accounting bổ sung kiểm tra việc kết thúc sớm.
