# DictaLab — English Listening Dictation

MVP web luyện nghe chép chính tả tiếng Anh theo CEFR A1–B2. Người học nghe từng câu, điền từng từ vào ô có độ dài tương ứng, nhận phản hồi ngay và theo dõi khả năng nghe thực tế qua chỉ số **first-attempt accuracy**.

## Chức năng đã có

- Đăng ký, đăng nhập, đăng xuất bằng email; mật khẩu được băm BCrypt và phiên đăng nhập dùng cookie HttpOnly.
- Lịch sử luyện, điểm số, dashboard và gợi ý bài được tách riêng theo từng tài khoản.
- Chọn level A1, A2, B1, B2 và bài luyện.
- Mỗi bài gồm nhiều câu; mỗi từ có một ô nhập riêng và độ rộng theo độ dài từ.
- Từ đúng chuyển màu xanh và có hiệu ứng; từ sai được đánh dấu để thử lại.
- Ghi nhận số lần nghe và thời gian làm từng câu.
- Ghi từng lần nhập: đáp án thô/chuẩn hóa, số lần thử, đúng/sai và thời gian phản hồi.
- Cho chọn tốc độ 0,75×/1×/1,25× và đo mức phụ thuộc vào nghe chậm.
- Ba mức gợi ý: chữ cái đầu, mở một từ, mở toàn bộ câu.
- Từ/câu đã mở đáp án không được tính là trả lời đúng cuối cùng.
- Sau khi mở đáp án, người học bắt buộc nghe lại trọn câu trước khi tiếp tục.
- Phân loại từ sai thành “biết nhưng nghe lỡ” hoặc “từ chưa biết”.
- Lưu first-attempt accuracy, final accuracy, replay, hint/reveal, loại lỗi, thời gian và overall score.
- Dashboard theo thời gian, level, loại lỗi và gợi ý bài tiếp theo.
- Ước lượng năng lực nghe A1–B2 kèm độ tin cậy từ tối đa 10 bài chưa từng học gần nhất.
- Giao diện responsive, hỗ trợ bàn phím, trình đọc màn hình và chế độ giảm chuyển động.
- 20 bài ELLLO A1–B2 với 910 câu từ 79 bản ghi hội thoại người thật; trung bình khoảng 5 từ/câu.
- Khu vực quản trị nội dung: tạo bản nháp, thêm/sửa câu, tải hoặc thay audio, nghe thử và xuất bản ngay, không cần build hay deploy lại JAR.
- **Luyện cùng bạn (phòng 1–1):** Mời bạn bằng link, gọi thoại qua WebRTC và học chung bài nghe. Chủ phòng chọn bài/chuyển câu; hai người xem phần nhập của nhau trực tiếp, nhưng lưu phiên và kết quả riêng. Tối đa 2 người mỗi phòng.

## Công nghệ

- Java 17
- Spring Boot 4.1.1
- Spring MVC, Spring Security, Spring Data JPA, Bean Validation, Actuator
- **Spring WebSocket** (signaling server cho Voice Room)
- **WebRTC** (peer-to-peer audio, phía client)
- PostgreSQL 18 và Flyway; H2 chỉ dùng cho test tự động
- HTML, CSS và JavaScript thuần
- Audio người thật từ ELLLO; Web Speech API chỉ dùng làm phương án dự phòng

## Chạy local với PostgreSQL

Yêu cầu Java 17+, Maven 3.9+, PowerShell 7 và Docker Desktop (hoặc cluster PostgreSQL native đã có). Lần đầu tiên, sao chép `.env.example`
thành `.env` rồi đổi `POSTGRES_PASSWORD`. File `.env` đã được Git bỏ qua.

```powershell
Copy-Item .env.example .env
# Sửa POSTGRES_PASSWORD trong .env, sau đó chạy bằng PowerShell 7:
pwsh -File .\scripts\run-local.ps1
```

Script khởi động PostgreSQL trong Docker ở cổng cố định `15433`, chờ database sẵn sàng rồi chạy
Spring Boot ở [http://localhost:18081](http://localhost:18081). Health check ở
[http://localhost:18081/actuator/health](http://localhost:18081/actuator/health).

Nếu dùng cluster native tại `data/postgres-local`, đặt `DICTALAB_PG_CTL` trong `.env` tới
file `pg_ctl.exe`. Script chỉ khởi động cluster sẵn có, không tạo lại hoặc xóa database.

Hai cổng local được cố định trong `.env`: web `18081` và PostgreSQL `15433`. Script không tự đổi
sang cổng khác; nếu một cổng bị chương trình khác chiếm, nó sẽ dừng và báo rõ cổng đang xung đột.

Để dừng ứng dụng, nhấn `Ctrl+C`. PostgreSQL tiếp tục chạy để giữ dữ liệu; có thể dừng bằng
`docker compose stop postgres` và chạy lại bằng script trên.

Dữ liệu PostgreSQL được giữ trong Docker volume `dictalab_postgres_data`. Flyway tự động tạo schema,
nạp 20 bài ELLLO và mốc phát cho 910 câu ở lần chạy đầu tiên. File H2 cũ trong `data/` không bị
xóa, nhưng ứng dụng không còn dùng file đó khi chạy local.

79 MP3 hội thoại nằm trong `src/main/resources/static/audio/elllo`. Mỗi câu trong
database trỏ tới một file hội thoại kèm `audio_start_seconds` và `audio_end_seconds`;
frontend dùng Web Audio API để chỉ phát đúng khoảng đó. Các mốc căn chỉnh nguồn nằm
trong `audio/elllo/segments.json`, vì vậy không cần lưu 910 MP3 nhỏ riêng biệt.

Để tính lại mốc câu, cần cài FFmpeg và dependency căn chỉnh giọng nói:

```powershell
python -m pip install -r scripts/requirements-audio.txt
python scripts/split-elllo-sentences.py --force
```

Nếu thay đổi mốc trên môi trường đã chạy Flyway, hãy tạo một migration tiến tới mới;
không sửa V7 hoặc migration cũ đã được áp dụng.

## Voice Room — Luyện cùng bạn

Tính năng gọi thoại 1-1 dùng **WebRTC** để truyền âm thanh thẳng giữa hai trình duyệt (peer-to-peer) và **WebSocket** để trao đổi tín hiệu kết nối (signaling).

### Cách dùng

1. Đăng nhập, vào tab **Luyện cùng bạn** → **Tạo phòng**.
2. Sao chép link mời và gửi cho người bạn qua ứng dụng nhắn tin.
3. Bạn mở link, xin tham gia → chủ phòng bấm **Chấp nhận**.
4. Cả hai bấm **Tham gia cuộc gọi** → cho phép truy cập mic → gọi thoại bắt đầu.
5. Chủ phòng bấm **Chọn bài học chung**. Hai người nhập đáp án riêng và thấy phần nhập của nhau trong lúc gõ.
6. Cả hai bấm **Sẵn sàng**, rồi chủ phòng bấm **Chuyển câu cho cả hai**. Kết quả học chung không dùng để ước lượng năng lực nghe độc lập.
7. Bấm **Tắt mic** / **Bật mic** để kiểm soát âm thanh. Bấm **Rời phòng** để kết thúc.

### Yêu cầu kỹ thuật

| Điều kiện | Chi tiết |
|---|---|
| HTTPS | Bắt buộc trên production để WebRTC hoạt động. Local dev (`localhost`) được miễn. |
| TURN server | Không bắt buộc nhưng khuyến nghị nếu hai thiết bị ở hai mạng khác nhau (VD: mạng mobile ↔ mạng WiFi nhà). Nếu không có TURN, cuộc gọi có thể thất bại khi qua NAT nghiêm ngặt. |

### Cấu hình TURN (tùy chọn)

Nếu bạn có TURN server (ví dụ Coturn), thêm vào `.env`:

```env
DICTALAB_TURN_URL=turn:your-turn.example.com:3478
DICTALAB_TURN_SECRET=your-shared-secret
DICTALAB_TURN_TTL_SECONDS=3600
```

Credentials được tính tự động theo chuẩn RFC 5766 (HMAC-SHA1) và cấp cho người dùng đã xác thực qua endpoint `/api/v1/rooms/{roomId}/ice-config`. Bỏ trống nếu không dùng TURN — ứng dụng tự fallback về Google STUN.

### Allowed origins

Để WebSocket handshake không bị từ chối khi chạy production hoặc đằng sau reverse proxy:

```env
DICTALAB_ALLOWED_ORIGINS=https://your-domain.com,https://www.your-domain.com
```

## Thêm bài nghe sau khi đã deploy

Nội dung do quản trị viên tạo được lưu tách khỏi JAR:

- thông tin bài và transcript nằm trong PostgreSQL;
- audio tải lên nằm tại `DICTALAB_MEDIA_ROOT` (production khuyến nghị `C:\DictaLab\media`);
- bài mới bắt đầu là **bản nháp** và chỉ xuất hiện với người học sau khi đủ audio rồi bấm **Xuất bản bài**;
- thay JAR hoặc chạy workflow deploy không xóa PostgreSQL và thư mục media.

Để cấp quyền quản trị trên Windows VPS lần đầu, chạy PowerShell với quyền Administrator:

```powershell
powershell.exe -ExecutionPolicy Bypass -File C:\DictaLab\staging\configure-admin-windows-vps.ps1 `
  -AdminEmails "email-cua-ban@example.com"
```

Sau đó đăng xuất và đăng nhập lại. Mở **Trang quản trị** bên cạnh tài khoản hoặc truy cập `/admin`. Có thể khai báo
nhiều email bằng dấu phẩy. Script cũng cố định thư mục audio tại `C:\DictaLab\media` và khởi động lại service.

Khu vực `/admin` dùng trang HTML và menu riêng: Bài nghe, Nghe hiểu, Người dùng, Yêu cầu hỗ trợ.
Server yêu cầu quyền ADMIN cho mọi đường dẫn quản trị, kể cả `/admin.html`; khách được chuyển
về đăng nhập và người dùng thường nhận trang 403. Phiên đăng nhập và API được tái sử dụng.

Quy trình thêm một bài:

1. Chọn **Trang quản trị → Bài nghe → Bài mới**, nhập level, tên, chủ đề và mô tả.
2. Thêm transcript chính xác cho từng câu.
3. Tải MP3/M4A/WAV người thật tương ứng với từng câu rồi bấm **Nghe thử**.
4. Sắp xếp câu và bấm **Xuất bản bài**. Người học thấy bài ngay, không cần deploy lại.

Giới hạn mặc định là 25 MB/file. Tên file trên máy chủ được sinh bằng UUID; tên file từ trình duyệt
không được dùng làm đường dẫn. Backend kiểm tra phần mở rộng, MIME type và chữ ký file. Khi bài đã có
lịch sử học, transcript/audio bị khóa để kết quả cũ không thay đổi; hãy tạo bài mới nếu cần thay nội dung.

Khi chạy local, đặt email tài khoản của bạn trong `.env`:

```text
DICTALAB_MEDIA_ROOT=./data/media
DICTALAB_ADMIN_EMAILS=your-email@example.com
```

Email trong `DICTALAB_ADMIN_EMAILS` chỉ nhận quyền quản trị sau khi xác minh bằng liên kết gửi đến
chính địa chỉ đó. Cần cấu hình `DICTALAB_PUBLIC_URL`, `DICTALAB_MAIL_FROM`, `SMTP_HOST`, `SMTP_PORT`,
`SMTP_USERNAME`, `SMTP_PASSWORD` và TLS cho backend trước khi dùng xác minh email, đổi email hoặc
đặt lại mật khẩu. Nếu SMTP chưa cấu hình, các thao tác này trả lỗi rõ ràng; người dùng vẫn có thể
đăng ký và học bằng tài khoản thường. Tài khoản admin đã tồn tại giữ nguyên vai trò hiện tại,
vì vậy cần rà danh sách admin trước khi phát hành bản mới.

Tên riêng và từ khó được phân loại ngoại tuyến bằng `scripts/enrich-preview-vocabulary.py`, sau đó
lưu vào PostgreSQL. API key ShopAIKey chỉ nhập khi chạy script trên máy quản trị; web và VPS không giữ
key cho bước này. Module Listening dùng khóa ShopAIKey trên backend để tạo câu hỏi và chấm nhớ ý.

```powershell
python scripts/enrich-preview-vocabulary.py `
  --ssh-host your-vps `
  --ssh-key C:\path\to\vps-key `
  --prompt-api-key `
  --apply
```

### Kết nối tới PostgreSQL khác

```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5432/dictalab"
$env:DB_USERNAME = "dictalab_app"
$env:DB_PASSWORD = "your-password"
$env:PORT = "18081"
mvn spring-boot:run
```

Không lưu thông tin đăng nhập database vào source code.

## Luồng luyện tập

1. Chọn level và lesson.
2. Nhấn **Nghe câu**; bộ đếm thời gian bắt đầu và replay được ghi nhận.
3. Nhập từng từ. Lần kiểm tra đầu tiên của mỗi từ được giữ lại để tính first-attempt accuracy.
4. Có thể xin hint hoặc mở đáp án, nhưng điểm độc lập sẽ giảm.
5. Với từ sai, chọn “biết nhưng lỡ” hoặc “từ mới”.
6. Nếu đã mở một từ hoặc cả câu, nghe lại trọn câu rồi mới được chuyển tiếp.
7. Hoàn thành lesson để xem kết quả, dashboard và bài được đề xuất.

## AI Coach sau Dictation

Trang kết quả hiển thị độ đúng lần nhập đầu, tối đa 3 điểm cần luyện, 1 nhận xét tiến bộ và
tối đa 3 hành động (một hành động chính). Smart Review chọn tối đa 12 lỗi thật; bài có ít lỗi
sẽ hiển thị ít hơn. Ưu tiên từ lặp lại, từ biết nhưng nghe lỡ, contractions, số và dấu hiệu cần
kiểm tra về weak forms/âm cuối. Chính tả và tên riêng có ưu tiên thấp hơn. Các dấu hiệu âm học
là giả thuyết từ đáp án, không phải phân tích trực tiếp tín hiệu audio.

Người học có thể nghe lại đoạn audio gốc, luyện điền từ tối đa 3 lỗi cùng nhóm, hoặc lưu lỗi
vào Error Bank theo tài khoản. Lượt ôn không thay đổi điểm Dictation. Toàn bộ câu, telemetry,
ước lượng năng lực và xuất dữ liệu nằm trong **Phân tích chi tiết**, mặc định đóng.

Backend kết nối [chatgpt-api-web](https://github.com/MrBalourd/chatgpt-api-web) qua
`POST /v1/chat/completions`. Chạy bridge theo README của dự án và đăng nhập ChatGPT trong
Chrome của bridge trước; cấu hình sau áp dụng trên **máy chạy backend DictaLab**:

```env
DICTALAB_COACH_ENABLED=true
DICTALAB_COACH_URL=http://127.0.0.1:3000/v1/chat/completions
DICTALAB_COACH_MODEL=chatgpt-web
DICTALAB_COACH_TIMEOUT=75s
DICTALAB_COACH_API_KEY=
```

Để chạy cả DictaLab và bridge trên Windows (cần Node.js 18.18+, Git, Chrome):

```powershell
pwsh -File .\scripts\run-local.ps1 -WithCoach
```

Script tải bridge vào thư mục Git bỏ qua `data/chatgpt-api-web` nếu chưa có, cài dependencies,
áp dụng `scripts/chatgpt-web-compat.patch` cho giao diện ChatGPT hiện tại rồi chạy bridge tại
`127.0.0.1:3000`, Chrome CDP tại `127.0.0.1:9223`. Đăng nhập ChatGPT trong Chrome vừa mở;
profile lưu ở `data/chatgpt-api-web/data/chrome-profile` để dùng lại. Không đưa profile vào Git.
Nếu Chrome ở vị trí khác, đặt `DICTALAB_COACH_CHROME_PATH` trong `.env`.
Log bridge ở `data/chatgpt-api-web/bridge.out.log` và `bridge.err.log`.
Đây là bridge dùng tài khoản ChatGPT trên web; khi giao diện ChatGPT thay đổi, bản vá có thể cần cập nhật.

Kiểm tra thực tế sau khi đăng nhập (tạo tài khoản QA riêng và một bài hoàn thành có lỗi giả lập):

```powershell
pwsh -File .\scripts\check-coach-local.ps1
```

Check yêu cầu `source=AI`, giới hạn insight/action/review, lưu báo cáo và xác nhận điểm không đổi.
Tài khoản QA tồn tại trong database local; script không thay đổi điểm của tài khoản đang dùng.

Trên Windows VPS, bridge có thể chạy bằng `scripts/start-coach-bridge-vps.ps1` qua Scheduled Task
`DictaLabCoachBridge` trong phiên Administrator tương tác. Sau khi đăng nhập ChatGPT trong Chrome
của VPS, chạy `scripts/enable-coach-vps.ps1` trên VPS; script kiểm tra phản hồi ChatGPT thật trước
khi bật AI Coach cho service và khôi phục cấu hình nếu app không lên health. Scheduled Task cần
phiên Administrator đăng nhập lại sau khi máy khởi động; khi phiên đó chưa có, trang kết quả vẫn
dùng Smart Review theo quy tắc backend.

Nếu bridge chạy trên máy khác, đặt URL tới kết nối riêng có bảo vệ; `127.0.0.1` luôn là máy
backend, không phải máy người học. Khóa tùy chọn chỉ dùng khi proxy trước bridge có xác thực.

Kết quả được lưu trước khi gọi AI. Frontend lấy gợi ý deterministic bằng
`GET /api/v1/practice-sessions/{id}/coach`, sau đó gọi `POST` cùng địa chỉ để bổ sung AI.
Bridge không hỗ trợ JSON Schema enforcement: prompt yêu cầu JSON, backend parse nghiêm ngặt,
kiểm tra số lượng/độ dài, pattern và evidence IDs; phản hồi free-text hoặc sai schema bị loại.
Không gửi cookie, email, tên tài khoản hoặc lịch sử hội thoại; chỉ gửi level, điểm lần đầu,
nhận xét tiến bộ và tối đa 40 lỗi/ngữ cảnh. Mỗi yêu cầu dùng hội thoại bridge mới.

AI chọn ưu tiên và đề xuất bài ôn; backend vẫn tính điểm và xác định bằng chứng. Nhận xét tiến bộ
so với tối đa 5 bài mới cùng cấp độ, học riêng và hoàn thành trước phiên đang xem; cần ít nhất
2 bài để so sánh. Bài học lại/học chung được ghi rõ chưa thể so sánh. Phản hồi AI hợp lệ được
lưu một lần cho mỗi phiên; bridge lỗi/timeout/đang bận thì giữ gợi ý deterministic và cho thử lại.
`DICTALAB_COACH_ENABLED=false` tắt việc gửi dữ liệu sang AI nhưng vẫn dùng Smart Review/Error Bank.

Migration V21 chỉ thêm bảng `dictation_coach_reports` và `dictation_error_bank`; không cập nhật
điểm hoặc transcript cũ. Trên PostgreSQL 18, đây là thay đổi cộng thêm; triển khai cần theo dõi
lock khi tạo foreign key. Nếu migration gặp lock kéo dài, dừng đợt deploy và thử lại sau khi
xử lý transaction giữ lock; không sửa checksum migration đã áp dụng. Bản ứng dụng cũ có thể
bỏ qua hai bảng này khi rollback. Kiểm tra sau deploy bằng phiên đã hoàn thành và thao tác lưu lỗi.

Kiểm thử phần Coach:

```powershell
mvn '-Dtest=CoachAnalysisTest,ChatGptWebCoachClientTest,CoachServiceIntegrationTest,PracticeServiceIntegrationTest' test
node --test src/test/js/coach.test.cjs
```

## Cách tính điểm

```text
overall = 65% × first-attempt accuracy
        + 25% × final accuracy
        + 10% × independence
```

`independence` bắt đầu từ 100 và giảm theo số hint, reveal và lượt replay vượt quá một lần/câu. Các hệ số nằm trong `dictalab.scoring` tại `application.yml` để có thể điều chỉnh mà không sửa business logic.

Thuật toán gợi ý ưu tiên kết quả gần đây của cùng level:

- từ 85% first-attempt accuracy: đề xuất level cao hơn nếu có;
- dưới 55%: đề xuất level thấp hơn để củng cố;
- còn lại: tiếp tục cùng level và ưu tiên bài chưa hoàn thành.

## Ước lượng năng lực nghe

Sau mỗi bài, hệ thống trả một mức A1–B2 và độ tin cậy. Đây là chỉ báo chẩn đoán nội bộ, không phải
chứng chỉ CEFR chuẩn hóa. Kết quả được đánh dấu **tạm thời** nếu có dưới 5 bài chưa từng học hoặc bài
chưa được hiệu chỉnh.

Ước lượng chỉ dùng tối đa 10 bài mới gần nhất và tính đến:

- CEFR, tốc độ nói từ mốc audio, số người nói và ba mức khó từ vựng/ngữ pháp/câu;
- độ chính xác lần đầu không trợ giúp, lần đầu và cuối;
- độ chính xác từ chức năng so với từ nội dung;
- replay vượt quá lượt nghe đầu, gợi ý, nghe chậm, reveal và thời gian phản hồi;
- độ đầy đủ của telemetry, số từ quan sát, độ phủ level và trạng thái hiệu chỉnh để tính confidence.

Lỗi từ được suy luận thành bỏ sót, thay từ, chính tả, biến thể từ và sai thứ tự. Với giao diện mỗi từ
một ô hiện tại, lỗi **chèn từ** chưa thể nhận diện đáng tin cậy; lỗi nối âm cũng cần dữ liệu căn chỉnh
âm vị hoặc gắn nhãn thủ công. Hai tín hiệu này được giữ là giới hạn rõ ràng thay vì đoán và làm sai mức.

## API chính

| Method | Endpoint | Mục đích |
|---|---|---|
| `GET` | `/api/auth/csrf` | Khởi tạo CSRF token cho web |
| `POST` | `/api/auth/register` | Tạo tài khoản và đăng nhập |
| `POST` | `/api/auth/login` | Đăng nhập |
| `GET` | `/api/auth/me` | Thông tin tài khoản hiện tại |
| `POST` | `/api/auth/logout` | Đăng xuất |
| `GET` | `/api/v1/catalog/levels` | Danh sách level |
| `GET` | `/api/v1/catalog/lessons?level=A1` | Danh sách lesson theo level |
| `POST` | `/api/v1/practice-sessions` | Bắt đầu hoặc tiếp tục lesson |
| `GET` | `/api/v1/practice-sessions/{sessionId}` | Lấy trạng thái hiện tại |
| `POST` | `.../sentences/{sentenceId}/replay-events` | Ghi nhận bắt đầu/kết thúc nghe |
| `POST` | `.../sentences/{sentenceId}/words/{wordIndex}/checks` | Kiểm tra một từ |
| `POST` | `.../sentences/{sentenceId}/hints` | Dùng hint/reveal |
| `POST` | `.../sentences/{sentenceId}/classifications` | Phân loại từ bị sai |
| `POST` | `.../sentences/{sentenceId}/complete` | Hoàn tất câu |
| `GET` | `/api/v1/dashboard` | Dashboard và recommendation của tài khoản đang đăng nhập |
| `GET/POST` | `/api/admin/lessons` | Liệt kê hoặc tạo bản nháp (chỉ ADMIN) |
| `POST/PUT/DELETE` | `/api/admin/lessons/{id}/sentences/**` | Quản lý câu và tải audio (chỉ ADMIN) |
| `PUT` | `/api/admin/lessons/{id}/publication` | Xuất bản hoặc gỡ xuất bản (chỉ ADMIN) |

### Listening Comprehension

Vào `/listening` để chọn bài một hoặc hai lượt. Mỗi lượt phát trọn audio một lần, tiếp theo là
ghi nhớ tự do (English/Vietnamese) và 5–8 câu hỏi. Đáp án, transcript và nghe từng đoạn chỉ mở
sau khi hoàn thành assessment. Nếu lượt nghe bị gián đoạn, người học kết thúc phiên đó và bắt đầu
phiên mới; lượt đã gián đoạn không có báo cáo. Báo cáo dùng lượt 1 làm chỉ số chính, hiển thị điểm
hiểu/nhớ theo lượt, mức thay đổi và độ chính xác theo loại câu hỏi.

Một bài A1 mẫu được tạo bởi migration. Quản trị viên mở **Trang quản trị → Nghe hiểu** tại `/admin/listening`, chọn audio đã căn mốc để ShopAIKey tạo bản nháp hoặc nhập thủ công, sau đó duyệt/sửa
facts, câu hỏi, đáp án và giải thích trước khi xuất bản. Đặt `SHOPAIKEY_API_KEY` trên máy chủ;
có thể đổi `SHOPAIKEY_MODEL` và `SHOPAIKEY_URL`. Nếu chưa có khóa, phần câu hỏi vẫn chấm được,
còn recall hiển thị là chưa chấm. Bản nháp cần audio hội thoại và mốc thời gian câu đã có trong
lesson. Bài đã xuất bản không thể sửa trực tiếp; quản trị viên tạo bản sửa mới và xuất bản
phiên bản đó, trong khi các kết quả cũ vẫn giữ nội dung của phiên bản trước.

Trạng thái “chưa ghi nhận học” dựa trên phiên Dictation/Listening, sự kiện nghe từ vựng trước bài
và khai báo của người học. Vì audio vẫn có thể đã được nghe bên ngoài ứng dụng,
hiện chưa coi lượt này là benchmark sạch đã xác minh. Các file transcript `.json`/`.txt` dưới
`/audio/elllo/` bị chặn truy cập trực tiếp, nhưng audio và nội dung Dictation đã công bố vẫn
có thể được xem theo flow cũ. Các API Listening giữ session theo chủ sở hữu và dùng CSRF.

Người học quản lý hồ sơ tại `/account`, báo audio/transcript/điểm sai hoặc yêu cầu xóa tài khoản
tại `/support`. Admin xử lý người dùng tại `/admin/users`, yêu cầu hỗ trợ tại `/admin/support`.
Đổi mật khẩu, khóa tài khoản và đổi vai trò thu hồi phiên đăng nhập cũ. Xóa tài khoản theo yêu cầu
sẽ xóa lịch sử học và dữ liệu hỗ trợ liên quan; thao tác này không thể hoàn tác.

Tất cả API `/api/v1/**` yêu cầu đăng nhập. Backend lấy user ID từ session bảo mật; frontend không thể
truyền một learner ID khác để xem hoặc sửa lịch sử của người khác. Các request thay đổi dữ liệu được
bảo vệ bằng CSRF token.

## Cấu trúc dự án

```text
src/main/java/com/dictalab
├── config       cấu hình chấm điểm
├── domain       JPA entities và enum
├── dto          request/response records
├── repository   Spring Data repositories
├── service      nghiệp vụ luyện tập, chấm điểm, dashboard, gợi ý
└── web          REST controllers và xử lý lỗi

src/main/resources
├── db/migration Flyway schema và dữ liệu mẫu
└── static       giao diện HTML/CSS/JS
```

## Lưu ý cho production

Bản local đã dùng audio ELLLO người thật. API hiện vẫn gửi `speechText` để hỗ trợ phương án đọc dự phòng, vì vậy transcript có thể được xem trong DevTools. Production đã dùng PostgreSQL và HTTPS với cookie `Secure`. Vẫn cần trả `audioUrl` được bảo vệ thay cho transcript chưa reveal, đưa audio lên object storage/CDN và bổ sung rate limiting trước khi public. Kiểm tra điều khoản ELLLO trước khi phân phối công khai hoặc dùng cho mục đích thương mại.

## Kiểm thử

```powershell
mvn test
node --check src/main/resources/static/js/app.js
```

Test hiện có bao phủ tokenizer, công thức chấm điểm, telemetry từng lần nhập/tốc độ, giới hạn 10 bài mới,
phân loại lỗi, luồng hoàn thành, ràng buộc nghe lại sau khi reveal, đăng ký, session đăng nhập, CSRF và quyền truy cập API.
Test dùng H2 in-memory riêng và không tác động tới dữ liệu PostgreSQL local.

## CI/CD lên Windows Server 2019

Workflow `.github/workflows/deploy.yml` chạy khi có commit được push vào nhánh `main`:

1. Chạy toàn bộ test và build JAR bằng Java 17.
2. Kết nối VPS bằng SSH deploy key, tải bản build và script triển khai lên thư mục staging.
3. Tạo và xác minh bản sao PostgreSQL riêng tư trước khi chạy migration.
4. Xác minh SHA-256, cập nhật Windows Service `DictaLab` và kiểm tra health endpoint.
5. Nếu backup thất bại, pipeline dừng trước migration; nếu bản mới không khởi động thành công,
   tự động phục hồi JAR trước đó.

Khai báo bốn repository secrets tại **Settings → Secrets and variables → Actions**:

| Secret | Giá trị |
|---|---|
| `VPS_HOST` | Địa chỉ IP hoặc hostname của VPS |
| `VPS_USER` | Tài khoản Windows dùng để deploy |
| `VPS_SSH_KEY` | Toàn bộ nội dung private SSH key dành riêng cho deploy |
| `VPS_KNOWN_HOSTS` | Public host key của VPS, lấy bằng `ssh-keyscan -H <VPS_HOST>` |

Không thêm mật khẩu VPS, mật khẩu PostgreSQL hoặc private key vào repository. Database secrets tiếp tục
chỉ nằm trên VPS. Có thể chạy deploy thủ công bằng nút **Run workflow** trong GitHub Actions.

## HTTPS trên VPS

Ứng dụng production mở tại [https://160.191.244.27:18443/](https://160.191.244.27:18443/).
Địa chỉ HTTP cũ ở cổng `18081` chuyển hướng sang HTTPS và giữ nguyên đường dẫn, query.
Cổng `443` thuộc ứng dụng WebLens trên cùng VPS, nên DictaLab dùng cổng HTTPS riêng.

Windows Service `DictaLabHttps` chạy Caddy với [Caddyfile](scripts/https/Caddyfile), chuyển tiếp
HTTP và WebSocket vào `127.0.0.1:18082`. Service `DictaLab` chỉ nghe trên localhost, dùng cookie
`Secure` và xử lý `X-Forwarded-*` từ proxy. Chứng chỉ IP của Let's Encrypt có hạn khoảng 6 ngày;
Caddy tự gia hạn. Giữ service `DictaLabHttps` chạy và cổng `80` mở để xác thực/gia hạn chứng chỉ;
cổng `18443` phục vụ người dùng. Kiểm tra HTTPS sau khi triển khai bằng
`https://160.191.244.27:18443/actuator/health`.
Khóa chứng chỉ trong `C:\DictaLab\https\data` chỉ cho `SYSTEM` và `Administrators` truy cập;
[script phân quyền](scripts/https/secure-cert-storage.ps1) dùng lại được khi cần kiểm tra.
