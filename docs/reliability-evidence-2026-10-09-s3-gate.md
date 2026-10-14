# DictaLab — chặn release khi S3 recovery chưa được chứng minh, 09/10/2026

Kết quả local: **PG17 và PG18 đều đạt 166/166 mandatory**, 17 suite, không failure/error/skip; JavaScript 120/120. Default verify có 306 ca, 250 thực chạy, 56 skip theo profile. Chưa deploy, chưa truy cập S3 thật, chưa nghiệm thu production và chưa tắt HP.

Tiếp nối [runtime Java 17](reliability-evidence-2026-10-09-java17.md). Batch này chỉ sửa hai script release/restore, hai file test và verifier mandatory; cập nhật tài liệu. Không sửa production Java, API, migrations, dependency hoặc kiến trúc modular monolith.

## P0: khoảng trống và biện pháp

Trước sửa, restore drill bỏ qua URL S3 như dependency bên ngoài nhưng updater vẫn chấp nhận restore DB/local-media/JAR để release. Bằng chứng trong snapshot trước batch: `scripts/restore-drill-windows-vps.ps1` và `scripts/update-windows-vps.ps1` thuộc `target/reliability-java17-20261009/ship-source/`. Điều này không chứng minh object/credential S3 có thể phục hồi. Các kiểm tra S3 production ngày 04/10 trong [s3-audio.md](s3-audio.md) là lịch sử, không đóng khoảng trống recovery này.

Giải pháp nhỏ nhất là **fail closed**, chưa xây hệ thống backup cloud mới:

- [Updater dòng 86](../scripts/update-windows-vps.ps1#L86) gọi `-CheckS3Only` trước Redis preflight và lần dừng dịch vụ đầu tiên.
- [SQL dòng 50](../scripts/restore-drill-windows-vps.ps1#L50) kiểm tra hai bảng audio và catalog V27 trong một transaction `REPEATABLE READ READ ONLY`. Statement deadline 15 giây, lock deadline 3 giây; không retry hoặc repair. DB chưa V27 vẫn kiểm tra URL cũ.
- URL `/media/s3/...` hợp lệ hoặc sai định dạng đều chặn. Catalog khác `DELETED` đều chặn, kể cả `STAGED`/`DELETING` chưa có tham chiếu bài học. Tombstone-only được phép qua nhưng tombstone có tham chiếu hiện tại vẫn chặn.
- [Wrapper dòng 78](../scripts/restore-drill-windows-vps.ps1#L78) yêu cầu exit 0 và đúng một success marker. Lỗi quyền/kết nối/schema, thiếu hoặc sai marker đều từ chối; không đưa captured output vào thông báo lỗi.
- [Snapshot check dòng 182](../scripts/restore-drill-windows-vps.ps1#L182) kiểm tra lại DB phục hồi trước smoke JAR mới/cũ, bắt dependency phát sinh sau live preflight. Giữ nguyên quy trình backup/rollback hiện có.
- DB-only drill vẫn được phép; [dòng 281](../scripts/restore-drill-windows-vps.ps1#L281) xuất `RESTORE_EXTERNAL_S3_VERIFIED=FALSE`. Không diễn giải `RESTORE_OK` thành full S3 recovery.

**Trade-off:** updater mới chặn cả release hợp lệ đang dùng S3 cho đến khi có object recovery gate riêng được triển khai và kiểm chứng. Không có bypass flag. Không xóa URL/catalog hoặc tắt S3 để làm gate xanh. Gate không kiểm kê object mồ côi ngoài catalog, không xác minh bucket/IAM/versioning/retention, và không bảo vệ toàn bộ đường triển khai thủ công ngoài updater này. Đây là mitigation P0 ở release tooling local, **không phải đóng P0 S3 recovery production**.

## Test và tiêu chí nghiệm thu

Hai ca JavaScript mới kiểm tra thứ tự trước stop/smoke, parse và thực thi function PowerShell thực với fake psql: success, nonzero exit, output rỗng và marker sai; kiểm tra lỗi không lộ output.

[Integration test dòng 221](../src/test/java/com/dictalab/support/RestoreIsolationIntegrationTest.java#L221) tạo DB riêng có tên được kiểm tra, chạy SQL lấy trực tiếp từ script bằng psql thật trên disposable PostgreSQL. Nó kiểm tra legacy/malformed URL trong cả hai bảng, các trạng thái catalog với DDL V27 hiện có, tombstone, thiếu bảng và dữ liệu không bị sửa sau rejection. Không dùng transaction rollback của test hoặc Hibernate L1 cache để suy ra dữ liệu đã commit.

Verifier bắt buộc `RestoreIsolationIntegrationTest` có ít nhất 7 ca thay vì 6. Gate phải đủ 17 suite/166 ca và không skip/failure/error; BUILD SUCCESS một mình không đủ. Không thay timeout/assertion để làm xanh kết quả.

Raw root: `target/reliability-s3-gate-20261009/`. Giữ nguyên lượt lỗi.

| Bằng chứng | Kết quả | Diễn giải |
|---|---|---|
| `red-js.xml` | 5 ca, 2 fail | Hai contract mới tái hiện thiếu gate trước sửa |
| `green-js.xml` | 5/5 | Contract script sau sửa |
| `sql-first/` | 1 ca, 1 fail | SQL lần đầu chưa qua local-only; không dùng nghiệm thu |
| `sql-green/` | 1/1 | SQL thật sau sửa, đủ các tình huống trong ca mới |
| `tc17/`, `tc18/` | Mỗi gate 166/166 | Verify/JAR và mandatory verifier PASS |
| `default/` | 306 tổng, 56 skip, 250 thực chạy | Verify/JAR PASS; không thay gate mandatory |
| `js-ship.xml` | 99 pass, 2 lỗi nạp file | Snapshot thiếu phụ trợ, không phải 101 ca nghiệp vụ hoàn chỉnh |
| `js-replay.xml` | 120/120 | Gói source tái hiện đầy đủ, không skip/cancel |
| `race17/`, `race18/` | Mỗi lượt 28/28 | Ma trận riêng: 7 nghiệp vụ, 2/5/10/20 contenders, mỗi cấu hình lặp 2 lần |

Lượt SQL đầu thất bại tại kỳ vọng local-only được phép qua; sửa tên biến `references` và dấu kết thúc block SQL. Không có stderr chi tiết được lưu trong lượt đầu nên không gán thông báo DB cụ thể cho lượt đó. Các lượt sau lưu stdout/stderr từng lần psql trong `restore-isolation/s3-gate-*.log`.

Hai full gate dùng chung working directory nên fixture concurrency cũ ghi chung `ship-source/target/concurrency-matrix.csv`. Không dùng CSV chung để chứng minh riêng từng PG. Đã chạy lại `ConcurrencyMatrixTest` với đường dẫn evidence riêng: mỗi PG có 88 dòng, 28 tổ hợp nghiệp vụ/contender; hai ca có ba phase race mỗi vòng, các ca còn lại có một phase. `race-gates-final.log` kiểm tra số phase và hai lần lặp. JUnit của full gate vẫn độc lập theo build directory.

## Runtime, source và artifact

- Oracle JDK **17.0.12** thật, Maven 3.9.12, Windows HP; PostgreSQL 17.11/18.4, Redis 8.10.2 và RabbitMQ 4.3.6 trong Testcontainers. Không thay JAVA_HOME/PATH cấp máy/service. JDK này chỉ chứng minh compatibility, không chứng nhận bản vá bảo mật/runtime production.
- `runtime-gates-final.log` xác nhận 17 XML mỗi mandatory gate dùng Java 17; default có 46 XML chứa runtime properties, một suite hoàn toàn skip không xuất properties. Checker đầu quá chặt với suite skip nên log đầu không phải nghiệm thu; không có test chạy sai runtime.
- PG17 kết thúc 12:11:40, PG18 12:11:43; default 12:18:37; race riêng 12:21:10/12:21:17 (UTC+7, 09/10/2026).
- `ship-source.zip`, SHA256 `E819A65D30C8F88D64CD6DDAB7D8C8BEA63B45F4A013328BD2705D53BBA0451E`: 350 file input của hai mandatory gate. So với snapshot trước, 5 file batch này và 4 file UI từ chỉnh sửa khác thay đổi. Không ghi đè hoặc nhận các chỉnh sửa UI là phần reliability này.
- Snapshot 350 thiếu chín phụ trợ cần cho toàn bộ JS suite: launcher local, bốn file legacy bridge và bốn file load-test. Gói **`ship-source-replay.zip`**, SHA256 `842E5D96F7371E41E1884C0FB6769F776BB7B3B99D68C44F15C5D18D443EA016`, có 359 file: toàn bộ 350 input khớp byte-for-byte, chỉ thêm phụ trợ; không đổi Java/resources/POM. JS cuối và hai race riêng chạy từ gói này. Default chạy trên bản mở rộng có cùng input; gói trung gian có CSV phát sinh được giữ riêng, không dùng làm source chuẩn bàn giao.
- JAR của `tc17`, `tc18` và `default` cùng SHA256 **`A728E43EA8B9ED56AB83118F1718992E1DAE114829208067E747A792A8DF79C4`**. Hash khác batch trước do có UI trong input; không gán artifact này cho bản production đang chạy.
- `workspace-after.log` xác nhận toàn bộ 359 input vẫn khớp workspace sau test, không tính output phát sinh trong `target`. Docs mới nằm ngoài snapshot build, được bàn giao riêng.

Không còn Java 17 test process hoặc container/network thuộc các lượt mới sau kết thúc. Giữ nguyên các JVM khác, container `codex-lb`, `dictalab-redis`, PostgreSQL và network có trước/thuộc công việc khác. Không dừng dịch vụ hoặc dọn tài nguyên không thuộc test này.

## Tái hiện và bàn giao

Giải nén `ship-source-replay.zip` vào thư mục mới; Windows có Docker, Maven, JDK 17 và PowerShell/pwsh cần cho script tests. Chạy từ root source đã giải nén, mỗi build/evidence directory phải mới:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
mvn --batch-mode --no-transfer-progress -Preliability '-Ddictalab.build.directory=target/replay-pg17' '-Ddictalab.race-evidence=target/replay-pg17/concurrency-matrix.csv' verify
if ($LASTEXITCODE) { throw 'PG17 failed' }
node scripts/verify-reliability-tests.cjs target/replay-pg17/surefire-reports
if ($LASTEXITCODE) { throw 'PG17 mandatory gate failed' }
mvn --batch-mode --no-transfer-progress -Preliability '-Ddictalab.testcontainers.postgres-image=postgres:18.4-alpine' '-Ddictalab.build.directory=target/replay-pg18' '-Ddictalab.race-evidence=target/replay-pg18/concurrency-matrix.csv' verify
if ($LASTEXITCODE) { throw 'PG18 failed' }
node scripts/verify-reliability-tests.cjs target/replay-pg18/surefire-reports
if ($LASTEXITCODE) { throw 'PG18 mandatory gate failed' }
node --test src/test/js/*.test.cjs
if ($LASTEXITCODE) { throw 'JavaScript failed' }
```

`handoff-bundle.zip` trong raw root giữ hai source ZIP, JAR, báo cáo/runbook/S3 notes, logs/XML và child/S3/race evidence, gồm các lượt lỗi. Manifest hash đi kèm. Raw chứa test credentials và dữ liệu synthetic: chỉ dùng nội bộ, rà soát bản sao trước khi công bố; không sửa raw gốc. Không có `.env` thật, production dump hoặc cloud credential trong gói. `target` bị Git ignore, cần sao lưu trước khi dọn.

Ponytail giữ giải pháp ở release gate hiện có; không thêm dependency hoặc hệ thống backup mới. Encoding guard kiểm tra UTF-8 cho toàn bộ file batch này. JPA testing skill yêu cầu bằng chứng SQL đã commit thay vì cache/test rollback.

## Còn mở và thứ tự tiếp theo

1. **P0:** xác định staging riêng và phạm vi/budget S3 được phép; triển khai và kiểm chứng recovery object/credential/retention thật, gồm partial failure và uncertain outcome. Chưa được cấp target/phạm vi ghi-xóa cloud nên chưa chạy; không dùng một cờ xác nhận để bỏ gate.
2. **P1:** kiểm chứng CI/runtime production và diễn tập release/rollback trên staging, với DB/Redis/RabbitMQ riêng, rồi mới phê duyệt cutover. Các thay đổi reliability local chưa được coi là deployed; cleanup S3 vẫn mặc định tắt.
3. **P2:** load/soak/spike/stress theo guard và ngân sách trong [runbook](reliability-evidence-runbook.md). Chưa có bằng chứng tải dài hoặc SLA production từ các test local này.

HP chưa tắt vì yêu cầu “đến cuối” chưa được nghiệm thu production. Cần chốt môi trường và quyền thử còn thiếu, không tự triển khai vào hệ thống thật.
