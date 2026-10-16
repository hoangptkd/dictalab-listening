# DictaLab — RabbitMQ uncertain publication và post-commit redelivery, 08/10/2026

Tiếp nối [restore isolation/recovery](reliability-evidence-2026-10-08-restore.md). Batch này đóng hai khoảng trống kiểm thử P1; không đổi code production, API, migrations, dependency hoặc kiến trúc modular monolith. Không deploy và không gọi provider trả phí.

## 1. Bằng chứng source và quyết định

- `AiBackgroundJobs.dispatch()` ghi publication deadline vào PostgreSQL trước gửi persistent message chỉ chứa job ID. Nó chờ correlated confirm 5 giây; timeout/returned message giữ job PENDING, ghi metric lỗi và không fallback sang gọi AI trực tiếp. Không biết broker đã nhận hay chưa là kết quả hợp lệ của timeout.
- `AiBackgroundJobs.consume()` claim PENDING dưới khóa DB, tăng attempts và lấy lease. `withLease()` ghi kết quả cùng DONE trong một transaction, kiểm tra lease bằng thời gian DB. Consumer AUTO ACK sau khi method trả về. Delivery lặp của job DONE không claim được và không gọi provider.
- `AiRabbitConfig` đã có durable queues, persistent publication, giới hạn queue/reject-publish và DLQ. Test cũ đã kiểm tra duplicate delivery, poison message, unroutable publication, confirmed publication qua SIGKILL và graceful drain.
- Khoảng trống trước batch: chưa chứng minh **timeout nhưng publication đến muộn**, hoặc **commit thành công rồi mất ACK**. Hai test đầu tiên đều xanh với implementation hiện hữu. Không có production bug được tái hiện trong batch này; không dựng red giả hoặc viết lại luồng đã đúng.

Thay đổi tối thiểu: thêm hai case vào `AiRabbitQueueIntegrationTest`, đổi jobs injection thành `@MockitoSpyBean` để giữ nguyên xử lý thật nhưng đặt checkpoint trước ACK; thêm pause/unpause vào launcher Testcontainers hiện hữu. Helper chỉ chấp nhận Rabbit container do launcher giữ, không nhận container ID tùy ý. Verifier nâng minimum của suite từ 5 lên 7, vẫn từ chối mọi skip/failure/error trong mandatory gate.

**Có sẵn / đang tắt / đã triển khai:** AI queue và Listening queue có source đầy đủ nhưng mặc định `enabled=false` trong `application.yml`. Test bật AI queue riêng và tắt auto-start listener cho đến checkpoint. Batch không đọc hoặc thay config VPS, nên không xác nhận flag production hiện tại hoặc rollout đã hoàn tất. Không đồng nhất “có source + test xanh” với “đã chạy production”.

## 2. Tiêu chí nghiệm thu và fault injection

### A. Confirm hết hạn nhưng message vẫn tới broker

1. Tạo job riêng và warm publisher channel tới Rabbit thật. Dừng tiến trình broker bằng Docker pause, không cắt socket của publisher.
2. Chạy dispatcher thật, giữ deadline confirm production 5 giây. Dispatcher phải kết thúc trong budget test 15 giây; thời gian chờ không được nhỏ hơn 5 giây. Job vẫn PENDING, attempts=0, metric publish failure tăng 1, published không tăng và chưa gọi TTS.
3. Luôn unpause trong finally. Sau resume phải thấy đúng một message trong queue: publication chưa confirm đã tới broker.
4. Làm publication deadline của **job fixture duy nhất** hết hạn, dispatch lại và thấy đúng hai message trước khi start consumer. Đây là retry publication, không phải retry tác vụ trả phí.
5. Consumer thật phải xử lý cả hai delivery. Đợi hai invocation trước shutdown để không dừng quá sớm khi message thứ hai còn prefetched. Sau drain: queue rỗng, DONE, attempts=1, một hàng audio và đúng một TTS call giả lập.

### B. PostgreSQL commit trước ACK, broker crash rồi giao lại

1. Publish đúng một message. Spy gọi `consumeAudio` thật cho đến khi transaction commit xong, rồi giữ callback bằng latch trước khi trả về cho listener AUTO ACK.
2. Đọc qua SQL từ test thread: DONE và một hàng audio đã nhìn thấy ngoài transaction worker; TTS đã gọi đúng một lần.
3. SIGKILL/start cùng Rabbit container và data. Cập nhật connection factory theo mapped port mới của Docker; không tạo job hoặc publish thêm message. Thả callback cũ trong finally, kể cả khi assertion thất bại.
4. Listener phải tự reconnect và thực sự gọi callback lần hai. Kết thúc/drain listener, rồi kiểm tra DONE không đổi, attempts=1, đúng một hàng audio, bytes vẫn `{1,2,3}`, đúng hai consumer invocation và một TTS call.

Các test không có transaction bao ngoài. DB assertions dùng `JdbcTemplate` tới PostgreSQL thật, không đọc JPA L1 cache. Provider duy nhất là mock TTS; claim, lease, commit, Rabbit transport và listener ACK đều là code thật. JUnit budget hữu hạn; latch/executor được giải phóng khi lỗi. Fault tests chỉ bật khi `dictalab.testcontainers=true`, không pause/kill broker tùy ý từ messaging environment.

**Trade-off:** spy đặt checkpoint chính xác vào cửa sổ rất ngắn nhưng không mô phỏng chết JVM. Docker pause chứng minh broker stall/confirm delay, không phải network partition. SIGKILL kiểm tra unacknowledged delivery recovery trên cùng storage, không chứng nhận quorum failover hay disk loss. Không giả định exactly-once; chứng minh idempotent local effects cho job đã commit trong hai kịch bản này.

## 3. Kết quả và raw evidence

Root: `target/reliability-rabbit-windows-20261008/`. Output mỗi lượt tách riêng, không ghi đè bằng chứng batch restore trước.

| Lượt | Tests | Failures | Errors | Skipped | Ý nghĩa |
|---|---:|---:|---:|---:|---|
| `focused` | 2 | 0 | 0 | 0 | Hai cửa sổ lỗi mới, lần đầu, code production không đổi |
| `tc17` | 152 | 0 | 0 | 0 | PG17.11/Redis/Rabbit, source cuối, verifier 16 suite đạt |
| `tc18` | 152 | 0 | 0 | 0 | PG18.4/Redis/Rabbit, source cuối, verifier 16 suite đạt |
| `default` | 298 | 0 | 0 | 52 | H2 toàn bộ và packaging, trước bước chờ đủ hai invocation |
| `default-final` | 298 | 0 | 0 | 52 | Source cuối, H2 toàn bộ và executable JAR đạt |
| `js.xml` | 116 | 0 | 0 | 0 | JavaScript toàn bộ, gồm verifier tests |

`focused` chạy trước khi thêm bước chờ hai invocation ở case A. Bước chờ này giữ tiêu chí đủ delivery trước shutdown, không thay deadline hoặc logic production. Hai mandatory gates nghiệm thu source cuối cùng; suite Rabbit đạt 7/7 ở mỗi gate. Không có lượt failure trong batch này.

Default counts bao gồm skipped: thực chạy 246 ca; 52 ca bị profile/env gate tắt có chủ đích. H2 không chứng minh hai lỗi Rabbit mới; mandatory PostgreSQL gates chạy đủ 152 ca với 0 skip. Không dùng H2 hoặc focused subset thay gate thật. Verifier kiểm tra XML đầy đủ, 16 suite và minimum từng suite, không chỉ dựa vào Maven BUILD SUCCESS. Không chạy lại native PostgreSQL ngoài Docker trong batch test-only này.

Negative check của verifier chạy trên reports batch restore trước: suite Rabbit chỉ có 5 case bị từ chối với `Missing cases in AiRabbitQueueIntegrationTest: 5 < 7` (`gate-negative.log`). Các reports 150 ca trước vẫn có giá trị cho batch cũ nhưng không đủ nghiệm thu batch mới.

Local dùng JDK21.0.12.1 compile release17, Spring Boot4.1.1/Testcontainers2.0.5, PostgreSQL17.11/18.4, Redis8.10.2, Rabbit4.3.6. Các containers test được resource reaper dọn sau khi fork kết thúc; không dừng container `codex-lb` có trước. Java17 runtime CI/VPS và paid-provider execution chưa được chạy từ workspace này.

## 4. Tái hiện

Từ repository root, Java17+, Maven, Docker và PowerShell. Chọn thư mục output mới để giữ nguyên raw. Profile reliability tự tạo PostgreSQL/Redis/RabbitMQ riêng; không có Docker thì phải fail, không skip.

```powershell
mvn --batch-mode --no-transfer-progress -Preliability '-Ddictalab.build.directory=target/rabbit-windows-replay17' test
if ($LASTEXITCODE -ne 0) { throw 'Reliability tests failed' }
node scripts/verify-reliability-tests.cjs target/rabbit-windows-replay17/surefire-reports
if ($LASTEXITCODE -ne 0) { throw 'Reliability evidence rejected' }

mvn --batch-mode --no-transfer-progress -Preliability '-Ddictalab.testcontainers.postgres-image=postgres:18.4-alpine' '-Ddictalab.build.directory=target/rabbit-windows-replay18' test
if ($LASTEXITCODE -ne 0) { throw 'Reliability tests failed' }
node scripts/verify-reliability-tests.cjs target/rabbit-windows-replay18/surefire-reports
if ($LASTEXITCODE -ne 0) { throw 'Reliability evidence rejected' }
```

Suite Rabbit đã nằm trong POM/profile và workflow hiện hữu. Surefire XML cùng logs có tên case và lỗi connection/ACK dự kiến khi fault injection; không cần framework hoặc artifact uploader mới. Không nhận error log có chủ đích là failure nếu assertions recovery và mandatory verifier đều đạt.

## 5. Giới hạn và bước tiếp theo

- **P0:** full S3 recovery còn thiếu bytes/version/IAM/backup proof; giữ cleanup mặc định false. Không tự bật hoặc chạy thử trên bucket production.
- **P1:** actual worker JVM crash, network partition, quorum/disk-loss và provider uncertain outcomes chưa được batch này chứng nhận. Các paid attempts chưa biết kết quả vẫn cần reconciliation/explicit retry; không bulk reset UNKNOWN/RUNNING sang PENDING.
- **P1:** staging/VPS full restore, Java17 runtime, DPAPI, compatibility JAR, service-stop budgets và cutover vẫn chưa nghiệm thu. Không dùng local tests để tuyên bố đã deploy.
- **P2:** load/soak và overload HTTP mapping giữ thứ tự đã chốt, cần tài nguyên và workload riêng.

Ponytail giữ scope test-only khi code đã đúng. Spring testing skills giữ transaction boundaries thật và assertions trên committed DB; encoding guard bảo toàn UTF-8. Câu portfolio phù hợp: “Dùng PostgreSQL và RabbitMQ thật để chứng minh timeout publisher có thể tạo delivery trùng, và broker crash sau commit nhưng trước ACK không tạo thêm kết quả hay gọi lại TTS trong các kịch bản đã kiểm thử.” Không tuyên bố exactly-once hoặc production-ready toàn hệ thống.

## 6. Snapshot và artifact

```text
baseline-tests.zip  B900F9599FD290626F21D68093DDC4A467AB40A7B84D4A1C8FE2166A4A10D5A4
verified-source.zip C78ECC0808EB6DC1F46AA2A391B0F66D16B32CBF3B74765DD0F92D5ED369EF17
executable JAR      4ECFEC662580AE4C1F4645F3BE2A8A86239E800D9F33C9F5BE0C5B339220D74C
```

Root như phần raw evidence. Baseline chụp ba file test/helper/verifier trước sửa (flat ZIP); snapshot verified giữ 350 đường dẫn source/config/scripts đã chọn như batch restore, không chứa `.env`, docs hoặc secret production. Đã đối chiếu byte hash của cả 350 file với workspace. So với snapshot batch trước chỉ ba file nêu trên thay đổi; toàn bộ `src/main`, migrations, POM và deployment scripts giữ nguyên. Không lấy HEAD làm source đã test vì workspace có nhiều thay đổi có trước.

JAR ở `default-final/` (bản `default/` có cùng hash), có SHA-256 bằng đúng JAR batch restore trước. Đây là kiểm chứng production artifact không đổi, không phải bằng chứng deploy. UTF-8 của ba file code/verifier và hai tài liệu đạt, không cần repair; whitespace checks đạt. `target` bị Git ignore; sao lưu source/raw/JAR trước khi dọn. Không commit, push hoặc deploy.
