# P2: chấm từ và phản hồi gọn

## Quyết định cache

- Giữ trạng thái câu và metadata đang dùng trong RAM của tab, ghép kết quả từng từ vào snapshot đó. Bản nháp vẫn độc lập với kết quả server, không mất nội dung đang gõ.
- Không lưu kết quả chấm/đáp án vào HTTP cache, Cache API, localStorage hoặc IndexedDB. API vẫn `no-store`; journal trong sessionStorage chỉ giữ các thao tác chưa xác nhận để chống gửi trùng như trước.
- Không thêm Redis vào luồng chấm từ. Đây là luồng ghi: quyền sở hữu, khóa phiên, lịch sử lần thử, tiến độ và receipt phải nhất quán trong một transaction DB. Cache không thay thế các bước này; cache thêm trạng thái thay đổi sau từng từ sẽ cần xử lý invalidation và lỗi ghi DB/cache lệch nhau.
- Chỉ cân nhắc cache Redis cho dữ liệu bài học ít đổi nếu đo tiếp cho thấy việc đọc metadata là nút thắt đáng kể. Khi đó phải có key theo phiên bản nội dung và invalidation khi sửa/xuất bản; không cache quyền truy cập hay dùng cache làm nguồn quyết định điểm.

Tham khảo: [MDN HTTP caching](https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/Caching), [Redis cache-aside và invalidation](https://redis.io/docs/latest/develop/use-cases/cache-aside/rust/).

## Thay đổi

- Client mới dùng `POST .../words/{index}/checks?compact=true`. Response gồm `sentenceId`, một `word`, số từ đã giải quyết và các cờ/counter tiến độ; không trả lại toàn bộ `words`, transcript, audio hay số câu trong bài.
- `canComplete` là đủ điều kiện hoàn thành, không phải tự động hoàn thành câu. Thao tác hoàn thành vẫn dùng endpoint/transaction riêng.
- Tab cũ không có tham số `compact` vẫn nhận `SentenceState`. Hai hợp đồng response có action receipt riêng, không đọc nhầm JSON của nhau.
- Frontend chỉ dựng markup và cập nhật DOM của từ vừa chấm; giữ nguyên các input khác, focus, selection và bản nháp. Vẫn nhận được response đầy đủ nếu backend cũ chưa hỗ trợ compact.
- Nếu số từ đã giải quyết hoặc counter gợi ý không khớp snapshot, client GET lại trạng thái từ server, không POST lại đáp án. Context guard vẫn chặn response sau chuyển câu, chuyển phiên hoặc đăng xuất.
- Backend gộp truy vấn xác định câu hiện tại và lượt làm thành một JOIN, dùng danh sách lượt làm từ đã tải để chấm và tính tiến độ, tokenize một lần và bỏ truy vấn đếm số câu trên đường compact.
- Giữ khóa phiên, xác thực, CSRF, kiểm tra đã nghe, giới hạn thử, phân loại lỗi, lịch sử, penalty gợi ý và receipt chống gửi trùng. Không thêm dependency hoặc migration.

## Số đo local

Fixture có 7 từ, một tên riêng điền sẵn, audio ghi âm, chấm đúng một từ:

| Chỉ số | Kết quả |
| --- | --- |
| JSON đầy đủ | 1.973 byte |
| JSON compact | 420 byte, giảm khoảng 79% |
| SELECT toàn request compact | 8, gồm cả xác thực và JDBC receipt |
| Tổng SQL compact | 12, gồm ghi lần thử, event và receipt |
| Đọc danh sách `word_attempts` | 1 lần |
| Đếm số câu trong bài khi chấm compact | 0 lần |

Số đo được khóa bằng `PracticeWordCheckIntegrationTest`, dùng `JdbcQueryCounter` hiện có, không dựa vào cache persistence context của test. Các lượt service/request chạy transaction thực và commit riêng.

Đây là số đo payload/truy vấn local, **không phải** mức giảm latency production. Mạng, thời gian chờ khóa và tải DB vẫn có thể chi phối thời gian phản hồi. Không suy ra giảm 79% payload tương đương tăng 79% tốc độ.

## Kiểm tra có thể chạy lại

```powershell
node --test src/test/js/*.cjs
mvn --batch-mode --no-transfer-progress '-Dtest=PracticeWordCheckIntegrationTest,PracticeServiceIntegrationTest,DataContractIntegrationTest,ListeningLearningIntegrationTest,RoomControllerIntegrationTest,HistoryIntegrationTest' '-Ddictalab.testcontainers=true' '-Dspring.profiles.active=postgres-test' '-Ddictalab.build.directory=target/p2-postgres' test
```

Test Chrome dùng profile tạm và focus emulation qua DevTools để kiểm tra sự kiện focus/blur native ổn định, kể cả khi cửa sổ Chrome khác đang hoạt động; không dùng profile người dùng hoặc tắt sandbox. Test compact được thêm vào profile reliability và gate bắt buộc của CI PostgreSQL.

Kết quả local: 259/259 test JavaScript pass (không skip, có Chrome), 63/63 test liên quan trên PostgreSQL 17.11 pass, 50/50 trên PostgreSQL 18.4 pass; lượt PostgreSQL 18 gồm 6 test compact, test cạnh tranh ghi và retry. Đây là các nhóm test đã chọn, không phải toàn bộ bộ Java/reliability. UTF-8 và `git diff --check` pass.

Phạm vi theo Ponytail: chỉ sửa luồng P2 và các caller/test liên quan. JPA Query Reviewer định hướng query budget; các skill Spring testing định hướng kiểm tra transaction, quyền, CSRF và retry. Encoding Guard kiểm tra UTF-8 sau chỉnh sửa.

Chưa triển khai hoặc đo lại trên production. Commit/push nhánh không đồng nghĩa deployment.
