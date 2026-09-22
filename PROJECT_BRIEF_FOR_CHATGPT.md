# Hồ sơ dự án DictaLab — dùng để đánh giá portfolio/CV

## 1. Tóm tắt dự án

**DictaLab** là web luyện nghe - chép chính tả tiếng Anh theo CEFR A1–B2. Thay vì chỉ chấm điểm cuối bài, hệ thống thu thập hành vi trong lúc người học nghe và nhập từng từ để ước lượng năng lực nghe, mức độ độc lập và kiểu lỗi.

- Website production: `http://160.191.244.27:18081/`
- Repository: `git@github.com-hoangptkd:hoangptkd/dictalab-listening.git`
- Stack chính: Java 17, Spring Boot 4, Spring Security, Spring Data JPA, PostgreSQL, Flyway, HTML/CSS/JavaScript thuần.
- Quy mô dữ liệu ban đầu: 20 bài A1–B2, 79 audio hội thoại người thật, khoảng 910 câu luyện.

Mục tiêu cá nhân của dự án là biến một nhu cầu học ngoại ngữ cụ thể thành một sản phẩm có khả năng giải quyết các bài toán kỹ thuật thực tế: thu thập dữ liệu hành vi, suy luận năng lực từ tín hiệu không hoàn hảo, quản lý nội dung, bảo mật tài khoản và vận hành/deploy một hệ thống web.

## 2. Bài toán thực tế và cách giải quyết

### Bài toán 1: Điểm cuối bài không phản ánh đúng khả năng nghe

Người học có thể hoàn thành bài sau nhiều lần nghe lại, dùng gợi ý hoặc xem đáp án. Nếu chỉ dùng final accuracy thì hệ thống đánh giá quá cao khả năng nghe độc lập.

**Giải pháp:** mỗi từ và mỗi câu được lưu telemetry chi tiết: đáp án gốc/đáp án chuẩn hóa, số lần thử, kết quả lần đầu/lần cuối, thời gian phản hồi, số lần phát lại, tốc độ phát, gợi ý, reveal/give-up và cách người học tự phân loại từ đã lỡ nghe hay từ mới.

Điểm bài học được tách thành:

```text
overall score = 65% × first-attempt accuracy
              + 25% × final accuracy
              + 10% × independence
```

`independence` giảm khi người dùng dùng hint, reveal hoặc nghe lại vượt lượt đầu. Các trọng số là cấu hình trong `application.yml`, không nằm cứng trong logic nghiệp vụ.

### Bài toán 2: Ước lượng CEFR từ dữ liệu ít và không đồng nhất

Mỗi bài có độ khó khác nhau; so sánh điểm thô giữa các bài là không đủ. Một bài B1 nói nhanh, nhiều người nói và câu phức phải được diễn giải khác một bài A1 đơn giản.

**Giải pháp:** hệ thống chọn tối đa 10 bài **chưa từng học** gần nhất, chỉ lấy lần tiếp xúc đầu tiên của mỗi bài. Thuật toán kết hợp:

- CEFR của bài;
- tốc độ nói (WPM) suy ra từ mốc audio và số từ;
- số người nói, accent profile;
- độ khó từ vựng, ngữ pháp và cấu trúc câu;
- unassisted first-pass accuracy, first-attempt accuracy và final accuracy;
- tỷ lệ từ chức năng/từ nội dung đúng;
- replay, nghe chậm, hint, reveal và thời gian phản hồi;
- độ phủ dữ liệu, số level đã trải nghiệm và trạng thái hiệu chỉnh bài để tính confidence.

Kết quả trả về gồm estimated level A1–B2, ability score 0–100, confidence band và cờ `provisional`. Hệ thống chủ động ghi rõ đây là chỉ báo chẩn đoán nội bộ, không phải chứng chỉ CEFR chuẩn hóa.

### Bài toán 3: Chỉ ra người học sai ở đâu để có hành động tiếp theo

Chỉ hiển thị “sai” không giúp người học cải thiện. Hệ thống phân loại lỗi ở cấp từ:

- omission (bỏ sót);
- substitution (thay từ);
- spelling;
- word form;
- word order;
- insertion được giữ trong model/API nhưng giao diện một ô một từ hiện chưa thể suy luận đáng tin cậy.

Người học có thể gắn nhãn từ sai là **biết nhưng nghe lỡ** hoặc **từ chưa biết**. Dashboard tổng hợp xu hướng, chia accuracy từ chức năng và từ nội dung, rồi đề xuất bài tiếp theo dựa trên kết quả gần đây cùng level.

### Bài toán 4: Luyện nghe theo câu phải chính xác nhưng không tạo hàng nghìn file audio

Audio gốc là hội thoại dài; tách 910 file MP3 nhỏ sẽ nặng, khó cập nhật và tạo chi phí quản lý.

**Giải pháp:** lưu 79 MP3 gốc và mốc `audio_start_seconds`/`audio_end_seconds` cho từng câu. Frontend sử dụng Web Audio API để phát đúng đoạn của câu. Script căn chỉnh audio có thể tính lại mốc khi nội dung thay đổi; trên môi trường có Flyway, cập nhật dữ liệu bằng migration tiến tới thay vì sửa migration đã chạy.

### Bài toán 5: Nội dung mới không nên bắt buộc build/deploy lại ứng dụng

**Giải pháp:** xây dựng khu vực quản trị nội dung. Admin có thể tạo bài nháp, thêm/sửa/sắp xếp câu, tải audio, nghe thử và xuất bản. Metadata/transcript lưu PostgreSQL; audio upload lưu ngoài JAR tại thư mục media. Khi bài đã có lịch sử học, transcript/audio bị khóa để không làm sai lệch dữ liệu đánh giá cũ.

## 3. Trải nghiệm người dùng đã triển khai

- Đăng ký, đăng nhập, đăng xuất bằng email.
- Danh mục bài theo A1, A2, B1, B2; hiển thị tiến độ đã làm và trạng thái làm lại.
- Mỗi câu là các ô nhập theo từng từ; phản hồi đúng/sai tức thời.
- Nhập bằng bàn phím mượt: Enter chuyển bước; Backspace xóa từ hiện tại hoặc quay về từ trước nếu ô trống; playback không được phép cướp focus khi người dùng đang nhập.
- Người dùng chọn tốc độ 0.75×, 1× hoặc 1.25×; hệ thống ghi nhận phụ thuộc vào nghe chậm.
- Có hint chữ đầu, reveal một từ, reveal toàn câu. Reveal không được tính là đúng và yêu cầu nghe lại toàn câu trước khi tiếp tục.
- Transcript cuối bài tô màu theo 7 nhóm hành vi: đúng lần 1, đúng lần 2, đúng lần 3–6, đúng từ lần 7, dùng chữ đầu, reveal một từ, reveal toàn câu.
- Preview 2–5 từ hiếm/chuyên biệt/vượt level trước bài; người học có thể nghe trước. Các từ preview được loại khỏi mẫu tính CEFR để tránh làm sai phép đo.
- Responsive cho điện thoại; có hỗ trợ keyboard, screen reader và reduced motion.

## 4. Kiến trúc và thiết kế kỹ thuật

```text
Browser (HTML/CSS/Vanilla JS)
  ├─ Web Audio API: phát audio theo đoạn câu
  ├─ API JSON + CSRF token
  └─ giao diện học, dashboard, quản trị

Spring Boot application
  ├─ Controllers: auth, catalog, practice, dashboard, admin
  ├─ Services: scoring, assessment, recommendation, error classification,
  │            content/media management, preview vocabulary
  ├─ Spring Security: session + CSRF + role ADMIN
  └─ JPA repositories

PostgreSQL + Flyway
  ├─ lessons / lesson_sentences
  ├─ practice_sessions / sentence_attempts
  ├─ word_attempts / word_attempt_events
  └─ lesson_preview_words

External media storage
  └─ audio người dùng upload, tách khỏi JAR
```

Một số quyết định đáng chú ý:

- Không dùng SPA framework: phạm vi MVP phù hợp với JavaScript thuần, giảm dependency và vẫn kiểm soát được hiệu năng/tương tác.
- `open-in-view=false`, Bean Validation tại biên API, domain/service/repository tách trách nhiệm.
- Flyway quản lý schema và dữ liệu seed; PostgreSQL dùng production, H2 chỉ cho integration test.
- Dùng optimistic locking (`version`) trên practice session để bảo vệ trạng thái luyện tập khi có request đồng thời/mạng chập chờn.
- Các thao tác API được kiểm tra ownership session trước khi đọc/ghi để người dùng không truy cập lịch sử của tài khoản khác.

## 5. Bảo mật và độ tin cậy

- Mật khẩu băm BCrypt.
- Session cookie `HttpOnly`, `SameSite=Lax`; cấu hình `Secure` theo môi trường.
- Spring Security, CSRF token cho các request thay đổi dữ liệu và phân quyền `ADMIN` cho API quản trị.
- Không trả stack trace hay lỗi nội bộ ra client.
- File audio upload giới hạn kích thước, kiểm tra extension, MIME type và magic signature; lưu dưới UUID để không tin tên file do trình duyệt gửi lên.
- PostgreSQL chỉ lắng nghe localhost trên VPS; secrets database nằm ngoài repository.
- Tài khoản admin được cấu hình qua biến môi trường, không hard-code trong mã nguồn.
- Deploy tự kiểm tra SHA-256 của artifact, health check và tự rollback về JAR trước đó nếu phiên bản mới không healthy.

## 6. Kiểm thử và chất lượng mã nguồn

Có test tự động cho các luồng và logic rủi ro cao:

- chấm điểm và các penalty;
- tokenizer transcript;
- phân loại lỗi từ;
- lifecycle luyện tập và telemetry;
- tính listening assessment;
- auth/CSRF;
- quản trị nội dung và personal content integration.

Pipeline CI chạy `mvn clean verify` trước khi deploy. Theo lịch sử Git hiện có, production chỉ nhận artifact sau khi build/test thành công.

## 7. Vận hành và CI/CD

Ứng dụng đang chạy trên Windows Server 2019 VPS ở cổng 18081. Quy trình deploy GitHub Actions:

```text
push main
  → GitHub Actions checkout
  → Java 17 + Maven clean verify
  → tạo executable JAR và SHA-256
  → SSH/SCP dùng deploy key + known_hosts
  → upload JAR lên VPS
  → Windows PowerShell kích hoạt release
  → health check /actuator/health
  → rollback tự động nếu lỗi
```

Trên VPS, Spring Boot chạy như Windows service qua NSSM; PostgreSQL là service riêng. Media và database được giữ lại khi thay JAR, nên deploy không làm mất bài do admin tạo hoặc lịch sử học của người dùng.

## 8. Những kỹ năng dự án này có thể chứng minh trong CV

- Phân tích sản phẩm: chuyển yêu cầu “luyện nghe” thành mô hình dữ liệu, chỉ số hành vi và feedback có thể hành động.
- Backend Java/Spring: REST API, validation, service layer, JPA, transaction, Spring Security, Actuator.
- Data modeling: thiết kế dữ liệu ở cấp lesson/sentence/word/event; migration có version; bảo toàn tính lịch sử của dữ liệu đánh giá.
- Algorithmic reasoning: heuristic ước lượng trình độ đa tín hiệu, weighting theo độ khó/recency/calibration, confidence thay vì đưa ra một con số tuyệt đối.
- Frontend: interaction phức tạp theo word-level, xử lý focus/keyboard/audio và responsive mobile bằng web platform APIs.
- Media/data pipeline: lưu audio theo segment thay vì file nhỏ, script xử lý/căn chỉnh nội dung.
- Security: password hashing, CSRF, session cookie, authorization, upload validation và secret management.
- DevOps: Docker cho local database, Windows service, GitHub Actions, SSH deploy, integrity verification, health check, rollback.
- Testing: unit test + integration test, CI gate trước khi phát hành.

## 9. Các giới hạn hiện tại cần nói rõ khi đánh giá

Đây là điểm thể hiện tư duy kỹ thuật trung thực, không phải thiếu sót cần che giấu:

- Ước lượng CEFR hiện là heuristic nội bộ. Nó cần thêm dữ liệu người dùng thực, bài chuẩn hóa và calibration thực nghiệm để có giá trị psychometric cao.
- Vì UI đang nhập từng từ theo vị trí cố định, insertion không thể được suy ra đáng tin cậy; connected-speech errors cần phoneme alignment hoặc nhãn thủ công.
- Nội dung hiện tập trung A1–B2 và corpus ban đầu còn nhỏ. Một production public quy mô lớn cần quy trình bản quyền nội dung, CDN/object storage, observability và backup/restore chính thức.
- CI/CD đã tự động deploy, nhưng nên bổ sung environment separation, secret rotation policy, monitoring/alerting, database backup định kỳ và giới hạn rate nếu mở cho nhiều người dùng.

## 10. Roadmap có giá trị portfolio cao

Ưu tiên các hạng mục vừa giải quyết bài toán thật vừa nâng cấp độ sâu kỹ thuật:

1. **Calibrate assessment bằng dữ liệu thật:** thiết kế bảng benchmark, so sánh predicted level với self-reported/placement test, đo sai số và điều chỉnh trọng số.
2. **Adaptive learning:** chọn bài tiếp theo theo uncertainty và information gain, thay vì rule-based recommendation hiện tại.
3. **Analytics dashboard cho người học/admin:** biểu đồ tiến bộ, replay/hint dependency theo thời gian, export dữ liệu ẩn danh.
4. **Observability và reliability:** structured logs, metrics, alerting, backup PostgreSQL đã kiểm thử restore.
5. **Mở rộng mô hình lỗi:** sentence-level free-form dictation hoặc alignment để phát hiện insertion; phoneme/connected-speech annotation cho chẩn đoán phát âm-nghe.
6. **Quy trình nội dung có bản quyền:** import pipeline có provenance, metadata và kiểm duyệt trước xuất bản.

## 11. Prompt gợi ý khi đưa tài liệu này cho ChatGPT

> Hãy đóng vai một Senior Software Engineer/Engineering Manager. Dựa trên hồ sơ dự án DictaLab dưới đây, hãy đánh giá khách quan năng lực của tác giả cho vị trí Backend Java hoặc Full-stack Junior/Middle. Chỉ ra: (1) điểm mạnh kỹ thuật đã có bằng chứng, (2) chỗ nào mới là MVP/heuristic và cần tránh phóng đại trong CV, (3) 3–5 cải tiến có tác động cao nhất để dự án thể hiện năng lực giải quyết bài toán quan trọng ở mức production, (4) cách viết 3 bullet CV theo chuẩn STAR, ngắn gọn và định lượng khi có thể. Không tự suy diễn tính năng không có trong hồ sơ.

---

## Tham chiếu mã nguồn nhanh

- Thuật toán assessment: `src/main/java/com/dictalab/service/ListeningAssessmentService.java`
- Luồng luyện và telemetry: `src/main/java/com/dictalab/service/PracticeService.java`
- Phân loại lỗi: `src/main/java/com/dictalab/service/ListeningErrorClassifier.java`
- Admin content: `src/main/java/com/dictalab/service/AdminContentService.java`
- Security: `src/main/java/com/dictalab/config/SecurityConfig.java`
- Database migrations: `src/main/resources/db/migration/`
- Frontend: `src/main/resources/static/`
- CI/CD: `.github/workflows/deploy.yml`
- Windows deploy/rollback: `scripts/update-windows-vps.ps1`
