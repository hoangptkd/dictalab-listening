# DictaLab — English Listening Dictation

MVP web luyện nghe chép chính tả tiếng Anh theo CEFR A1–B2. Người học nghe từng câu, điền từng từ vào ô có độ dài tương ứng, nhận phản hồi ngay và theo dõi khả năng nghe thực tế qua chỉ số **first-attempt accuracy**.

## Chức năng đã có

- Đăng ký, đăng nhập, đăng xuất bằng email; mật khẩu được băm BCrypt và phiên đăng nhập dùng cookie HttpOnly.
- Lịch sử luyện, điểm số, dashboard và gợi ý bài được tách riêng theo từng tài khoản.
- Chọn level A1, A2, B1, B2 và bài luyện.
- Mỗi bài gồm nhiều câu; mỗi từ có một ô nhập riêng và độ rộng theo độ dài từ.
- Từ đúng chuyển màu xanh và có hiệu ứng; từ sai được đánh dấu để thử lại.
- Ghi nhận số lần nghe và thời gian làm từng câu.
- Ba mức gợi ý: chữ cái đầu, mở một từ, mở toàn bộ câu.
- Từ/câu đã mở đáp án không được tính là trả lời đúng cuối cùng.
- Sau khi mở đáp án, người học bắt buộc nghe lại trọn câu trước khi tiếp tục.
- Phân loại từ sai thành “biết nhưng nghe lỡ” hoặc “từ chưa biết”.
- Lưu first-attempt accuracy, final accuracy, replay, hint/reveal, loại lỗi, thời gian và overall score.
- Dashboard theo thời gian, level, loại lỗi và gợi ý bài tiếp theo.
- Giao diện responsive, hỗ trợ bàn phím, trình đọc màn hình và chế độ giảm chuyển động.
- 20 bài ELLLO A1–B2 với 910 câu từ 79 bản ghi hội thoại người thật; trung bình khoảng 5 từ/câu.

## Công nghệ

- Java 17
- Spring Boot 4.1.1
- Spring MVC, Spring Security, Spring Data JPA, Bean Validation, Actuator
- PostgreSQL 18 và Flyway; H2 chỉ dùng cho test tự động
- HTML, CSS và JavaScript thuần
- Audio người thật từ ELLLO; Web Speech API chỉ dùng làm phương án dự phòng

## Chạy local với PostgreSQL

Yêu cầu Java 17+, Maven 3.9+ và Docker Desktop. Lần đầu tiên, sao chép `.env.example`
thành `.env` rồi đổi `POSTGRES_PASSWORD`. File `.env` đã được Git bỏ qua.

```powershell
Copy-Item .env.example .env
# Sửa POSTGRES_PASSWORD trong .env, sau đó chạy:
.\scripts\run-local.ps1
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

Test hiện có bao phủ tokenizer, công thức chấm điểm, luồng hoàn thành, ràng buộc nghe lại sau khi reveal,
đăng ký, session đăng nhập, CSRF và quyền truy cập API.
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
