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

## Công nghệ

- Java 17
- Spring Boot 4.1.1
- Spring MVC, Spring Security, Spring Data JPA, Bean Validation, Actuator
- PostgreSQL 18 và Flyway; H2 chỉ dùng cho test tự động
- HTML, CSS và JavaScript thuần
- Audio người thật từ ELLLO; Web Speech API chỉ dùng làm phương án dự phòng

## Chạy local với PostgreSQL

Yêu cầu Java 17+, Maven 3.9+, PowerShell 7 và Docker Desktop. Lần đầu tiên, sao chép `.env.example`
thành `.env` rồi đổi `POSTGRES_PASSWORD`. File `.env` đã được Git bỏ qua.

```powershell
Copy-Item .env.example .env
# Sửa POSTGRES_PASSWORD trong .env, sau đó chạy bằng PowerShell 7:
pwsh -File .\scripts\run-local.ps1
```

Script khởi động PostgreSQL trong Docker ở cổng cố định `15433`, chờ database sẵn sàng rồi chạy
Spring Boot ở [http://localhost:18081](http://localhost:18081). Health check ở
[http://localhost:18081/actuator/health](http://localhost:18081/actuator/health).

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

Sau đó đăng xuất và đăng nhập lại. Thanh điều hướng sẽ có mục **Quản trị nội dung**. Có thể khai báo
nhiều email bằng dấu phẩy. Script cũng cố định thư mục audio tại `C:\DictaLab\media` và khởi động lại service.

Quy trình thêm một bài:

1. Chọn **Quản trị nội dung → Bài mới**, nhập level, tên, chủ đề và mô tả.
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

Tên riêng và từ khó được phân loại ngoại tuyến bằng `scripts/enrich-preview-vocabulary.py`, sau đó
lưu vào PostgreSQL. API key ShopAIKey chỉ nhập khi chạy script trên máy quản trị; web và VPS không giữ
key, không gọi AI khi người dùng mở bài.

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

Bản local đã dùng audio ELLLO người thật. API hiện vẫn gửi `speechText` để hỗ trợ phương án đọc dự phòng, vì vậy transcript có thể được xem trong DevTools. Với bản production, chỉ nên trả `audioUrl` được bảo vệ và không đưa transcript chưa reveal vào response. Hãy dùng PostgreSQL, bật HTTPS với `SESSION_COOKIE_SECURE=true`, đưa audio lên object storage/CDN và bổ sung rate limiting trước khi public. Kiểm tra điều khoản ELLLO trước khi phân phối công khai hoặc dùng cho mục đích thương mại.

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
2. Kết nối VPS bằng SSH deploy key và tải bản build lên thư mục staging.
3. Xác minh SHA-256, cập nhật Windows Service `DictaLab` và kiểm tra health endpoint.
4. Nếu bản mới không khởi động thành công, tự động phục hồi JAR trước đó.

Khai báo bốn repository secrets tại **Settings → Secrets and variables → Actions**:

| Secret | Giá trị |
|---|---|
| `VPS_HOST` | Địa chỉ IP hoặc hostname của VPS |
| `VPS_USER` | Tài khoản Windows dùng để deploy |
| `VPS_SSH_KEY` | Toàn bộ nội dung private SSH key dành riêng cho deploy |
| `VPS_KNOWN_HOSTS` | Public host key của VPS, lấy bằng `ssh-keyscan -H <VPS_HOST>` |

Không thêm mật khẩu VPS, mật khẩu PostgreSQL hoặc private key vào repository. Database secrets tiếp tục
chỉ nằm trên VPS. Có thể chạy deploy thủ công bằng nút **Run workflow** trong GitHub Actions.
