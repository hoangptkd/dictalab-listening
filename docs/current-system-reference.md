# DictaLab — Tài liệu toàn bộ hệ thống hiện tại

Ngày cập nhật: **08/10/2026**, múi giờ Asia/Bangkok (UTC+7).

**Hiện trạng chính:** DictaLab là website luyện nghe tiếng Anh với Dictation, Listening, Hội thoại AI, Coach, phòng học 1–1 và quản trị nội dung. Mã nguồn hiện dùng JWT/Redis, hỗ trợ RabbitMQ và S3. Tài liệu triển khai ngày 04/10 ghi nhận JWT/Redis và S3 đã hoạt động trên VPS. Hàng đợi nền tạo audio/Coach cùng migration V26 đã có trong workspace, mặc định tắt; chưa có bằng chứng triển khai production cho phần này trong tài liệu đã đối chiếu. AI chạy qua Gemini, giọng đọc qua Cartesia; browser bridge cũ không thuộc luồng chạy hiện tại.

## 0. Cách dùng tài liệu này

Đây là bản mô tả độc lập để tải lên ChatGPT. Không cần tải những tài liệu được liên kết bên dưới để hiểu các chức năng chính. Các đường dẫn mã nguồn là bản đồ tra cứu khi có thêm repository, không phải nội dung bắt buộc phải đọc riêng.

Phạm vi xác minh: mã nguồn, cấu hình, migration, frontend, kiểm thử và tài liệu đo/triển khai trong workspace `English_dictition`, tên sản phẩm **DictaLab**. Đây là mô tả toàn bộ dự án đang mở, không phải danh mục mọi dự án khác của chủ dự án. Workspace có nhiều thay đổi chưa commit và file chưa được Git theo dõi; không lấy HEAD Git làm đại diện duy nhất cho phiên bản hiện tại. Đây không phải bản chụp trực tiếp toàn bộ database production.

Thông tin production dựa trên báo cáo triển khai/kiểm tra ngày 02–04/10/2026 và cấu hình trong repository. Lượt viết tài liệu ngày 08/10 chỉ đọc workspace, không kết nối VPS, gọi provider, chạy tải hoặc chạy lại bộ kiểm thử. Cấu hình môi trường có thể ghi đè mặc định. “Có trong mã nguồn”, “đã kiểm thử theo báo cáo” và “đã triển khai theo báo cáo” là ba trạng thái khác nhau; không coi mọi thay đổi local là đã chạy trên VPS.

Không chứa mật khẩu, API key, token đăng nhập, email người dùng thật, khóa SSH hoặc dữ liệu Chrome profile. Trước khi chia sẻ công khai, chủ dự án có thể bỏ địa chỉ IP production.

Quy tắc cho ChatGPT: chỉ coi tính năng được ghi là đã triển khai là hiện có. Không suy diễn từ tên module, ý tưởng roadmap hay tài liệu thiết kế cũ. Không coi điểm nội bộ là chứng nhận CEFR, kết quả benchmark là SLA, hay cấu hình CI là bằng chứng mọi workflow đã chạy thành công trên GitHub.

### 0.1. Bản tóm tắt để hiểu dự án nhanh

- **Bài toán:** giúp người học phân biệt nghe nhận ra từ, nghe hiểu ý và hiểu thông tin trong tình huống; lưu dấu vết lỗi để biết cần luyện gì tiếp.
- **Người dùng:** người học tiếng Anh, giao diện chủ yếu tiếng Việt; quản trị viên chuẩn bị và kiểm duyệt nội dung. Chưa xác minh số người dùng hoạt động, doanh thu hoặc mô hình thu phí.
- **Vòng học chính:** chọn bài phù hợp, nghe và trả lời, xem kết quả độc lập/hỗ trợ, ôn lỗi, chọn bài tiếp theo. Ba chế độ có dữ liệu và cách chấm riêng.
- **Nền tảng:** Java/Spring Boot modular monolith, PostgreSQL làm nguồn dữ liệu nghiệp vụ, frontend JavaScript thuần; Redis cho xác thực và rate limit tùy cấu hình; RabbitMQ cho công việc nền tùy cấu hình.
- **Điểm cần giữ khi mở rộng:** kết quả lần đầu không đổi khi sửa đáp án; lịch sử tham chiếu đúng phiên bản nội dung; quyền dữ liệu theo tài khoản; không tự gọi lại AI tính phí khi kết quả cũ chưa rõ.
- **Điểm chưa có:** chấm nói/phát âm, chương trình ôn cách quãng đầy đủ, CEFR chuẩn hóa, trạng thái Room dùng chung nhiều backend, bằng chứng sức tải dài của artifact mới nhất.
- **Cần chủ dự án bổ sung để chọn hướng phát triển:** mục tiêu sản phẩm hay portfolio, nhóm người học ưu tiên, phản hồi thực tế, ngân sách/thời gian, quy mô sử dụng và tiêu chí thành công. Tài liệu không tự suy đoán các thông tin này.

## 1. Sản phẩm và mục đích

**DictaLab** là website luyện nghe tiếng Anh, giao diện chủ yếu bằng tiếng Việt. Sản phẩm kết hợp ba chế độ luyện và một lớp phân tích kết quả:

1. **Dictation / Chép chính tả:** nghe từng câu, gõ từng từ, sửa lỗi, dùng gợi ý khi cần.
2. **Listening / Nghe hiểu:** nghe một đoạn hội thoại, tự viết những gì nhớ được, làm câu hỏi trắc nghiệm, có thể nghe lượt hai rồi so sánh kết quả.
3. **Conversation / Hội thoại AI:** nghe lời thoại tiếng Anh theo tình huống, gõ câu trả lời tiếng Việt, nhận phản hồi và luyện chuyển tiếp với thông tin mới.
4. **Coach và tiến độ:** xem hành vi làm bài, ước lượng năng lực nghe, phân tích lỗi và lưu lỗi cần ôn.

Chép chính tả và danh mục nội dung hỗ trợ A1, A2, B1, B2. Hội thoại AI hiện hỗ trợ A1, A2, B1. Không có chế độ đánh giá nói, chấm phát âm hoặc nhận diện câu trả lời bằng giọng nói trong hội thoại AI.

Website production được ghi nhận: `https://160.191.244.27:18443/`. Địa chỉ HTTP cổng 18081 trong tài liệu cũ không còn là địa chỉ truy cập chính; Caddy cấu hình chuyển hướng sang HTTPS.

Định hướng portfolio: thể hiện backend Java, mô hình dữ liệu, xử lý trạng thái, bảo mật tài khoản, giao tiếp thời gian thực, tích hợp AI, kiểm thử và vận hành. Giá trị kỹ thuật không chỉ nằm ở việc gọi một model để sinh câu hỏi.

## 2. Vai trò và phạm vi quyền

| Vai trò | Chức năng |
|---|---|
| Khách chưa đăng nhập | Mở trang, đăng ký/đăng nhập, yêu cầu đặt lại mật khẩu, xác nhận liên kết email; truy cập tài nguyên công khai được cho phép |
| USER | Luyện tập, xem dữ liệu của chính mình, dùng Coach, lưu lỗi, tham gia phòng, sửa tài khoản, gửi yêu cầu hỗ trợ |
| ADMIN | Có quyền người học; thêm quản trị bài, Listening, người dùng và hỗ trợ; truy cập nội dung cá nhân bị giới hạn |

Các trang shell và một số tài nguyên tĩnh có thể tải trước đăng nhập. Quyền đọc/ghi dữ liệu được bảo vệ ở API, không chỉ bằng việc ẩn nút trên giao diện. API học tập yêu cầu session đăng nhập. SessionId/UUID không thay thế kiểm tra ownership.

Admin email allowlist được cấu hình qua `DICTALAB_ADMIN_EMAILS`. Luồng xác minh email có kiểm tra allowlist để nâng quyền; tài khoản admin cũng có thể quản lý role theo cơ chế hiện có. Không có mật khẩu admin hard-code trong tài liệu này.

## 3. Giao diện, điều hướng và chức năng người học

### 3.1. Các màn hình chính

| Đường dẫn | Màn hình |
|---|---|
| `/`, `/index.html` | Shell ứng dụng |
| `/login`, `/register` | Đăng nhập, đăng ký |
| `/reset-password`, `/verify-email` | Khôi phục mật khẩu, xác nhận email/đổi email |
| `/catalog` | Danh mục bài chép chính tả theo level |
| `/practice/{sessionId}` | Câu đang luyện |
| `/history`, `/history/{sessionId}` | Lịch sử và kết quả chép chính tả |
| `/dashboard` | Tổng quan và tiến độ |
| `/listening`, `/listening/{sessionId}` | Nghe hiểu |
| `/conversation`, `/conversation/{sessionId}` | Hội thoại AI |
| `/rooms`, `/rooms/{roomId}`, `/rooms/join/{inviteCode}` | Phòng luyện cùng bạn và lời mời |
| `/account`, `/support` | Tài khoản, yêu cầu hỗ trợ |
| `/admin`, `/admin/lessons`, `/admin/lessons/new`, `/admin/lessons/{lessonId}` | Quản trị bài chép chính tả |
| `/admin/listening`, `/admin/users`, `/admin/support` | Quản trị các module còn lại |

Frontend dùng History API: `pushState`, `replaceState`, `popstate`; Spring MVC trả shell cho deep link. Không dùng React, Vue, Angular, Next.js hoặc router framework.

### 3.2. Danh mục và preview

- Chọn level A1–B2, xem tiêu đề, chủ đề, mô tả, thời lượng ước tính, số câu và tiến độ.
- Phân biệt bài đang làm, bài đã hoàn thành và việc luyện lại.
- Bắt đầu một bài đang có phiên solo `IN_PROGRESS` sẽ tiếp tục phiên đó thay vì luôn tạo phiên mới.
- Hiển thị từ preview có mốc audio; dữ liệu preview có phân loại tên riêng.
- Tên riêng được điền sẵn khi khớp chuỗi token trong câu; không bắt người học đoán tên và không đưa các ô đó vào mẫu số điểm Dictation.
- Từ preview chuẩn hóa được loại khỏi tín hiệu từ khi ước lượng năng lực nghe. Đây là quy tắc khác với việc loại riêng tên được điền sẵn khỏi điểm bài.
- Phát preview được ghi nhận như tiếp xúc audio để không coi audio đã nghe trước là hoàn toàn mới.
- Nội dung `BRITISH_COUNCIL_PERSONAL` không xuất hiện cho USER, không được dùng cho học chung hoặc tạo Listening công khai.

### 3.3. Nhập liệu và khả năng sử dụng

Mỗi từ có ô riêng; dấu câu được trình bày cạnh từ. Người học nhận trạng thái đúng/sai, có thể tiếp tục sửa từng ô. Có xử lý Enter, Backspace, di chuyển focus và tránh phát audio cướp focus đang nhập. Kiểm tra từ có chờ ngắn 240 ms trên luồng tự động kiểm tra để giảm request liên tiếp.

Giao diện responsive; có nhãn, vùng thông báo trạng thái/lỗi, điều hướng bàn phím, tab keyboard và CSS reduced motion. Đây là các hỗ trợ đã có, không phải tuyên bố đã đạt chứng nhận WCAG trên toàn website.

### 3.4. Sao chép câu đang hiện trong Dictation

Đây là hành vi mới nhất theo yêu cầu người dùng:

- Lấy **đúng chữ đang hiển thị** trong ô nhập hoặc phần từ đã hiện đáp án, theo thứ tự câu.
- Bỏ ô trống, không chèn `___`, không tự lấy đáp án ẩn hoặc đáp án cũ trong database.
- Giữ dấu câu gắn với từ hiển thị; ghép các từ còn lại bằng khoảng trắng.
- Nếu người học xóa chữ, bản nháp rỗng được giữ; render nền không được làm chữ cũ xuất hiện lại.
- Nội dung có thể chưa đầy đủ hoặc chưa đúng, vì đây là bản sao trạng thái hiện tại, không phải transcript gốc.
- Có preview textarea chỉ đọc, hỗ trợ sao chép toàn bộ hoặc phần đang chọn.
- Dùng Clipboard API trong secure context, có fallback `document.execCommand("copy")`.
- Chặn phản hồi clipboard cũ khi đổi route, phiên, câu hoặc nội dung preview; xóa selection khi đổi câu.

Hàm tham chiếu: `buildVisibleDictationSentence()` trong `src/main/resources/static/js/app.js`. Kiểm tra hồi quy: `src/test/js/dictation-copy.test.cjs`.

## 4. Dictation: luồng nghiệp vụ và dữ liệu

### 4.1. Vòng đời phiên

```text
Chọn bài active
  -> Tạo hoặc tiếp tục PracticeSession IN_PROGRESS
  -> Nghe câu hiện tại
  -> Kiểm tra từng từ / gợi ý / tự phân loại lỗi
  -> Tất cả ô đã đúng hoặc đã mở đáp án
  -> Nếu đã reveal: nghe lại hoàn tất câu
  -> Hoàn thành câu
  -> Câu tiếp theo hoặc PracticeSession COMPLETED
  -> Kết quả + transcript review + assessment + Coach
```

Backend yêu cầu câu gửi lên đúng vị trí hiện tại, session thuộc người dùng và còn `IN_PROGRESS`. Không cho gửi tùy ý một câu khác để nhảy tiến độ. Hoàn thành câu có lưu thời gian thực hành do frontend gửi.

Để kiểm tra từ hoặc dùng hint, backend yêu cầu đã có lần nghe. Replay ghi nhận qua event; `STARTED` tăng số lượt và telemetry tốc độ khi câu chưa sẵn sàng hoàn thành. Event hoàn tất sau reveal mở điều kiện tiếp tục. Không coi việc gửi replay event là bằng chứng chống gian lận rằng người thật đã nghe.

### 4.2. Gợi ý và kết quả từ

Ba loại hỗ trợ: `FIRST_LETTER`, `REVEAL_WORD`, `REVEAL_SENTENCE`. Các thao tác lặp có kiểm soát việc tăng bộ đếm. Reveal không biến từ đó thành trả lời đúng cuối cùng; việc resolve một ô để chuyển câu khác với đạt `finalCorrect`.

Đáp án được so sánh sau chuẩn hóa Unicode và chữ thường, không dùng AI để xác định đúng/sai từng từ. Người học có thể tự gắn nhãn cho từ sai lần đầu:

- `KNOWN_BUT_MISSED`: biết từ nhưng nghe lỡ.
- `UNKNOWN`: chưa biết từ.

Từng lần kiểm tra lưu đáp án thô, chuẩn hóa, lần thử, đúng/sai, thời gian phản hồi và loại lỗi suy luận. First-attempt accuracy không bị nâng lên sau khi sửa đúng. Hỗ trợ của lần đầu bao gồm hint/reveal và bối cảnh nghe lại hoặc nghe chậm.

### 4.3. Review cuối bài

Hiển thị điểm, độ đúng lần đầu/lần cuối, replay, hint/reveal, thời gian, lỗi đã biết/chưa biết. Transcript review có bảy nhóm hành vi: đúng lần đầu, đúng lần hai, đúng lần 3–6, đúng từ lần 7, dùng chữ đầu, reveal từ và reveal câu.

Review có audio từng câu, mốc bắt đầu/kết thúc, đáp án từng lần, WPM khi đủ mốc audio, số người nói, accent và độ khó nội dung. Bài ELLLO có nguồn tham chiếu về trang bài gốc. Điểm của phiên hoàn thành lưu trong database; assessment trả kèm có thể thay đổi khi có thêm bằng chứng mới.

## 5. Các thuật toán chép chính tả và đánh giá

### 5.1. Tokenizer và chuẩn hóa

`TranscriptTokenizer` dùng regex:

```regex
[\p{L}\p{N}]+(?:['’\-][\p{L}\p{N}]+)*
```

Từ chứa dấu nháy hoặc gạch nối, ví dụ `don't`, `well-known`, là một token. Token giữ phần dấu câu trước/sau riêng để render. Độ dài/mask xử lý code point và giữ hình dạng dấu nháy/gạch nối.

Chuẩn hóa đáp án: trim, Unicode NFKC, đổi nháy cong sang nháy thẳng, lowercase với `Locale.ROOT`. Từ chức năng được nhận diện bằng một tập từ cố định. Không có parser ngữ pháp đầy đủ hay mô hình NLP huấn luyện riêng trong bộ chấm Dictation.

### 5.2. Điểm bài Dictation

Ký hiệu `N` là số từ có thể chấm, **không gồm ô tên riêng được điền sẵn**. Với `N > 0`:

```text
firstAccuracy = 100 * firstCorrect / N
finalAccuracy = 100 * finalCorrect / N
extraReplays = max(0, totalReplays - sentenceCount)
penalty = 2 * firstLetterHints
        + 8 * wordReveals
        + 20 * sentenceReveals
        + 1.5 * extraReplays
independence = clamp(100 - penalty, 0, 100)
overallScore = clamp(0.65 * firstAccuracy
                   + 0.25 * finalAccuracy
                   + 0.10 * independence, 0, 100)
```

Các giá trị được làm tròn một chữ số thập phân. Trọng số và penalty trong `application.yml` có thể cấu hình; không phải tham số được học bằng ML. Reveal câu và reveal từ có bộ đếm riêng.

Nguồn: `ScoringService`, `PracticeService.finishSession()`.

### 5.3. Phân loại lỗi tự động

`ListeningErrorClassifier` áp dụng các nhánh theo thứ tự:

1. Đáp án trống: `OMISSION`.
2. Khớp từ ở vị trí khác trong câu: `WORD_ORDER`.
3. Gốc từ đơn giản khớp sau bỏ đuôi `s`, `ed`, `ing`, với giới hạn lệch độ dài: `WORD_FORM`.
4. Levenshtein trong ngưỡng `max(1, min(2, expected.length()/4))`: `SPELLING`.
5. Còn lại: `SUBSTITUTION`.

Levenshtein dùng hai hàng cuộn, thời gian O(m*n), bộ nhớ O(n) với hai chuỗi dài m và n. `INSERTION` tồn tại trong model nhưng các ô cố định chưa đủ để suy luận chèn từ đáng tin cậy.

Đây là heuristic trên chữ, **không phân tích âm thanh, phoneme hoặc phát âm**. Các nhãn không chứng minh nguyên nhân tâm lý/ngôn ngữ của lỗi.

### 5.4. Ước lượng năng lực nghe từ Dictation

`ListeningAssessmentService` chỉ lấy tối đa 10 phiên solo hoàn thành, first exposure, mỗi lesson một bằng chứng; ưu tiên mới nhất. Không dùng bài làm lại hoặc phiên học chung để kết luận năng lực nghe độc lập. Mức tối thiểu mặc định để bớt provisional là 5 bài.

Tín hiệu từ loại bỏ những từ nằm trong preview chuẩn hóa. Các đại lượng `first`, `unassisted`, `final` là phần trăm trên tập từ dùng để đánh giá, không nhất thiết bằng trực tiếp chỉ số điểm bài lưu sẵn.

```text
hintRate = hintedWords / totalWords
revealRate = revealedWords / totalWords
slowRate = slowPlaybackEvents / playbackEvents
replayRate = clamp(extraReplays / sentenceCount / 2, 0, 1)

independence = 100 * (1 - clamp(0.35*hintRate + 0.25*revealRate
                              + 0.20*slowRate + 0.20*replayRate, 0, 1))
performance = 0.50*unassisted + 0.15*first + 0.20*final
            + 0.15*independence

averageDifficulty = (vocabularyDifficulty + grammarDifficulty
                     + sentenceDifficulty) / 3
lessonPosition = 0.75*levelOrdinal + 0.25*(averageDifficulty - 1)
speedAdjustment = clamp((WPM - 120)/250, -0.15, 0.25)
speakerAdjustment = clamp((speakerCount - 1)*0.06, 0, 0.18)
performanceAdjustment = clamp((performance - 70)/35, -1, 0.75)
position = clamp(lessonPosition + speedAdjustment
                 + speakerAdjustment + performanceAdjustment, 0, 3)

recency = 1 - min(0.35, indexOfLesson*0.05)
calibration = 1.0 nếu calibrated, ngược lại 0.8
weight = max(1, sqrt(totalWords)) * recency * calibration
estimatedPosition = sum(position*weight) / sum(weight)
estimatedLevel = level tại round(estimatedPosition), giới hạn A1..B2
abilityScore = clamp((estimatedPosition + 1)*25, 0, 100)
```

`levelOrdinal`: A1=0, A2=1, B1=2, B2=3. Không đủ mốc audio thì speed adjustment bằng 0. Trường hợp chưa có bằng chứng dùng trạng thái assessment rỗng, không áp dụng công thức như đã có dữ liệu.

Độ tin cậy:

```text
confidence = 100 * (
  0.40*min(lessonCount/5, 1)
  + 0.15*min(lessonCount/10, 1)
  + 0.15*min(totalWords/120, 1)
  + 0.10*firstAttemptEventCoverage
  + 0.10*distinctLevelCoverage
  + 0.10*calibrationCoverage)
```

Coverage được chặn trong 0..1. Distinct level coverage là số level khác nhau chia `min(3, lessonCount)`. Dưới 5 bài, confidence tối đa 49. Nếu chưa mọi bài đều calibrated, tối đa `65 + 20*calibrationCoverage`. Band: dưới 50 LOW, dưới 75 MEDIUM, còn lại HIGH. Provisional nếu chưa đủ 5 bài hoặc có bài chưa calibrated.

Có thống kê độ đúng từ chức năng/từ nội dung, thời gian phản hồi và lỗi. **Accent profile được lưu/hiển thị nhưng không trực tiếp tham gia công thức position. Thời gian phản hồi cũng không trực tiếp điều chỉnh position.** Không được liệt kê mọi telemetry như một trọng số đang dùng.

Thuật toán là đánh giá nội bộ dựa trên quy tắc; chưa có calibration psychometric hoặc bằng chứng tương đương IELTS/CEFR chuẩn hóa.

### 5.5. Recommendation

- Chưa có bài solo hoàn thành: chọn bài A1 active đầu tiên.
- Lấy level của phiên solo hoàn thành mới nhất.
- Trung bình first accuracy của tối đa 3 phiên solo gần nhất cùng level.
- Từ 85% trở lên: tăng một level, tối đa B2.
- Dưới 55%: giảm một level, tối thiểu A1.
- Còn lại giữ level.
- Ưu tiên bài active chưa hoàn thành theo `displayOrder`; hết bài mới thì dùng bài active đầu tiên.

Không dùng machine learning, bandit hoặc information gain để chọn bài.

## 6. Coach và ngân hàng lỗi

Coach hoạt động trên kết quả Dictation **đã hoàn thành và thuộc người dùng**. Có hai lớp:

1. `CoachAnalysis` tạo phân tích cơ sở bằng quy tắc.
2. Nếu bật Coach và có khóa Gemini, AI diễn giải/xếp chọn bằng chứng đã có. Lỗi AI không ngăn người học hoàn thành hoặc xem review.

### 6.1. Xếp ưu tiên lỗi

Các nhóm và điểm cơ sở theo nhánh: tên riêng 5, chính tả 10, âm cuối 80, contraction 85, số 80, weak forms 75, known-but-missed 90, vocabulary 45, word recognition 50. Với nhóm không phải spelling/proper name, lỗi lặp tăng tối đa 30 điểm; từ tự gắn known-but-missed tăng thêm 15.

Lỗi âm cuối/weak forms là **giả thuyết cần nghe lại**, không phải chẩn đoán âm học. Mã cố định lời giải thích thận trọng cho hai nhóm này ngay cả khi AI diễn đạt mạnh hơn.

Review chọn tối đa 12 lỗi; ưu tiên lỗi nghe hơn chính tả, cố gắng phân bố tối đa hai lỗi mỗi câu trước khi lấp chỗ còn lại. Phân tích có tối đa 3 điểm yếu và 3 hành động. AI chỉ được dùng ID bằng chứng hợp lệ, không tự tạo từ sai mới; gửi tối đa 40 candidate vào bước diễn giải.

### 6.2. Tiến bộ và lưu trữ

So sánh với tối đa 5 phiên solo first exposure trước đó cùng level. Cần ít nhất 2 phiên trước để so sánh. Chênh first accuracy từ +3 điểm phần trăm là UP, từ -3 là DOWN, còn lại STEADY. Redo/shared trả NOT_COMPARABLE. Độ khó từng bài có thể khác nhau nên đây không phải phép đo kiểm soát hoàn hảo.

Báo cáo AI hợp lệ lưu JSON trong `dictation_coach_reports`, một báo cáo mỗi phiên. Phân tích cơ sở vẫn dùng được khi AI tắt, đang bận hoặc lỗi. Các trạng thái nguồn gồm RULES, BUSY, UNAVAILABLE, AI; khi bật AI queue có thêm QUEUED, RETRY_REQUIRED và EXHAUSTED. Công việc nền được mô tả ở mục 13.3.

Ngân hàng lỗi lưu snapshot của item; thêm trùng user/session/item là idempotent nhờ unique constraint. API đọc tối đa 100 item mới nhất. Chưa có spaced repetition, lịch ôn hoặc thuật toán SM-2.

## 7. Listening Comprehension — Nghe hiểu

### 7.1. Nội dung và trải nghiệm

Module này **đã triển khai**, không phải chỉ tồn tại trong tài liệu thiết kế. Bài nghe dùng audio hội thoại và các câu đã căn mốc của lesson công khai. Catalog trước hoàn thành dùng tiêu đề trung tính theo level/id để giảm lộ chủ đề.

Người học nghe, viết recall tối đa 6.000 ký tự, rồi làm MCQ. Có lưu nháp recall/câu trả lời trên server, báo cáo kết quả, lịch sử riêng, review transcript từng đoạn và ghi chú câu khó.

### 7.2. Máy trạng thái thực tế

```text
READY -> PLAYING -> RECALL -> QUESTIONS -> PASS1_RESULT
                                             |
                    finish-after-pass-1 ------+-> COMPLETE (ONE_PASS)
                    second-pass -------------+-> READY lượt 2
Lượt 2: READY -> PLAYING -> RECALL -> QUESTIONS -> COMPLETE (TWO_PASS)
Abandon -> ABANDONED
```

Lưu ý để không nhầm với thiết kế ban đầu:

- Cả đầu vào ONE_PASS và TWO_PASS đều có thể tới PASS1_RESULT sau nộp lần một.
- Tại đó người học xem điểm số lượt một và quyết định kết thúc hoặc nghe lượt hai.
- Recall lượt hai được khởi tạo từ recall lượt một, **không bắt đầu trống**.
- PASS1_RESULT chưa công khai toàn bộ đáp án facts/transcript; review đầy đủ sau COMPLETE.
- Options được xáo trộn một lần bằng `Collections.shuffle` và lưu order JSON trong session, không đảo lại mỗi lần tải.

### 7.3. Ràng buộc playback và exposure

Backend cấp playback UUID token. Hoàn tất nghe yêu cầu session đang PLAYING, token đúng và thời gian đã trôi tối thiểu gần thời lượng audio (`duration - 1 giây`). Đây là kiểm tra trạng thái/thời gian, không chứng minh người học thực sự bật loa hoặc chú ý nghe.

First exposure dựa trên câu trả lời đã nghe trước và dấu vết audio URL trong Dictation, Listening, preview exposure. Chưa dùng SHA-256 nội dung audio để xác định hai URL khác nhau nhưng cùng âm thanh. Copy bài giữ cùng audio URL giúp bảo toàn một phần nhận diện này; upload lại cùng âm thanh thành file khác có thể không được nhận ra.

### 7.4. Tạo và kiểm duyệt unit

Admin có thể tạo draft bằng Gemini hoặc nhập nội dung thủ công. Tạo AI cần lesson active, không phải personal, audio có ít nhất 5 câu có mốc, thời lượng hữu hạn 0..3600 giây và kiểm tra mốc cuối phù hợp thời lượng.

Nội dung được validate:

- 4–12 facts, ID không trùng.
- 5–8 câu hỏi, ID không trùng.
- Mỗi câu có 3–4 option không rỗng, không trùng, index đáp án đúng trong phạm vi.
- Type hợp lệ: MAIN_IDEA, DETAIL, INFERENCE, SPEAKER_INTENT.
- Ít nhất 1 câu MAIN_IDEA và 2 câu DETAIL.

Schema hợp lệ không bảo đảm đúng ngôn ngữ hoặc câu hỏi có chất lượng sư phạm. Admin phải đọc, sửa, nghe đối chiếu trước publish. Có copy revision, liên kết `supersedes_unit_id`, retire phiên bản cũ; session tham chiếu unit cụ thể để không đổi đáp án của phiên đã học.

### 7.5. Chấm điểm

MCQ: backend đổi index hiển thị về index gốc bằng option order đã lưu. Đúng được 1, sai/không trả lời 0; điểm `100 * correct/total`, làm tròn một chữ số.

Recall: AI gắn nhãn **từng fact** CAPTURED, PARTIAL, MISSED, INCORRECT. Điểm do backend tính, không tin tổng AI tự trả:

```text
CAPTURED = 1
PARTIAL = 0.5
MISSED = 0
INCORRECT = 0
recallScore = 100 * sum(factValue) / factCount
```

ID facts phải đầy đủ, không trùng hoặc bịa thêm. Recall trống được chấm MISSED bằng quy tắc, không cần gọi AI. Lưu model và rubric `RECALL_V1` cùng kết quả để biết nguồn chấm. Chấm thủ công qua support có model MANUAL.

### 7.6. Worker chấm và retry

- Dispatcher mặc định chạy mỗi 1 giây; ngân sách worker mặc định 2, có thể cấu hình. Khi tắt Listening queue, dùng executor có giới hạn và trạng thái PostgreSQL; khi bật, phân phối qua RabbitMQ.
- `listening_passes` giữ PENDING cùng đáp án/retry trong database. Message chỉ mang session/pass/retry revision/attempt; publisher confirm và cập nhật có điều kiện cho phép gửi lại khi broker lỗi mà không mất bài nộp.
- SQL claim chuyển sang RUNNING, tăng attempts và cấp lease. Commit kết quả yêu cầu đúng lease và còn hạn, chặn worker cũ ghi đè.
- RUNNING quá 120 giây với recall không rỗng chuyển FAILED/OUTCOME_UNKNOWN, cần retry có chủ ý để tránh tự gọi AI tính phí lần nữa. Recall rỗng có thể phục hồi tự động vì không gọi provider.
- Lỗi provider có nhánh retry được phép (ProviderGuard.Unavailable), tối đa 3 attempts, chờ ít nhất 30/60 giây với jitter và tôn trọng Retry-After. Lỗi khác không được tự coi là an toàn để gọi lại.
- Sau FAILED, người học có tối đa 2 lần retry thủ công, reset attempts cho lượt retry. Đây là ngân sách riêng của Listening, khác giới hạn 3 lần gọi tổng của job audio/Coach.
- Trạng thái grading tách khỏi trạng thái hoàn thành bài, nên lỗi AI không phải mất recall đã nộp.

Review cho nghe đoạn câu, gắn `NOT_HEARD` hoặc `HEARD_NOT_UNDERSTOOD`, ghi chú tối đa 500 ký tự. History Listening có API phân trang DB `/history/page`, mặc định 10 mục, size 1..100. API legacy `/history` chỉ trả 100 mục đầu; không còn tải toàn bộ lịch sử.

Không coi module hiện tại là kỳ thi chống gian lận hoặc implementation đầy đủ của mọi điều trong `docs/listening-comprehension-design.md`. Những ý như audio identity bằng hash, protocol version, mọi submission idempotent hoặc phân biệt exposure UNKNOWN chưa được triển khai đầy đủ như thiết kế đó.

## 8. Conversation — Hội thoại AI

### 8.1. Mục đích và cấu trúc phiên

Có 5 scenario: `cafe` (quán/gọi món), `directions` (hỏi đường), `plans` (lịch hẹn), `tickets` (mua vé), `delivery` (nhận hàng). Chọn A1/A2/B1.

Mỗi phiên có 4 nhiệm vụ: 3 nhiệm vụ chính và nhiệm vụ thứ 4 kiểm tra chuyển tiếp bằng thông tin mới. Mỗi nhiệm vụ tối đa 3 câu trả lời gõ tiếng Việt. Giao diện giới thiệu khoảng 5–8 phút là ước lượng trải nghiệm, không phải giới hạn thời gian cưỡng chế.

Đây là luyện nghe hiểu theo hội thoại, không phải chat tự do vô hạn. Không có STT hoặc chấm pronunciation. Catalog lấy 12 phiên mới nhất. Giới hạn tạo tối đa 20 phiên/user trong 24 giờ lùi, không nhất thiết theo ngày lịch.

### 8.2. Nhà cung cấp theo từng công việc

| Công việc | Nguồn đang dùng trong mã | Cấu hình mặc định |
|---|---|---|
| Sinh lời thoại, task, câu hỏi, dịch, facts | Gemini | `gemini-3.5-flash-lite` qua `DICTALAB_CONVERSATION_MODEL` |
| Chấm câu trả lời, phản hồi và làm rõ | Gemini API | `GEMINI_MODEL`, mặc định `gemini-3.5-flash-lite` |
| Tạo giọng tiếng Anh | Cartesia TTS | `sonic-3.6`, WAV PCM16 24 kHz |

`GEMINI_MODEL` dùng cho Listening, Coach và chấm/làm rõ hội thoại, mặc định `gemini-3.5-flash-lite`; không phải biến chọn model sinh hội thoại. Hội thoại cần bật `DICTALAB_CONVERSATION_ENABLED` và đủ khóa Gemini/Cartesia; không phụ thuộc công tắc Coach hoặc browser bridge.

### 8.3. JSON và bằng chứng

Prompt mỗi nhiệm vụ gồm `taskVi`, `questionVi`, `utteranceEn`, `translationVi`, `skill`, `facts`. Validator kiểm tra đúng shape, giới hạn trường, câu tiếng Anh 20–65 từ theo tách khoảng trắng và tối đa 1.200 ký tự. Có 2–3 facts, A1 đúng 2; ID f1/f2/f3, meanings/evidence không trùng sau chuẩn hóa. `evidenceEn` phải là chuỗi con thật trong utterance.

Nhiệm vụ chuyển tiếp kiểm tra trùng equality sau chuẩn hóa của text/evidence với facts trước. Đây **không phải semantic similarity**; đổi cách diễn đạt có thể vượt kiểm tra trùng dù ý giống nhau.

Prompt chỉ yêu cầu chấm những ý câu hỏi thực sự hỏi, không chấm mọi chi tiết phụ tình cờ có trong đoạn. Ngoài validation, mã hiện có một bước AI review kiểm tra ý nghĩa nhiệm vụ/câu nghe; lỗi review làm tác vụ không được chấp nhận. Có tối đa **một lần repair có giới hạn** trong vòng sinh prompt. Đây chưa phải bộ đánh giá sư phạm độc lập trên tập người học thật. Không tự retry network vô hạn và không fallback sinh câu sang ChatGPT Web.

### 8.4. Chấm và hỗ trợ

Grade fact có CAPTURED, PARTIAL, MISSED, INCORRECT, UNCERTAIN. Với nhãn cần evidence, `evidenceVi` phải là chuỗi con từ câu trả lời thực của người học, không phải model tự viết. Fact ID phải đầy đủ. Thiếu ý/không chắc cần câu hỏi làm rõ trung tính, không lộ đáp án.

Người học có thể nghe lại, nghe chậm 0,8×, mở transcript, mở bản dịch, nhờ AI diễn đạt rõ hơn. Các hỗ trợ được ghi trong kết quả. Backend không trả answer facts ra browser; utterance/translation chỉ đưa vào view khi đã mở hỗ trợ. Audio cần được nghe vẫn được phục vụ qua endpoint riêng có ownership.

Kết thúc có số facts hiểu độc lập, facts hiểu với hỗ trợ, tổng facts, tối đa 3 ý đã hiểu, tối đa 3 kỹ năng cần luyện, nhận xét transfer và hành động tiếp theo. Không phải điểm CEFR hay điểm phát âm.

### 8.5. Chịu lỗi và concurrency

Trạng thái lưu JSON trong PostgreSQL; câu trả lời được lưu trước khi chờ AI. `requestId` hỗ trợ nhận diện lại câu trả lời pending để retry không tạo lần trả lời mới trong luồng tương ứng. `expectedTurn` chặn thao tác của màn hình cũ.

Claim/commit DB ngắn; cuộc gọi AI thực hiện ngoài transaction của Conversation. Lease có ID, version và `busy_until` 210 giây để ngăn hai mutation đồng thời cùng phiên và cho thu hồi khi request bỏ dở. Không được suy luận rằng mọi module khác cũng có cấu trúc transaction tương tự.

Sau khi hoàn tất một task, hệ thống prefetch prompt tiếp theo. Prefetch lỗi không xóa grade vừa lưu; dữ liệu cache không hợp lệ sẽ sinh lại. Các request API độc lập, gửi ngữ cảnh từ database, không tạo/đóng tab hoặc session nhà cung cấp.

Khi AI queue tắt, audio dùng 2 worker trong process, hàng đợi tối đa 8 công việc, chống tạo trùng bằng future và khóa theo session/turn/variant. Khi bật, job audio lưu bền trong PostgreSQL và phân phối qua RabbitMQ, HTTP trả trạng thái chờ để frontend polling (mục 13.3). Cartesia mặc định giới hạn 2 request đồng thời, timeout 45 giây, body tối đa 3.000.000 byte và kiểm tra RIFF/WAVE. WAV lưu BYTEA, chỉ chủ phiên đọc được; xóa cùng session/account theo FK cascade.

Mã hiện có cleanup audio mỗi giờ, mặc định chọn phiên tạo quá 30 ngày, tối đa 100 phiên mỗi batch và tránh phiên đang giữ lease. Xóa WAV có thể tái tạo, giữ lịch sử học; phát lại có thể gọi TTS lần nữa. Đây là retention theo tuổi phiên, không phải theo lần truy cập cuối.

## 9. Dashboard và lịch sử

Dashboard là tổng hợp **Dictation solo**, không trộn mọi điểm Listening/Conversation vào một điểm chung:

- Tổng số phiên hoàn thành, phút luyện và các trung bình điểm.
- Thống kê theo level, loại lỗi known/unknown.
- Biểu đồ 8 phiên solo mới nhất theo thứ tự thời gian.
- Recommendation và assessment hiện tại.

Lifetime totals dùng SQL aggregate theo level, không tải toàn bộ entity của lịch sử vào Java. Trung bình tính trên số phiên, không lấy trung bình không trọng số giữa các level. Ngày hiển thị trend dùng timezone hệ thống JVM; database/JDBC dùng UTC, cần chú ý khi thay timezone máy chủ.

History Dictation lọc trước phân trang tại DB, mặc định 10 mục, size 1..100, page 0..100000. Thứ tự `completedAt DESC, id DESC` có tie-break. Response gồm content và tổng số trang/mục; view detail chỉ của chủ sở hữu và phiên hoàn thành. Frontend giữ filter/page trong URL, xử lý deep link, retry, stale response và focus khi chuyển trang.

## 10. Luyện cùng bạn: phòng 1–1 và WebRTC

### 10.1. Trải nghiệm

Host tạo phòng, sao chép invite link. Guest mở link và xin tham gia; host duyệt hoặc từ chối. Sau khi active, hai người bật/tắt microphone, gọi thoại, cùng luyện Dictation. Host chọn lesson và chuyển câu khi cả hai đã sẵn sàng. Mỗi người vẫn có PracticeSession và điểm riêng, gắn `room_id`.

Hai người nhìn được bản nháp của nhau; draft có sequence tăng để bỏ update cũ. Trạng thái ready được xác minh từ điều kiện hoàn thành câu ở backend. Shared session không dùng làm first-exposure assessment hoặc recommendation solo.

Cập nhật 03/10/2026: nghe câu và lượt nghe dùng chung trong phòng. Mỗi người bấm **Bật nghe chung** một lần để mở AudioContext và tải file audio. Khi cả hai sẵn sàng, chỉ host được phát/chọn tốc độ 0,75×, 1× hoặc 1,25×; hai browser phát cùng đoạn audio từ file bài học, không truyền audio bài qua microphone. Điểm và câu trả lời vẫn riêng. Bộ đếm hiển thị là số lệnh phát được máy chủ chấp nhận, không chứng minh cả hai đã nghe thành công; sự kiện STARTED/COMPLETED vẫn được ghi riêng vào PracticeSession. Chuyển câu đặt lượt nghe về 0.

Server phát lệnh với sequence tăng, version trạng thái, position, speed và thời điểm bắt đầu sau 750 ms. Browser ước lượng lệch đồng hồ bằng thời gian giữa request/response polling; bỏ lệnh cũ/trùng và lệnh đến muộn hơn 1,5 giây. Khi có lỗi, yêu cầu bật lại/phát lại thay vì coi như đã nghe trọn câu. Chỉ chuyển câu/phát lượt mới sau khi cả hai kết thúc; disconnect giải phóng người mất kết nối. Watchdog 5 phút giải phóng lượt bị kẹt do tab đóng băng, yêu cầu cả hai bật nghe lại. Đây là đồng bộ theo lịch phát, không cam kết độ chính xác mẫu âm thanh giữa hai thiết bị.

### 10.2. Dữ liệu và khóa

- RoomRegistry nằm **trong bộ nhớ một JVM**, không có bảng room PostgreSQL.
- Tối đa 200 phòng mở; một user chỉ ở một phòng, kể cả đang pending.
- Invite dùng SecureRandom; TTL 24 giờ.
- Reconnect window 60 giây; cleanup mỗi 60 giây nên giải phóng không nhất thiết ngay đúng giây 60.
- Khóa riêng mỗi room; khóa membership index được giữ ngắn để nguyên tử hóa join/create/leave/cleanup.
- Thứ tự khóa: room lock trước, membership lock sau; creation chỉ cần membership lock.
- Restart backend làm mất phòng đang mở. Chưa hỗ trợ nhiều backend instance chia sẻ registry.
- Host rời đóng phòng; guest rời hoặc disconnect quá hạn được xử lý theo trạng thái phòng/bài đang học.

### 10.3. Giao thức và bảo vệ

REST quản lý vòng đời; WebSocket `/ws/room/{roomId}` làm signaling và draft. WebRTC truyền audio giữa hai browser khi kết nối trực tiếp được; TURN có thể relay khi cấu hình. Backend không lưu bản ghi audio microphone trong luồng này.

Host là offerer, guest là answerer. Message whitelist gồm `offer`, `answer`, `ice-candidate`, `ready`, `mic-state`, `renegotiate`, `draft`, `call-ready`, `audio-ready`, `lesson-play`, `lesson-played`; có ping/pong và thông báo peer joined/disconnected. Server xác minh ba lệnh nghe chung và broadcast `lesson-audio` cho cả hai, không relay tùy ý. Chỉ relay giữa thành viên được duyệt trong cùng phòng ACTIVE. Browser dùng polling trạng thái 2,5 giây và WebSocket có reconnect.

Giới hạn message 32 KB, 20 message/giây/kết nối. Dù comment gọi là token bucket, implementation là **bộ đếm cửa sổ một giây**, không phải token bucket tích lũy. Draft tối đa 100 từ, mỗi giá trị tối đa 80 ký tự. Một kết nối mới của cùng user trong room thay kết nối cũ.

WebSocket xác thực bằng JWT access cookie ở handshake và allowed origin patterns. Heartbeat cùng lịch kiểm tra 20 giây kiểm tra token hết hạn/thu hồi và trạng thái tài khoản, kể cả socket im lặng. Khi access hết hạn, frontend phục hồi xác thực rồi reconnect; không đưa token vào query string. Không log SDP/ICE đầy đủ.

### 10.4. ICE và chỉ báo đang nói

STUN công khai: Google cổng 19302. TURN tùy chọn qua `DICTALAB_TURN_URL` và `DICTALAB_TURN_SECRET`; username `expiry:userId`, credential `Base64(HMAC-SHA1(secret, username))`, TTL mặc định 3600 giây. Chưa cấu hình TURN có thể làm cuộc gọi thất bại trên một số NAT/firewall.

TURN Windows bổ sung trong `scripts/turn/`: Go và Pion TURN v5.1.2, dịch vụ NSSM `DictaLabTURN` dưới LocalService, tự chạy khi Windows khởi động. Cổng vào UDP/TCP 3478, relay IPv4 UDP 49160–49959; `DICTALAB_TURN_URL` nhận danh sách URL phân cách dấu phẩy. Xác thực TURN REST bắt buộc, secret lưu trên VPS trong thư mục hạn quyền, TTL tối đa 3600 giây; chặn peer nội bộ/loopback/link-local/reserved và TCP relay allocation. Quota mềm 4 allocation/user, 400 tổng; cổng relay tạo trần cứng 800 UDP allocation. Chưa có TURN/TLS 443, giới hạn băng thông riêng hoặc SLA cuộc gọi. Xem `docs/shared-audio-turn-2026-10-03.md` để biết kiểm thử và vận hành.

Frontend chỉ báo đang nói dùng Web Audio Analyser, fftSize 512, lấy time-domain mỗi 80 ms. Tính RMS `sqrt(mean(((sample-128)/128)^2))`, ngưỡng 0,055. Bật chỉ báo sau 2 mẫu lớn; tắt sau 6 mẫu yên để giảm nhấp nháy. Đây là phát hiện năng lượng đơn giản, không VAD model hoặc STT; âm nền có thể gây nhận nhầm.

## 11. Tài khoản, email và hỗ trợ

### 11.1. Tài khoản

- Đăng ký email/display name/password; email trim và lowercase; tự đăng nhập sau đăng ký.
- Đăng nhập, đăng xuất, xem tài khoản hiện tại.
- Sửa display name, đổi mật khẩu bằng mật khẩu hiện tại.
- Đăng xuất toàn bộ phiên qua tăng `auth_version`.
- Gửi xác minh email, đổi email có mật khẩu xác nhận và link tới email mới.
- Quên mật khẩu qua email; không trả công khai việc email có tồn tại trong luồng gửi thành công thông thường.

Token email: 32 byte SecureRandom thành hex; bảng `account_tokens` lưu SHA-256 hash. Hết hạn 30 phút, đúng purpose, dùng một lần bằng conditional update; vô hiệu các token còn lại cùng user/purpose sau consume. Giới hạn 3 yêu cầu token/user/purpose/giờ. Gửi email cần SMTP và mail-from; chưa cấu hình trả thông báo không khả dụng. Token và job email được ghi cùng transaction vào `email_outbox`, worker gửi ngoài transaction. Nội dung thư chứa liên kết token được giữ trong outbox trong lúc chờ gửi, rồi xóa khi đã gửi/hết hạn; không coi hash token là biện pháp duy nhất bảo vệ liên kết này. Worker có lease và tối đa 5 attempts. SMTP có thể gửi trùng nếu crash sau khi nhà cung cấp nhận thư nhưng trước khi commit trạng thái; không có bảo đảm exactly-once.

`AccountStateFilter` đối chiếu enabled, auth_version, email và role với principal mỗi HTTP request; không khớp thì xóa security context. Xác thực dùng JWT/Redis, không tạo/đọc HttpSession. Đổi mật khẩu/quyền/thu hồi phiên vô hiệu quyền truy cập cũ theo domain rules. Cơ chế token và phục hồi frontend xem mục 17.1.

### 11.2. Support

USER gửi ticket AUDIO, TRANSCRIPT, SCORE, ACCOUNT_DELETE, OTHER. Subject tối đa 160, mô tả 3000 ký tự, tối đa 5 ticket/giờ. Reference tùy chọn LISTENING/DICTATION phải đủ cặp type/id và thuộc người gửi. Xem ticket của mình theo trang 20 mục.

ADMIN lọc ticket, xem chi tiết và nội dung liên quan, cập nhật OPEN/IN_PROGRESS/RESOLVED, note tối đa 1000 ký tự. Response cũ được lưu event thay vì âm thầm mất lịch sử. Có xử lý xung đột revision.

Ticket SCORE của Listening cho admin xem recall và facts rồi chấm thủ công theo rubric. Ticket ACCOUNT_DELETE cho admin xóa tài khoản thường sau xác nhận email, không xóa chính admin/tài khoản ADMIN theo luồng này, không xóa khi user còn trong room. Đây là thao tác xóa dữ liệu thật, không soft delete; account/session-related data được dọn qua transaction và FK cascade. Không có tự xóa tài khoản ngay một bước cho USER.

## 12. Quản trị nội dung và vận hành trên giao diện

### 12.1. Bài Dictation

Tạo draft; sửa title/description/level/topic/thời lượng/display order, số người nói, accent và độ khó vocabulary/grammar/sentence 1..5. Thêm/sửa/xóa/sắp xếp câu, transcript, speech rate, mốc audio; upload/thay audio; nghe thử; publish/unpublish.

Publish cần ít nhất một câu và audio cho tất cả câu. Thay đổi câu/audio làm bài về draft. Khi có practice history hoặc Listening unit tham chiếu, các sửa đổi cấu trúc/transcript/audio bị khóa để không làm lệch lịch sử; thay level cũng bị chặn. Có **copy sang bài sửa mới** để tiếp tục quản lý. Copy giữ audio URL và các câu, reset calibration; không mặc định copy mọi preview word hoặc mọi telemetry.

Reorder dùng hai bước vị trí tạm âm rồi vị trí dương để tránh vi phạm unique lesson/position. Media cũ chỉ dọn khi đủ điều kiện không còn tham chiếu; upload thất bại/transaction rollback có hook xử lý file, không coi file IO tự động rollback theo DB.

### 12.2. Listening admin

Xem sources, tạo unit bằng AI hoặc thủ công, sửa title/content, preview, publish, retire, copy revision và xem event. Không tạo Listening công khai từ personal content. Unit đã dùng có giới hạn sửa; phiên cũ tiếp tục tham chiếu phiên bản nội dung của nó.

### 12.3. Người dùng và bộ lọc

Quản trị danh sách user có tìm kiếm, role, enabled, verified và sort; view detail, audit, gửi reset, bật/tắt, đổi role, revoke sessions. Không tự khóa/hạ quyền chính mình; giữ ít nhất một ADMIN enabled. Các mutation liên quan có khóa DB `business_locks` với hàng `admin-policy` để phối hợp kiểm tra chính sách. Race đã có kiểm thử theo scenario; không suy ra mọi thao tác quản trị đều dùng cùng khóa.

Danh sách admin lessons/users/units/tickets dùng truy vấn phân trang 20 mục, whitelist sort và kiểm tra filter. Có event theo đối tượng, tối đa 100 event mới nhất khi đọc. Lesson revision dùng `updated_at`, Listening unit/ticket dùng `edit_version`; UI gửi revision. Một số API giữ compatibility khi revision thiếu nên **không phải mọi client đều bắt buộc optimistic revision**.

## 13. Công nghệ và kiến trúc

### 13.1. Stack thực tế

| Thành phần | Công nghệ |
|---|---|
| Backend | Java target 17, Spring Boot **4.1.1**, Maven executable JAR |
| HTTP | Spring MVC, embedded servlet server, JSON REST |
| Persistence | Spring Data JPA/Hibernate cho entity cốt lõi; JdbcTemplate cho Listening, Conversation và nhiều query account/support/admin/Coach |
| Database | PostgreSQL; migration cao nhất trong workspace V26; H2 chỉ test |
| Security | Spring Security, JWT HS256 + refresh token ngẫu nhiên, phiên Redis, BCrypt, CSRF, USER/ADMIN |
| Redis | Phiên token bắt buộc; token bucket rate limit dùng chung khi bật backend Redis |
| RabbitMQ | AMQP qua Spring AMQP; tùy chọn phân phối chấm Listening, TTS hội thoại và Coach |
| Object storage | AWS SDK Java v2 2.55.11; S3 riêng tư cho audio quản trị tải lên mới khi bật |
| JSON | Jackson trong Boot 4, package `tools.jackson.*` |
| Validation | Jakarta Bean Validation và validation nghiệp vụ |
| Email | Spring Mail / SMTP |
| Real time | Spring WebSocket signaling; WebRTC browser voice |
| Frontend | HTML, CSS, JavaScript thuần; History API, fetch, Web Audio, Clipboard, browser speech synthesis fallback |
| AI HTTP | `java.net.http.HttpClient`, không Spring AI SDK hoặc OpenAI SDK |
| TTS AI | Cartesia API |
| Quan sát | Actuator, Micrometer/Prometheus, OpenTelemetry tracing tùy bật, JSON log ECS, requestId/errorId |
| Audio offline | Python, FFmpeg/FFprobe, faster-whisper **1.2.1**, difflib và dynamic programming |
| Kiểm thử | JUnit/Spring Boot tests, MVC integration/security tests, Node built-in test runner |
| Hạ tầng | Windows VPS, NSSM, PowerShell, SSH/SCP, Caddy HTTPS; Docker Compose cho DB local; GitHub Actions |

Phiên bản dependency transitive theo BOM Maven; không giả định phiên bản Hibernate/Jackson chỉ từ kiến thức chung. Môi trường kiểm thử ngày 04/10 dùng JDK 21 nhưng artifact **target Java 17**. PostgreSQL local Compose dùng 18-alpine; CI có matrix 17.11-alpine và 18.4-alpine. Production được tài liệu vận hành trước ghi nhận PostgreSQL 17.11; chưa đọc lại version server ngày 08/10.

### 13.2. Kiến trúc triển khai

```text
Browser HTML/CSS/JS
  | HTTPS JSON/JWT cookie/CSRF + WebSocket signaling
  v
Caddy :18443  ->  Spring Boot 127.0.0.1:18082 (NSSM DictaLab)
                   | JPA + JdbcTemplate
                   +-> PostgreSQL loopback :5432
                   +-> Redis (phiên token; rate limit khi bật)
                   +-> RabbitMQ (tùy bật từng nhóm công việc)
                   +-> static audio trong JAR + media ngoài JAR
                   +-> S3 private (audio quản trị tải mới khi bật)
                   +-> Gemini HTTPS (generation/Listening/Coach/grading/clarification)
                   +-> Cartesia HTTPS (Conversation audio)

Browser người A <-> WebRTC audio trực tiếp/TURN <-> Browser người B
```

Đây là **modular monolith**: các chức năng học, tài khoản và quản trị nằm trong cùng ứng dụng Spring Boot; Redis/broker/object storage là dịch vụ hạ tầng. Bản mã mới không cần browser bridge ngoài backend. Chưa có Kafka, Elasticsearch hoặc vector database.

`open-in-view=false`, Hibernate `ddl-auto=validate`, Flyway quản lý schema. Service transaction kiểm soát nghiệp vụ; JPA @Version ở PracticeSession, conditional SQL ở Listening/Conversation, row lock + revision ở một số admin mutation. Không có một chiến lược locking duy nhất cho mọi module.

### 13.3. Công việc AI nền trong workspace hiện tại

`DICTALAB_AI_QUEUE_ENABLED` mặc định **false**. Khi bật và broker sẵn sàng, `AiBackgroundJobs` xử lý hai loại AUDIO và COACH:

1. HTTP lưu job PENDING trong PostgreSQL, trả trạng thái chờ; frontend đọc trạng thái thay vì chờ TTS/Coach trong request. Coach vẫn có gợi ý bằng quy tắc.
2. Job key unique theo phiên; audio gồm turn/variant/hash SHA-256 câu nghe. Message broker chỉ có UUID job, không có transcript, đáp án hay khóa provider.
3. Dispatcher mỗi 1 giây gửi persistent message, mandatory, publisher confirm tối đa 5 giây. Pending còn trong DB khi publish lỗi; quyền gửi 15 giây, gửi lại 30 giây nếu chưa xử lý.
4. Worker claim bằng transaction ngắn rồi gọi provider bên ngoài. Bảng `ai_job_limits` giữ ngân sách chung mặc định 2 AUDIO và 1 COACH giữa các instance cùng DB; trần 1.000 job pending/running mỗi loại.
5. Lease 180 giây. Worker mất lease chuyển UNKNOWN; không tự gọi lại provider. Retry phải chủ động, tối đa 3 attempts cho job thất bại; UUID đổi để message cũ không kích hoạt lần thử mới.
6. Nếu ProviderGuard từ chối ngay trước khi gửi HTTP (hết slot/circuit mở), job có thể hoãn 5 giây mà không tiêu attempt tính phí. Quy tắc này không áp dụng tùy tiện cho timeout/429/kết quả chưa rõ.
7. Worker kiểm tra audio hash/state và điều kiện cache báo cáo; ACK sau khi lưu trạng thái cuối. Queue và DLQ durable, giới hạn 1.000 message; PostgreSQL vẫn là nguồn trạng thái nghiệp vụ.

Đây không phải việc chuyển mọi gọi AI sang queue: sinh/chấm/làm rõ hội thoại và tạo nội dung Listening còn có luồng riêng. Semaphore provider của các luồng này vẫn theo JVM. Cần cùng cấu hình worker giữa backend; Room vẫn chỉ một JVM. Không coi bật RabbitMQ là đã hỗ trợ scale ngang toàn ứng dụng. Mã và test queue đã có; lượt cập nhật này chưa xác minh test mới hoặc kích hoạt trên production.

## 14. Mô hình dữ liệu hiện tại

Các file migration hiện có chứa 25 lệnh CREATE TABLE, ngoài bảng lịch sử Flyway. Đây là số bảng khai báo trong workspace, không phải kết quả đếm database đang chạy. Nội dung JSON hiện chủ yếu lưu **TEXT**, không phải thiết kế JSONB có index tìm kiếm.

| Bảng | Nội dung và quan hệ chính |
|---|---|
| `app_users` | Email unique, display name, password hash, role, enabled, verified, auth_version, timestamps |
| `lessons` | Slug, title, level, topic, active, content origin, difficulty metadata, calibration |
| `lesson_sentences` | FK lesson, vị trí unique trong lesson, transcript, audio URL/start/end, speech rate |
| `lesson_preview_words` | FK lesson, vị trí, từ/cụm, mốc audio, proper_name |
| `practice_sessions` | UUID string, FK user/lesson, IN_PROGRESS/COMPLETED, current position, điểm/totals, first_exposure, room_id, version |
| `sentence_attempts` | FK practice session/sentence, replay/hint/reveal, thời gian, playback telemetry và hoàn thành |
| `word_attempts` | FK sentence attempt + word index, first/last answer, correctness, số thử, assistance, reveal/error/outcome |
| `word_attempt_events` | FK word attempt; mỗi lượt check với raw/normalized answer, đúng/sai, response time, error category |
| `listening_units` | FK lesson, audio URL/duration, content JSON, published/retired, supersedes_unit_id, edit_version |
| `listening_sessions` | UUID string, FK user/unit, mode/stage/pass, first exposure, option order JSON, version, timestamps |
| `listening_passes` | Khóa session/pass, playback token/timing, recall/draft, answers/draft, điểm, grading state/retry/model/rubric |
| `listening_review_marks` | FK session/sentence, difficulty type, note |
| `account_tokens` | Token hash, FK user, purpose, target email, hạn/dùng một lần, request IP |
| `account_audit` | Actor/subject, action, detail và thời điểm |
| `support_tickets` | FK user, category, subject/description, reference, status/note, revision và timestamps |
| `audio_exposure_events` | FK user, audio URL, source sự kiện và thời điểm tiếp xúc |
| `admin_events` | Actor, object_type/object_id, action/detail, thời điểm; liên kết đối tượng theo kiểu polymorphic |
| `dictation_coach_reports` | Khóa/FK practice session, report JSON đã validate |
| `dictation_error_bank` | FK user/session, item ID/snapshot JSON; unique user/session/item |
| `ai_conversation_sessions` | UUID string, FK user, scenario/difficulty, state JSON, version/lease/busy_until |
| `ai_conversation_audio` | Khóa session/turn/variant, WAV BYTEA riêng tư |
| `business_locks` | Hàng khóa dùng phối hợp chính sách quản trị |
| `email_outbox` | Job thư liên kết với token; trạng thái, attempts, lease, nội dung thư chờ gửi |
| `ai_job_limits` | Ngân sách worker chung theo AUDIO/COACH |
| `ai_background_jobs` | Job key unique, FK phiên, hash audio, trạng thái, attempts, lease và lịch publish |

Quan hệ cốt lõi:

```text
User -> PracticeSession -> SentenceAttempt -> WordAttempt -> WordAttemptEvent
Lesson -> LessonSentence; Lesson -> PreviewWord
User -> ListeningSession -> ListeningPass / ReviewMark
Lesson -> ListeningUnit -> ListeningSession
PracticeSession -> CoachReport / ErrorBank
User -> ConversationSession -> ConversationAudio
User -> AccountToken / SupportTicket / ExposureEvent
```

Không lưu phòng gọi thoại thành bảng. Audio lesson/preview tham chiếu URL; audio Conversation lưu BYTEA. Dữ liệu ownership và FK/unique/check constraint là phần quan trọng của tính đúng, không chỉ có frontend state.

### 14.1. Migration

| Version | Mục đích |
|---|---|
| V1 | Schema lesson, sentence, practice, sentence/word attempt và index ban đầu |
| V2 | Seed bài ban đầu |
| V3 | Import ELLLO |
| V4 | Chia hội thoại ELLLO thành đơn vị câu |
| V5 | Tài khoản user và gắn ownership practice |
| V6 | Chi tiết đáp án đầu và loại reveal phục vụ review |
| V7 | Audio gốc + mốc đoạn câu |
| V8 | Nội dung do admin quản lý |
| V9 | Metadata assessment, first exposure, playback và word event telemetry |
| V10 | Cho phép personal content origin |
| V11 | Ẩn lesson ELLLO legacy |
| V12 | Preview vocabulary |
| V13 | Phân loại preview/tên riêng |
| V14 | Gắn room_id vào shared practice |
| V15 | Listening units/sessions/passes/review và starter unit |
| V16 | Thời điểm grading/retry |
| V17 | Seed Listening theo level |
| V18 | Account token/audit/support, verification/revocation, Listening drafts/revision lineage |
| V19 | Exposure event theo audio |
| V20 | Edit version và admin events |
| V21 | Dictation Coach và ngân hàng lỗi |
| V22 | AI Conversation và audio riêng tư |
| V23 | Constraint bảo vệ dữ liệu, grading lease, khóa chính sách admin và email outbox |
| V25 | Metadata dispatch RabbitMQ trên listening_passes |
| V26 | ai_job_limits và ai_background_jobs cho audio/Coach |

Workspace có 25 file migration, version cao nhất V26; **không có file V24** trong thư mục hiện tại. Báo cáo triển khai cũ có nhắc schema V24, báo cáo ngày 04/10 nhắc V25. Cần đối chiếu lịch sử Flyway của môi trường và artifact trước đợt phát hành tiếp theo; không tự tạo V24, sửa checksum hoặc suy ra mọi version liên tiếp đều có file.

Không sửa migration đã chạy để cập nhật production; thêm migration tiến tới. `ddl-auto=validate` không tự sửa schema thay Flyway.

## 15. Audio, cache và pipeline nội dung

### 15.1. Phát audio runtime

Dictation dùng Web Audio `decodeAudioData`, `AudioBufferSourceNode.start(offset, duration)`, chọn tốc độ 0,75×/1×/1,25×. Tải và giải mã cả file nguồn, rồi phát đoạn câu theo start/end. Cache Map tối đa 8 audio buffer promise, đưa mục vừa dùng về cuối, loại mục cũ nhất: LRU đơn giản, cache trong bộ nhớ tab.

Có playback generation để tải audio chậm không phát sau khi đổi câu/route. Khi câu không có audio ghi sẵn, có fallback browser `speechSynthesis`, ưu tiên voice en-US hoặc voice tiếng Anh. Production lesson công khai được publish yêu cầu audio; fallback không có nghĩa mọi bài đang dùng TTS browser.

Tốc độ Web Audio qua playbackRate không phải thuật toán time-stretch giữ nguyên pitch chuyên dụng. Cache limit theo số file, không theo byte; file dài vẫn tốn RAM. Dữ liệu nháp Dictation/Conversation có state/Map trong tab; không coi mọi draft được lưu bền qua reload. Listening có draft server riêng.

### 15.2. Kho media

- Audio ELLLO tĩnh đóng gói trong resources/JAR.
- Audio admin upload mới: khi S3 bật, lưu bucket riêng tư, DB giữ `/media/s3/{UUID.ext}`; khi S3 tắt, lưu ngoài JAR tại `DICTALAB_MEDIA_ROOT`.
- File local cũ `/media/{UUID.ext}` tiếp tục phát, không tự di chuyển/xóa khi bật S3.
- Nội dung cá nhân qua `/private-media/`, chỉ ADMIN.
- Conversation WAV trong PostgreSQL, endpoint private theo session.

Upload chỉ MP3/M4A/WAV, giới hạn 25 MB, request multipart 26 MB. Kiểm tra extension, MIME whitelist và magic header ID3/MPEG frame, RIFF/WAVE hoặc ftyp. Không tin original filename; tên lưu UUID. Normalize/kiểm tra đường dẫn; ghi file tạm rồi atomic move khi hỗ trợ. Kiểm tra magic không phải quét virus hoặc phân tích toàn bộ codec.

Với S3, ứng dụng kiểm tra JWT/Redis trước khi trả redirect 307 no-store tới URL ký, mặc định 30 phút, cho phép cấu hình từ 1 giây đến 1 giờ. URL ký cấp quyền nghe tạm thời cho người có link; logout không thu hồi ngay link đã cấp. Bucket cần CORS đúng origin để Web Audio tải bytes. Lỗi S3 trả 503, không tự lưu file vào local. Upload/replace có xử lý rollback và chỉ dọn object cũ không còn được tham chiếu sau commit. Audio ELLLO, personal và WAV Conversation chưa chuyển lên S3.

Theo `docs/s3-audio.md`, S3 đã được bật/kiểm tra trên VPS ngày 04/10: bucket riêng tư, public access bị chặn, SSE-S3, policy chỉ Put/Get/Delete trong prefix, fetch/Range/CORS và Web Audio decode qua Chrome thật đạt. Ba file local cũ được giữ. Versioning/lifecycle chưa bật; backup DB/media local không sao lưu bucket S3.

### 15.3. Pipeline offline ELLLO

Corpus ELLLO được tài liệu repository ghi nhận ban đầu: 20 bài A1–B2, 79 hội thoại người thật, khoảng 910 câu. Đây là **corpus seed**, không phải số bài active hiện tại trong production sau admin chỉnh sửa.

Ngày 02/10/2026 đã bổ sung ba bài Dictation B1 do Gemini 2.5 Flash sinh, Cartesia Sonic 3.6 tạo hai giọng AI: Đổi kế hoạch chuyến đi, Đổi trả đơn hàng, Lên kế hoạch hoạt động nhóm. Mỗi bài có 12 câu, tổng 36 câu; lesson ID production 121–123. Audio lưu ngoài JAR theo đoạn câu; đánh dấu chưa calibrated và ghi nguồn AI. Xem `docs/ai-b1-dictation-2026-10-02.md` cho kiểm tra/provenance. Đây là batch nội dung đã xuất bản, không phải màn hình cho người học tự sinh bài Dictation.

Script tải, sinh manifest và migration; `split-elllo-sentences.py` xử lý:

1. Parse speaker turns, tách câu theo punctuation với xử lý các trường hợp dấu chấm.
2. FFprobe lấy duration; FFmpeg `silencedetect=noise=-35dB:d=0.055` lấy khoảng lặng.
3. Có mode căn mốc theo khoảng lặng và mode dùng Whisper word timestamps.
4. Mode khoảng lặng dùng dynamic programming chọn ranh giới đơn điệu, tối ưu sai lệch vị trí kỳ vọng, nhịp/độ dài phần nói, ưu tiên khoảng nghỉ và chỗ đổi người nói; tránh đoạn quá ngắn.
5. Mode Whisper dùng faster-whisper CPU int8, mặc định model `tiny.en`, beam size 5, word timestamps, vad_filter=false, không conditioning từ đoạn trước.
6. Align transcript tokens với ASR tokens bằng dynamic programming insertion/deletion/substitution; similarity dùng `difflib.SequenceMatcher`. Insert/delete cost 1; substitution 0 nếu exact, còn lại `1.10 - 0.35*similarity`.
7. Suy ra ranh giới câu từ word mapping và khoảng lặng, xử lý chỗ transcript/audio thiếu khớp, validate duration/order/segment; cache word timestamps để giảm xử lý lại.

ASR chỉ phục vụ chuẩn bị/căn mốc nội dung offline, **không nghe và chấm câu trả lời của người dùng runtime**. DP alignment sequence lưu ma trận nên bộ nhớ tăng theo tích số token; đây không phải phiên bản Levenshtein hai hàng dùng trong classifier runtime.

### 15.4. Nội dung cá nhân

Có script chuẩn bị British Council personal và import manifest khi bật cấu hình. Manifest gồm lesson metadata, audio, câu đã căn mốc và 2–5 preview words. Import kiểm tra origin, file dưới root, vị trí và thời gian; bỏ qua slug đã tồn tại để tránh import trùng. Có xử lý ẩn personal lesson cũ không còn trong bộ manifest tương ứng.

Nội dung này chỉ cho mục đích cá nhân của admin; không biến việc tải/import thành quyền phát hành công khai. Cần kiểm tra giấy phép cho mọi corpus nếu mở rộng sản phẩm thương mại/public.

### 15.5. Chuẩn bị từ preview

`scripts/enrich-preview-vocabulary.py` là công cụ offline gọi Gemini để phân loại tên riêng và chọn từ hiếm/chuyên biệt/thành ngữ/vượt level. Parser đối chiếu lại cụm với transcript thật, chuẩn hóa, loại trùng và không gộp tên riêng vào difficult words. Có cache kết quả và self-test. Script có khả năng cập nhật dữ liệu VPS; không phải một worker tự chạy mỗi lần người học mở catalog, và không được chạy tùy tiện trên production để thử.

## 16. AI clients và giới hạn tích hợp

### 16.1. Gemini

Endpoint cấu hình được, mặc định `https://generativelanguage.googleapis.com/v1beta/openai/chat/completions`. HttpClient connect timeout 10 giây; tác vụ chung timeout 60 giây, bước sinh Conversation theo override có timeout 30 giây. Payload temperature=0, max_tokens=2200, không stream.

Body mạng bị chặn ở 100.000 byte bằng `BoundedProviderBody`, nội dung model tối đa 12.000 ký tự; có bỏ markdown fence/đoạn bao ngoài để lấy JSON object và kiểm tra trailing tokens. `sendAsync` được chặn thời hạn gồm đọc body và cancel trong finally để response chậm không giữ permit vô hạn.

ProviderGuard mặc định giới hạn Gemini 8 request đồng thời mỗi JVM; circuit mở sau 5 lỗi provider và chờ 30 giây, một probe phục hồi. HTTP 429 đọc Retry-After với ngân sách tối đa 300 giây cho giá trị hợp lệ; retry do từng nghiệp vụ quyết định, client không tự gọi lại mù. Cartesia có guard riêng. Các giới hạn này không phải quota tiền/token cho toàn hệ thống.

API key chỉ ở server. Tên model gateway trả lại không chứng minh độc lập model thực sự ở upstream. Chưa có bảng giá/chi phí/token accounting được xác minh cho tài khoản trong tài liệu này.

### 16.2. Coach và chấm/làm rõ qua Gemini

Các luồng này dùng cùng `GeminiClient` với Listening, chung khóa/URL/model cấu hình. Coach chỉ gửi bằng chứng đã tính bởi backend, kiểm tra pattern/evidence/progress và lưu report một lần. Khi queue tắt, Coach giữ semaphore một yêu cầu generation đồng thời; khi queue bật, dùng ngân sách worker DB (mục 13.3). BUSY/UNAVAILABLE hoặc job chưa hoàn tất vẫn có baseline, Smart Review và Error Bank.

Hội thoại gửi JSON của prompt, attempts và context khi chấm; khi làm rõ chỉ gửi prompt. Ngữ cảnh lấy từ phiên thuộc người dùng đã kiểm tra ownership, không dựa vào lịch sử tab bên nhà cung cấp. Pending answer, requestId, lease/version và kiểm tra evidence vẫn giữ nguyên; không có HTTP gọi `/v1/sessions/.../close` hoặc fallback sang ChatGPT web.

`ChatGptWebCoachClient` đã bỏ khỏi mã chạy. Các biến `DICTALAB_COACH_URL/MODEL/API_KEY/TIMEOUT` cũ không còn được đọc; công tắc `DICTALAB_COACH_ENABLED` vẫn tồn tại. Script/patch bridge được giữ để tra cứu lịch sử/rollback bản JAR cũ, không nằm trong khởi động/deploy bản mới. Không xóa hay thay profile/login/browser đang chạy trên VPS trong lượt sửa mã này.

### 16.3. Các phép đo AI đã có

Ngày 02/10 có benchmark sinh hội thoại đúng prompt của chương trình: Gemini 2.5 Flash trung vị 1,747 giây qua 3 đầu vào A1/A2/B1; ChatGPT Web 21,472 giây; một mô hình cloud khác (Sonnet) 28,987 giây. Đó là thời gian HTTP tới toàn bộ nội dung, **không gồm TTS, database hoặc thời gian tải giao diện**, không đủ để kết luận p95/SLA.

Đo sinh bộ MCQ Listening là tác vụ khác: Web có 3/3 output qua schema, Sonnet có phản hồi rỗng và sai ngôn ngữ trong mẫu. Không dùng tốc độ hội thoại ngắn để hứa tốc độ MCQ dài. Các tài liệu benchmark là lịch sử trước tích hợp; phần mô tả “Conversation còn dùng Web để generation” trong benchmark cũ đã được thay bằng luồng hiện tại ở mục 8.

Nguồn nội bộ: `docs/ai-conversation-speed-2026-10-02.md`, `docs/ai-question-speed-2026-10-02.md`. Độ đúng schema không chứng minh factual/sư phạm đúng; cần kiểm tra nội dung thật.

## 17. Bảo mật, tính đúng và giới hạn

### 17.1. Biện pháp hiện có

- BCrypt cost 12; không lưu mật khẩu plaintext.
- JWT access token HS256, hạn mặc định 10 phút; kiểm tra chữ ký, issuer, audience `dictalab-web`, key ID, loại token và expiry. Claims có user ID, session ID và authVersion, không có email/mật khẩu/nội dung học.
- Refresh token gồm UUID phiên và 32 byte ngẫu nhiên, hạn tuyệt đối mặc định 7 ngày; Redis giữ hash SHA-256. Lua xoay token nguyên tử, không kéo dài hạn gốc; dùng lại token đã xoay thu hồi gia đình phiên.
- Cookie `DICTALAB_ACCESS` HttpOnly, Path `/`; `DICTALAB_REFRESH` HttpOnly, Path `/api/auth`; SameSite=Lax và Secure ở production. Không lưu access/refresh trong localStorage, sessionStorage hoặc URL.
- CSRF cookie repository, lấy token qua `/api/auth/csrf`; bắt buộc cho thao tác ghi kể cả login/register/refresh/logout. Cookie CSRF có thể đọc bởi JS theo thiết kế.
- Redis kiểm tra phiên mỗi request đã xác thực; PostgreSQL giữ account/authVersion. Redis mất key phiên yêu cầu đăng nhập lại; Redis/DB không khả dụng trả 503/Retry-After, không bỏ qua xác thực.
- Mặc định tối đa 20.000 phiên hệ thống, 10 phiên/user, 2.048 rotation/phiên; trần phiên không bảo đảm đủ RAM ở mọi mức rotation. Có cấu hình chấp nhận khóa ký trước khi xoay khóa, không tự sinh secret mỗi restart.
- Frontend tự refresh một lần khi 401, phục hồi CSRF một lần nếu có `X-CSRF-Error: 1`; Web Locks phối hợp nhiều tab ở secure context. Không tự retry thao tác ghi sau lỗi mạng/503 hoặc refresh mất phản hồi; không retry 403 thiếu quyền thật. Browser thiếu Web Locks cần đăng nhập lại khi access hết hạn.
- Phân quyền USER/ADMIN; ownership truy vấn dựa vào principal, không tin userId từ body.
- AccountStateFilter từ chối principal không còn khớp tài khoản; authVersion thu hồi quyền phiên cũ. WebSocket kiểm tra lại token/quyền định kỳ.
- Token email random + hash + expiry + single-use.
- Validation input, whitelist sort/SQL structure, parameterized queries cho dữ liệu.
- Upload validation và path checks.
- Chặn truy cập JSON/TXT metadata dưới `/audio/elllo/`; audio tĩnh vẫn public theo cấu hình.
- API errors không trả stacktrace; unexpected error có errorId để tìm log.
- Nhóm auth/AI/upload có RequestRateLimitFilter; backend mặc định memory hoặc Redis token bucket dùng chung khi bật. Redis lỗi trong chế độ Redis trả 503, không hạ xuống RAM. Không coi đây là bảo vệ chống DDoS toàn tuyến hoặc quota tiền cho provider.
- Production DB/backend/management/broker theo cấu hình nội bộ; Redis ngoài máy cần xác thực/TLS. Bridge/CDP cũ không phải phụ thuộc ứng dụng mới.
- HTTPS Caddy, SHA-256 artifact và backup trước deploy.

### 17.2. Không được tuyên bố quá mức

- Dictation API state có `speechText` từ transcript cho fallback và render; không phải cơ chế giữ đáp án bí mật tuyệt đối trước người dùng biết kiểm tra network. Copy đã sửa không đọc đáp án ẩn nhưng không thay đổi toàn bộ API chống gian lận.
- Listening/Conversation giảm lộ answer facts trong view, không phải môi trường thi proctored.
- Rate limit đã có cho nhóm auth/AI/upload và giới hạn theo từng chức năng email/support/room/Conversation; phạm vi rule không phải mọi request đọc hoặc mọi tuyến hạ tầng.
- Chưa có MFA, OAuth/SSO, CAPTCHA tổng quát hoặc chính sách secret rotation tự động được mô tả trong code hiện tại.
- Email outbox, Conversation lease, Listening worker và AI queue đã tách IO theo từng luồng. Không dùng điều này để tuyên bố mọi phương thức trong toàn ứng dụng đã được audit transaction.
- Room và provider semaphore vẫn trong process; Redis session/rate limit cùng ngân sách AI job DB chỉ giải quyết một phần điều phối nhiều instance.
- Có restore drill DB/media/app theo các báo cáo triển khai và restore SQL ngày 04/10; chưa có chứng cứ khôi phục toàn hệ thống gồm S3/Redis/cấu hình hạ tầng. Chưa có audit bảo mật độc lập hoặc chứng nhận accessibility.

## 18. Danh sách API hiện tại

API JSON cùng origin, mang cookie JWT. Thao tác ghi cần CSRF. Ký hiệu `{id}`, `{sessionId}`, `{lessonId}` là tham số đường dẫn; mỗi nhóm ghi rõ prefix. Route inventory không thay thế DTO source về toàn bộ field/validation.

### 18.1. Auth và account

| Method | Path | Mục đích |
|---|---|---|
| GET | `/api/auth/csrf` | Lấy CSRF token |
| POST | `/api/auth/register` | Đăng ký và đăng nhập |
| POST | `/api/auth/login` | Đăng nhập |
| POST | `/api/auth/refresh` | Xoay refresh và cấp access mới, trả 204 |
| GET | `/api/auth/me` | Tài khoản hiện tại |
| POST | `/api/auth/logout` | Đăng xuất |
| POST | `/api/auth/password-reset/request` | Gửi link reset |
| POST | `/api/auth/password-reset/complete` | Consume reset token |
| POST | `/api/auth/email/confirm` | Xác minh email |
| POST | `/api/auth/email/change/confirm` | Xác nhận email mới |
| PUT | `/api/account/profile` | Đổi tên |
| POST | `/api/account/password` | Đổi mật khẩu |
| POST | `/api/account/logout-all` | Thu hồi các phiên |
| POST | `/api/account/email/verification` | Gửi xác minh |
| POST | `/api/account/email/change` | Yêu cầu đổi email |

### 18.2. Catalog, Dictation, History, Coach

| Method | Path | Mục đích |
|---|---|---|
| GET | `/api/v1/catalog/levels` | Các level |
| GET | `/api/v1/catalog/lessons` | Bài theo level và tiến độ |
| POST | `/api/v1/catalog/preview-exposure` | Ghi tiếp xúc audio preview |
| POST | `/api/v1/practice-sessions` | Start/resume bài |
| GET | `/api/v1/practice-sessions/{sessionId}` | Câu hiện tại |
| POST | `/api/v1/practice-sessions/{sessionId}/sentences/{sentenceId}/replay-events` | Playback telemetry |
| POST | `/api/v1/practice-sessions/{sessionId}/sentences/{sentenceId}/words/{wordIndex}/checks` | Chấm từ |
| POST | `/api/v1/practice-sessions/{sessionId}/sentences/{sentenceId}/hints` | Gợi ý/reveal |
| POST | `/api/v1/practice-sessions/{sessionId}/sentences/{sentenceId}/classifications` | Tự phân loại từ sai |
| POST | `/api/v1/practice-sessions/{sessionId}/sentences/{sentenceId}/complete` | Hoàn thành câu |
| GET | `/api/v1/history` | Lịch sử có page/size/level |
| GET | `/api/v1/history/{sessionId}` | Review phiên hoàn thành |
| GET | `/api/v1/dashboard` | Tổng quan solo Dictation |
| GET | `/api/v1/practice-sessions/{sessionId}/coach` | Cache/phân tích cơ sở |
| POST | `/api/v1/practice-sessions/{sessionId}/coach` | Yêu cầu phân tích AI |
| GET, POST | `/api/v1/error-bank` | Đọc/lưu lỗi |

### 18.3. Listening — prefix `/api/v1/listening`

| Method | Suffix | Mục đích |
|---|---|---|
| GET | `/units` | Catalog |
| POST | `/sessions` | Tạo phiên |
| GET | `/sessions/{id}` | State |
| POST | `/sessions/{id}/play` | Bắt đầu nghe/token |
| POST | `/sessions/{id}/play/complete` | Kết thúc nghe |
| PUT, POST | `/sessions/{id}/recall` | Lưu draft/nộp recall |
| PUT, POST | `/sessions/{id}/answers` | Lưu draft/nộp MCQ |
| POST | `/sessions/{id}/grading/{pass}/retry` | Retry recall grade |
| GET | `/sessions/{id}/pass-1-result` | Điểm lượt một |
| POST | `/sessions/{id}/second-pass` | Mở lượt hai |
| POST | `/sessions/{id}/finish-after-pass-1` | Hoàn thành một lượt |
| POST | `/sessions/{id}/abandon` | Bỏ phiên |
| GET | `/sessions/{id}/report` | Báo cáo |
| GET | `/sessions/{id}/review` | Transcript/review |
| POST | `/sessions/{id}/review-marks` | Đánh dấu câu khó |
| GET | `/history` | Lịch sử riêng |

### 18.4. Conversation — prefix `/api/v1/conversation`

| Method | Suffix | Mục đích |
|---|---|---|
| GET | rỗng | Scenarios, enabled, 12 phiên gần nhất |
| POST | `/sessions` | Tạo phiên |
| GET | `/sessions/{id}` | State/summary đã lọc |
| POST | `/sessions/{id}/next` | Tạo/chuyển nhiệm vụ |
| POST | `/sessions/{id}/answer` | Gửi/retry câu trả lời |
| POST | `/sessions/{id}/help` | Transcript/dịch/làm rõ |
| POST | `/sessions/{id}/play` | Audio variant và ghi hỗ trợ |
| GET | `/sessions/{id}/audio/{turn}/{variant}` | WAV riêng tư |

### 18.5. Rooms — prefix `/api/v1/rooms`

| Method | Suffix | Mục đích |
|---|---|---|
| POST | rỗng | Tạo phòng |
| GET | `/mine` | Phòng hiện tại, có thể 204 |
| GET | `/join/{inviteCode}` | Thông tin lời mời |
| POST | `/{roomId}/join` | Xin tham gia |
| POST | `/{roomId}/approve`, `/{roomId}/reject` | Host xử lý guest |
| GET, DELETE | `/{roomId}` | State/rời phòng |
| GET | `/{roomId}/ice-config` | STUN/TURN |
| POST | `/{roomId}/lesson` | Host chọn bài |
| POST | `/{roomId}/practice` | Start/resume shared practice |
| POST | `/{roomId}/ready` | Báo đã hoàn thành câu |
| POST | `/{roomId}/advance` | Host chuyển khi cả hai ready |

WebSocket riêng: `/ws/room/{roomId}`, không STOMP/SockJS.

### 18.6. Admin lessons — prefix `/api/admin/lessons`

| Method | Suffix | Mục đích |
|---|---|---|
| GET, POST | rỗng | Legacy list/tạo draft |
| GET | `/page`, `/filters` | Danh sách phân trang, filter |
| GET, PUT | `/{lessonId}` | Detail/sửa metadata |
| GET | `/{lessonId}/events` | Event |
| POST | `/{lessonId}/copy` | Copy revision |
| POST | `/{lessonId}/sentences` | Thêm câu |
| PUT, DELETE | `/{lessonId}/sentences/{sentenceId}` | Sửa/xóa câu |
| PUT | `/{lessonId}/sentences/order` | Reorder |
| POST | `/{lessonId}/sentences/{sentenceId}/audio` | Multipart upload |
| PUT | `/{lessonId}/publication` | Publish/unpublish |

### 18.7. Admin Listening — prefix `/api/admin/listening`

| Method | Suffix | Mục đích |
|---|---|---|
| GET | `/units`, `/units/page` | Legacy list/phân trang |
| GET | `/units/{id}`, `/units/{id}/events` | Detail/events |
| GET | `/sources` | Audio lesson có thể tạo unit |
| POST | `/units` | Sinh draft AI |
| POST | `/units/manual` | Draft thủ công |
| POST | `/units/{id}/copy` | Revision mới |
| PUT | `/units/{id}` | Sửa draft |
| PUT | `/units/{id}/retire` | Ngừng phiên bản |
| PUT | `/units/{id}/publication` | Publish/unpublish |

### 18.8. Admin users và support

| Method | Path | Mục đích |
|---|---|---|
| GET | `/api/admin/users`, `/api/admin/users/page` | Legacy list/phân trang |
| GET | `/api/admin/users/{id}`, `/api/admin/users/{id}/audit` | Detail/audit |
| POST | `/api/admin/users/{id}/password-reset` | Gửi reset |
| POST | `/api/admin/users/{id}/revoke-sessions` | Thu hồi phiên |
| PUT | `/api/admin/users/{id}/state`, `/api/admin/users/{id}/role` | Enabled/role |
| GET, POST | `/api/support/tickets` | Ticket của mình/tạo mới |
| GET | `/api/admin/support/tickets`, `/api/admin/support/tickets/page` | Legacy list/filter page |
| GET, PUT | `/api/admin/support/tickets/{id}` | Detail/xử lý |
| GET | `/api/admin/support/tickets/{id}/grading` | Dữ liệu chấm Listening |
| PUT | `/api/admin/support/tickets/{id}/grading/{pass}` | Grade thủ công |
| POST | `/api/admin/support/tickets/{id}/delete-account` | Xóa theo yêu cầu xác nhận |

API bổ sung trong workspace: GET `/api/v1/listening/history/page` cho lịch sử phân trang; GET `/api/v1/conversation/sessions/{id}/audio-status?turnNumber=...&variant=...` cho trạng thái audio nền; GET `/media/s3/{filename}` kiểm tra xác thực rồi redirect 307 tới S3. Phản hồi riêng tư của Conversation/media redirect dùng no-store.

App public có GET `/actuator/health` qua controller trả tình trạng lõi. Actuator thật health/info/prometheus/metrics được cấu hình trên management server riêng `127.0.0.1:18183` mặc định; không công khai endpoint metrics. Lượt triển khai ngày 03/10 ghi nhận management metrics 200, truy cập metrics qua app chưa xác thực 401.

## 19. Cấu hình, chạy local và production

### 19.1. Biến môi trường quan trọng

| Nhóm | Tên biến; ý nghĩa |
|---|---|
| DB backend | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`; password bắt buộc |
| DB Compose | `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_PORT` |
| HTTP/cookie | `PORT`, `JWT_COOKIE_SECURE`, `SESSION_COOKIE_SECURE`, `DICTALAB_PUBLIC_URL`, `DICTALAB_ALLOWED_ORIGINS` |
| JWT | `JWT_SIGNING_KEY`, `JWT_PREVIOUS_SIGNING_KEY`, `JWT_KEY_ID`, `JWT_PREVIOUS_KEY_ID`, `JWT_ACCESS_LIFETIME`, `JWT_REFRESH_LIFETIME`, `JWT_MAXIMUM_SESSIONS`, `JWT_SESSIONS_PER_USER` |
| Redis | `REDIS_URL` hoặc `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`; bắt buộc cho đăng nhập |
| Rate limit | `DICTALAB_RATE_LIMIT_BACKEND`, `RATE_LIMIT_ENABLED`, burst theo auth/AI/upload |
| RabbitMQ | `RABBITMQ_URL` hoặc host/port/user/password; `DICTALAB_LISTENING_QUEUE_ENABLED`, `LISTENING_WORKERS` |
| Audio/Coach nền | `DICTALAB_AI_QUEUE_ENABLED`, `DICTALAB_AUDIO_WORKERS`, `DICTALAB_COACH_WORKERS`; tên exchange/queue tùy cấu hình |
| Admin/media | `DICTALAB_ADMIN_EMAILS`, `DICTALAB_MEDIA_ROOT` |
| S3 | `DICTALAB_MEDIA_S3_ENABLED`, `DICTALAB_MEDIA_S3_BUCKET`, `DICTALAB_MEDIA_S3_REGION`, `DICTALAB_MEDIA_S3_PREFIX`, `DICTALAB_MEDIA_S3_SIGNED_URL_DURATION`; credential qua AWS credential chain |
| Capacity/quan sát | `DB_POOL_SIZE`, `DB_POOL_WAIT_MS`, `HTTP_THREADS`, `MANAGEMENT_PORT`, `TRACE_EXPORT_ENABLED`, `TRACE_ENDPOINT`, `TRACE_SAMPLE_RATE`, `DICTALAB_LOG_FILE` |
| SMTP | `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_AUTH`, `SMTP_STARTTLS`, `DICTALAB_MAIL_FROM` |
| Gemini | `GEMINI_API_KEY`, `GEMINI_MODEL`, `GEMINI_URL` |
| Coach qua Gemini | `DICTALAB_COACH_ENABLED`; dùng chung `GEMINI_API_KEY`, `GEMINI_MODEL`, `GEMINI_URL` |
| Conversation | `DICTALAB_CONVERSATION_ENABLED`, `DICTALAB_CONVERSATION_MODEL` |
| Cartesia | `CARTESIA_API_KEY`, `CARTESIA_VOICE`, `CARTESIA_MODEL`, `CARTESIA_VERSION` |
| TURN | `DICTALAB_TURN_URL`, `DICTALAB_TURN_SECRET`, `DICTALAB_TURN_TTL_SECONDS` |
| Personal | `DICTALAB_PERSONAL_CONTENT_ENABLED`, `DICTALAB_PERSONAL_CONTENT_ROOT`, `DICTALAB_PERSONAL_CONTENT_MANIFEST` |
| PostgreSQL tests | `TEST_DB_URL`, `TEST_DB_USERNAME`, `TEST_DB_PASSWORD` |

Mặc định local backend cổng 18081, DB localhost:15433/dictalab, username dictalab_app, media `./data/media`. Redis local cổng 16379; AMQP 15672, Rabbit management 25672 theo Compose của dự án. Coach/Conversation/personal, hai queue và S3 mặc định false trong cấu hình ứng dụng; `run-local.ps1 -WithMessaging` bật Redis rate limit cùng Listening/AI queue. Redis auth vẫn bắt buộc khi queue tắt. SMTP port 587, auth/STARTTLS true. Các số cổng là cấu hình dự án, không suy ra cổng mặc định nhà cung cấp. Đọc `.env.example` và script khởi động, không gửi `.env` cho ChatGPT.

### 19.2. Chạy và kiểm tra local

Cần JDK 17 trở lên tương thích target, Maven, PowerShell 7, Node để test JS và PostgreSQL/Redis qua Docker hoặc môi trường có sẵn. Môi trường mới cần khóa JWT Base64 từ ít nhất 32 byte ngẫu nhiên. Dùng `scripts/run-local.ps1` theo README; script xử lý cấu hình môi trường, DB và Redis local. RabbitMQ cần khi bật queue. Pipeline audio offline cần Python/FFmpeg và requirements riêng, không bắt buộc để dùng corpus đã có.

Các kiểm tra có thể chạy lại:

```powershell
mvn --batch-mode --no-transfer-progress verify
node --test src/test/js/*.cjs
& ./scripts/test-postgres.ps1
```

`test-postgres.ps1` tạo cluster test riêng dưới target, loopback cổng 15434 mặc định, dừng trong finally. Không trỏ integration test tới database production. Không chạy Maven clean hoặc xóa target khi cluster test đang hoạt động. Không chạy hai Maven task cùng target đồng thời.

### 19.3. Production được ghi nhận đến ngày 04/10/2026

Backend chạy Windows service NSSM `DictaLab`; PostgreSQL service riêng. Caddy reverse proxy loopback 18082, public HTTPS 18443, HTTP18081 redirect308. Caddy cấu hình ACME Let's Encrypt shortlived; không khẳng định certificate đang còn hạn nếu chưa kiểm tra runtime.

Mốc 02/10 khoảng 23:10 (UTC+7) chuyển Coach và chấm/làm rõ Conversation sang Gemini; sinh câu và Listening đã dùng Gemini, giọng vẫn Cartesia. Khóa/config provider đã được ghi nhận hoạt động; bridge HTTP3000/Chrome CDP9223 cũ chỉ có ý nghĩa lịch sử cho bản JAR cũ. Không suy ra task/profile cũ còn tồn tại hoặc đang chạy vào ngày 08/10.

Mốc triển khai S3 ngày 04/10 trong `docs/s3-audio.md` ghi nhận JWT/Redis hoạt động, upload S3 thật, protection ADMIN/CSRF, Range/CORS và giải mã audio trong Chrome đạt. Health UP, dữ liệu thử được dọn. SHA-256 JAR production được ghi trong báo cáo này:

```text
DFAC955B6FC915DEF78AB5563DA12D24311AD9E5BF0AAE7B027E9C182453CC2F
```

Trước đó, báo cáo JWT recovery ngày 04/10 ghi schema V25, 7 tài khoản và 39 phiên Dictation tại thời điểm kiểm tra. Đây không phải số người dùng hoạt động hoặc tổng hiện tại ngày 08/10. Tài liệu cấu hình Redis đã ghi nhận kết nối TLS cloud; không công bố credential/URL có mật khẩu.

Hàng đợi RabbitMQ tạo AUDIO/COACH và V26 hiện có trong workspace, nhưng tài liệu messaging ghi mặc định production tắt và chưa xác minh rollout. Không gán hash JAR trên cho thay đổi local V26 hoặc mọi file đang sửa. Lượt viết tài liệu này không deploy và không xác nhận trạng thái VPS theo thời gian thực.

### 19.4. Deploy và rollback

Các script deploy kiểm tra JS, build/test Maven và upload artifact. Quy trình cập nhật Windows VPS có preflight dữ liệu, đối chiếu SHA/size, backup DB/media, restore drill và kiểm tra app trước activate; giữ JAR trước và phục hồi nếu release mới không healthy. Media/database ngoài JAR được giữ. Không yêu cầu bridge. S3 là dữ liệu bên ngoài backup local và cần kế hoạch phục hồi bucket riêng.

Backup DB dùng pg_dump custom format, checksum, manifest và success marker. Báo cáo 03–04/10 có restore DB/media vào môi trường tách biệt và smoke đăng ký/catalog/tạo phiên với JAR mới/cũ. Lịch DB-only được ghi nhận lúc 02:30 giờ VPS; media snapshot nhất quán đi theo release, không bảo đảm cùng RPO với DB. Có bản sao off-VPS một lần trên HP, chưa chứng minh lịch off-VPS tự động. Báo cáo SQL restore ngày 04/10 kiểm tra hash/count mọi public table và invariant, nhưng phép thử đó riêng không restore media/Redis/app. Rollback JAR không rollback schema hoặc khôi phục object S3 đã xóa. Không gọi quy trình này là zero downtime.

GitHub Actions trong `.github/workflows/deploy.yml`:

1. PR vào main, push main, hoặc workflow_dispatch kích hoạt.
2. Test JS và build/verify Java với JDK17.
3. Chạy integration suite với matrix PostgreSQL 17.11/18.4 service container, Flyway từ schema trống, lưu report riêng.
4. Deploy chỉ ngoài PR và trên main, phụ thuộc test-build thành công.
5. SSH/SCP bằng secrets + known_hosts; preflight/backup/restore gate, verify artifact, activate/rollback và health HTTPS.

CI deployment cập nhật JAR qua workflow, không cần đồng bộ bridge cho bản mới. Workflow có concurrency production và không cancel release đang chạy. Tài liệu đã xác minh cấu hình/harness local; chưa cung cấp link một run GitHub mới thành công trong lượt viết docs này.

## 20. Kiểm thử và bằng chứng hiệu năng

### 20.1. Kiểm thử hiện có

Nhóm test bao phủ tokenizer/scoring/classifier/assessment, vòng đời Practice, auth/CSRF/account, admin/content/media/personal, Room HTTP và concurrency, Listening grading/drafts/ownership, Coach clients, Conversation, History/Dashboard và UI regressions/copy/stale response.

Theo báo cáo `docs/reliability-evidence-2026-10-04.md`: regression tập trung **105/105 đạt**, không skip, trên PostgreSQL thật/Redis test riêng; regression mặc định **229 tổng, 0 lỗi, 31 skip**; contract generator **8/8 đạt**. Báo cáo ngày 03/10 có mốc JavaScript **88/88**. Các số này thuộc các suite/artifact theo ngày; không cộng chúng thành một tổng kiểm thử cho mã ngày 08/10 và không coi chúng là nghiệm thu toàn bộ file mới V26/S3 trong workspace.

Race/recovery ngày 04/10 gồm cạnh tranh 2/5/10/20 thao tác cho đăng ký, start/resume, check/hint/complete, admin stale mutation, token consume, grading claim/fencing, Conversation requestId và Room. Có tiến trình email worker bị kết thúc thật sau SMTP acceptance giả lập, HTTP provider timeout/429/500/JSON sai/body quá lớn, upgrade V22→V25 và SQL restore. Kết quả chỉ chứng minh các scenario đã chạy; không chứng minh hệ thống không còn mọi race.

Kiểm thử AI queue và S3 mới có trong source và hướng dẫn `scripts/test-messaging.ps1`, `docs/s3-audio.md`; lượt viết tài liệu này không chạy lại suite, không gọi provider/AWS và không khẳng định số test mới nhất. H2 không thay thế kiểm tra PostgreSQL về constraint/locking/JDBC.

### 20.2. Tối ưu backend đã đo

History Dictation và Listening có server-side pagination, Dashboard SQL aggregation + trend 8 mục, Recommendation limit và assessment evidence selection trước limit. Các tối ưu này không có nghĩa mọi query đã loại N+1: catalog/detail còn truy vấn theo lesson/câu và cần đo theo dữ liệu thực.

Benchmark local 10.000 phiên synthetic của **một user**, 5 warm-up + 30 request tuần tự qua MockMvc:

| Endpoint | p95 trước | p95 sau | Giảm |
|---|---:|---:|---:|
| History | 141,306 ms | 63,758 ms | khoảng 54,9% |
| Dashboard | 220,574 ms | 145,792 ms | khoảng 33,9% |

History JSON giảm khoảng 3,13 MB xuống 3,16 KB; entity hydrate từ 10.021 xuống 21. Dashboard JSON từ 1,41 MB xuống 2,62 KB; entity từ 10.021 xuống 29. Query count không nhất thiết giảm; lợi ích chính là payload/hydration nhỏ và đọc có giới hạn.

Không đo Internet/browser latency, login, TTS, AI, CPU/RAM hoặc concurrent users. Dataset không seed đầy đủ word telemetry. Không được viết “chịu tải 10.000 người dùng đồng thời”. Tài liệu số liệu/harness: `docs/backend-performance-2026-10-01.md`; benchmark opt-in `BackendBenchmarkTest`.

### 20.3. Tải đồng thời và giới hạn bằng chứng

- **03/10 buổi tối:** artifact `E39A...` trong báo cáo capacity đạt warm mixed 100/200/300 VU, mỗi mức 120 giây, 80% reader/20% practice. p95 lần lượt 32,93/27,19/46,91 ms, không lỗi HTTP/nghiệp vụ. Fixture/think time/generator dùng VPS chung không đại diện mọi người dùng thật.
- **500 VU:** guard CPU dừng lượt chạy; transport errors xuất hiện sau dừng. Không coi đây là số người tối đa hoặc kết quả đạt. Không chạy 700/1.000.
- **04/10 với bộ evidence khác:** smoke HP 20 VU cấu hình 60 giây, 178/178 hành trình đạt, work p95 82,54 ms/p99 334,35 ms; app/DB/generator cùng máy, dùng fake provider. Có reads/Dictation/Listening/Room, không có WebRTC media thật hoặc AI capacity thật.
- **Chưa đạt mục tiêu dài:** chiến dịch 300 VU 30–60 phút, soak ≥2 giờ, spike/stress/recovery sau stress chưa xác minh. Preflight VPS ngày 04/10 từ chối do RAM/paging và số mẫu; không khởi chạy tải. Kết quả artifact cũ 03/10 không thay thế nghiệm thu artifact JWT/S3/queue mới.
- **AI thật:** báo cáo ngày 03/10 có một hành trình Conversation 4 turns đạt 28/28 checks, work p95 35,55 giây, toàn hành trình khoảng 156 giây. Đây là smoke ít mẫu có hỗ trợ dịch; không phải throughput, p95 ổn định hoặc đánh giá năng lực người học.

### 20.4. Giám sát và phục hồi

Theo `ops/observability/README.md`, ngày 03/10 đã cài service Prometheus trên VPS, LocalService, loopback `127.0.0.1:19190`, scrape UP và 7 rules hoạt động. Grafana 12 panels đã thử với datasource/query thật nhưng chưa là service thường trực; chưa có receiver chứng minh cảnh báo gửi tới người vận hành. Một số nhật ký sớm hơn ghi chưa cài daemon; mốc cài service trong README observability cập nhật trạng thái đó.

Tracing sampling mặc định 10%, export tắt; cấu hình OTLP queue/batch/timeout có giới hạn. Có kiểm thử export/provider span, chưa chứng minh collector storage và HTTP/JDBC/durable job tracing toàn luồng. Log ECS JSON có rotation 10 MB/file, tổng 100 MB, 7 ngày. Health UP chỉ là snapshot phụ thuộc lõi, không là chứng nhận SLA hoặc chất lượng AI/audio.

## 21. Những việc chưa có hoặc cần tiếp tục xác minh

- CEFR calibration bằng placement test/dữ liệu người thật; độ tin cậy hiện là heuristic coverage.
- Chấm phát âm/STT và phoneme alignment runtime.
- Phát hiện semantic duplicate và đánh giá chất lượng câu hỏi tự động trên tập eval đủ lớn.
- Spaced repetition cho ngân hàng lỗi.
- Hạ tầng nhiều instance hoàn chỉnh: Room state/routing còn trong JVM, provider concurrency của nhiều luồng chưa có ngân sách toàn cluster. Redis session/rate limit và job queue mới chỉ giải quyết từng phần.
- Observability còn thiếu receiver alert, Grafana thường trực và collector tracing toàn luồng; Prometheus/metrics/JSON logs và kiểm thử 12 panels đã có.
- MFA/OAuth/SSO, vận hành xoay secret theo quy trình, quota chi phí AI/token tổng và giám sát abuse thực tế. Rate limit auth/AI/upload đã có, không phải tính năng chưa làm.
- Phục hồi toàn hệ thống gồm S3/Redis/config, backup off-VPS tự động và RPO/RTO được thống nhất. Restore DB/media/app và DB-only backup theo lịch đã có bằng chứng từng đợt.
- Capacity dài trên artifact hiện tại, generator riêng, workload audio/WebRTC thật và latency AI/audio đủ mẫu. Không ngoại suy từ smoke hoặc 120 giây mixed sang cam kết dài hạn.
- CDN, quản lý vòng đời/backup/versioning bucket, provenance và giấy phép corpus khi phát hành rộng. S3 upload riêng tư đã có; không đề xuất triển khai S3 như thể chưa tồn tại.
- Đo query catalog/detail và phân bố dữ liệu thực; tối ưu khi có bottleneck. History Dictation/Listening đã có phân trang.
- Mục tiêu kinh doanh, phản hồi người học, retention, ngân sách và ưu tiên phát triển chưa được tài liệu xác minh; cần chủ dự án cung cấp.

Đây là danh sách giới hạn/khả năng cải thiện, **không phải tính năng đang hoạt động** và không phải cam kết phải bổ sung tất cả ngay.

## 22. Bản đồ mã nguồn cho ChatGPT tiếp tục làm việc

| Chủ đề | File/thư mục |
|---|---|
| Dependency/version | `pom.xml` |
| Cấu hình mặc định | `src/main/resources/application.yml`, `.env.example` |
| Security/ownership principal | `config/SecurityConfig.java`, `security/JwtTokenService.java`, `security/RedisTokenStore.java`, `security/AccountStateFilter.java`, `security/DictaLabPrincipal.java` |
| Rate limit | `security/RequestRateLimitFilter.java`, Redis Lua và test security |
| Dictation | `service/PracticeService.java`, `domain/PracticeSession.java`, `domain/WordAttempt.java` |
| Thuật toán | `TranscriptTokenizer`, `ScoringService`, `ListeningErrorClassifier`, `ListeningAssessmentService`, `RecommendationService` trong service |
| Catalog/review/dashboard | `CatalogService`, `PreviewVocabularyService`, `HistoryService`, `DashboardService`, repositories |
| Coach | `CoachService`, `CoachAnalysis`, `GeminiClient`, `static/js/coach.js` |
| Listening | `ListeningComprehensionService`, `ListeningComprehensionController`, `ListeningContentController`, `static/js/listening.js` |
| Conversation | `ConversationService`, `ConversationController`, `ConversationProperties`, `CartesiaClient`, `static/js/conversation.js` |
| Queue | `service/ListeningGradingQueue.java`, `service/AiBackgroundJobs.java`, `config/ListeningGradingRabbitConfig.java`, `config/AiRabbitConfig.java`, `config/AiQueueProperties.java` |
| Provider protection | `service/ProviderGuard.java`, `service/BoundedProviderBody.java` |
| Accounts/support | `AccountService`, `AccountManagementService`, `AuthController`, `AccountManagementController`, `SupportController`, `static/js/account.js` |
| Admin | `AdminContentService`, `AdminQueryService`, `MediaStorageService`, `AdminContentController`, `static/js/admin-ui.js` |
| S3 | `config/S3MediaConfig.java`, `web/MediaController.java`, `service/MediaStorageService.java`, `docs/s3-audio.md` |
| Email worker | `service/EmailOutbox.java`, `service/AccountManagementService.java` |
| Rooms | `src/main/java/com/dictalab/room/`, `config/WebSocketConfig.java`, room functions trong app.js |
| DB | `src/main/resources/db/migration/`, các version hiện có từ V1 tới V26, không có file V24 |
| Frontend | `src/main/resources/static/index.html`, `admin.html`, `css/app.css`, `js/app.js` và module JS |
| Offline audio | `scripts/split-elllo-sentences.py`, các script ELLLO/preview/personal |
| Deploy | `scripts/deploy-to-vps.ps1`, `update-windows-vps.ps1`, `backup-windows-vps.ps1`, `enable-coach-vps.ps1` |
| HTTPS | `scripts/https/` |
| Bridge lịch sử/rollback JAR cũ | `start-coach-bridge-vps.ps1`, `update-coach-bridge-vps.ps1`, `apply-chatgpt-web-patch.ps1`, `chatgpt-web-compat.patch` |
| CI/tests | `.github/workflows/deploy.yml`, `src/test/java/`, `src/test/js/`, `scripts/test-postgres.ps1` |
| Load/recovery | `scripts/load/`, `scripts/run-capacity-check.ps1`, `docs/reliability-evidence-2026-10-04.md`, `docs/reliability-evidence-runbook.md` |
| Quan sát | `ops/observability/`, `docs/operations-runbook.md`, `scripts/install-prometheus-vps.ps1` |
| Công cụ phụ độc lập | `tools/lesson-player/`; không thuộc backend/luồng học DictaLab |

Tên file Java viết ngắn trong bảng nằm dưới `src/main/java/com/dictalab/`; static paths nằm dưới `src/main/resources/`. Tài liệu thiết kế/audit/benchmark khác là bằng chứng theo thời điểm, không ghi đè mô tả hiện tại này khi mâu thuẫn.

### 22.1. Công cụ phụ trong repository

`tools/lesson-player/` là công cụ Python/Tkinter/PyAutoGUI cho phiên Windows tương tác đang mở website học bên ngoài DictaLab. Gửi ảnh màn hình tới model vision qua Gemini để nhận diện nút phát/phát lại; có chế độ chỉ nhận diện, phím F8, FAILSAFE và kiểm tra cửa sổ/chuột trước click. Không bấm next hoặc tự thay tiến độ. Khóa không lấy tự động từ `.env` DictaLab; ảnh màn hình có thể chứa thông tin ngoài bài học. Có test không gọi provider/không click; README ghi chưa kiểm chứng website/API thật. Đây là tiện ích độc lập, không phải tính năng của người học trên web DictaLab và không là phụ thuộc production.

## 23. Prompt đề xuất khi tải file này lên ChatGPT

> Đây là tài liệu hiện trạng toàn bộ website DictaLab của tôi, cập nhật 08/10/2026 từ workspace và các báo cáo có ngày. Hãy đọc toàn bộ, tóm tắt lại cách sản phẩm hoạt động để xác nhận bạn đã hiểu, rồi giúp tôi chọn hướng phát triển tiếp. Phân biệt Dictation, Listening, Conversation AI, Coach, Room và quản trị; phân biệt mã local với production đã được ghi nhận. Không đề xuất làm lại JWT/Redis, phân trang Listening, S3 hoặc rate limit như thể dự án chưa có chúng. Không coi CEFR heuristic là chứng nhận hoặc benchmark là SLA. Trước khi chốt roadmap, hỏi tối đa 5 câu về mục tiêu, người dùng, phản hồi thực tế, ngân sách và thời gian. Sau đó đưa 3 hướng ưu tiên; mỗi hướng nêu vấn đề được giải quyết, giá trị, module/dữ liệu bị ảnh hưởng, phiên bản nhỏ nhất, rủi ro, kiểm thử và tiêu chí đo thành công. Ưu tiên tái sử dụng code/dependency hiện có, giữ quyền dữ liệu/lịch sử/điểm lần đầu và kiểm soát gọi AI tính phí. Nếu thiếu thông tin, hỏi hoặc yêu cầu file liên quan thay vì suy đoán.

Nếu mục tiêu là CV, thêm:

> Hãy đánh giá dự án cho vị trí Backend Java, chỉ dùng bằng chứng có trong tài liệu. Chỉ ra điểm mạnh, giới hạn và 3 cải tiến ưu tiên. Viết bullet CV trung thực; không bịa số người dùng, throughput, CI run, chi phí AI hay chứng nhận chất lượng.

## 24. Các hướng để ChatGPT phân tích phát triển tiếp

Đây là các **giả thuyết phát triển**, chưa phải roadmap đã chọn hoặc tính năng đã triển khai. Chọn ưu tiên dựa trên mục tiêu và phản hồi người học, không cần làm tất cả.

| Hướng | Dữ liệu/chức năng có thể tái sử dụng | Phiên bản nhỏ để thảo luận | Cần đo hoặc xác minh |
|---|---|---|---|
| Ôn lỗi theo lịch | Error Bank, Coach evidence, audio gốc và lỗi lặp | Hàng đợi ôn theo hạn, trạng thái ôn riêng, không sửa điểm Dictation cũ | Tỷ lệ quay lại ôn, lỗi lần đầu ở bài mới tương đương |
| Lộ trình học rõ ràng | Recommendation, assessment, history và level | Mục tiêu tuần/ngày và bước tiếp theo phù hợp bằng chứng đã có | Tỷ lệ hoàn thành, quay lại học, hiểu được vì sao nhận gợi ý |
| Kiểm chứng chất lượng nghe/AI | Facts/evidence, rubric, hỗ trợ, admin revision | Tập eval có người kiểm duyệt cho sinh câu, chấm recall và transfer | Độ nhất quán người chấm/AI, lỗi schema, lỗi ý nghĩa, thiên lệch level |
| Mở rộng nội dung có kiểm duyệt | Admin draft/publish/copy, pipeline audio, preview, S3 | Quy trình nguồn/quyền dùng, QC transcript/mốc câu và kiểm duyệt trước publish | Độ phủ chủ đề/accent, lỗi nội dung, chi phí mỗi bài |
| Vận hành AI bền vững | ProviderGuard, V26 jobs, RabbitMQ, metrics | Nghiệm thu job nền trong môi trường tách biệt, thể hiện trạng thái chờ/retry rõ | Queue age, latency toàn luồng, call trùng, cost budget, khôi phục khi worker lỗi |
| Phát hành cho nhiều người học | JWT/Redis, DB constraints, backup, Prometheus | Chốt alert receiver, recovery S3/Redis, đo tải đúng artifact và workload | RPO/RTO, lỗi thực, p95 dưới tải dài, quyền dữ liệu khi concurrency |
| Luyện nghe và nói | Conversation tasks/facts và audio; chưa có STT/scoring nói | Nếu có nhu cầu thật, thử một luồng trả lời giọng nói với tiêu chí riêng | Quyền mic, privacy, nhận diện accent, độ tin cậy feedback và chi phí |

### 24.1. Những ràng buộc khi thiết kế thêm

- Dùng lại modular monolith và frontend hiện có nếu chúng còn đáp ứng; thay framework/tách microservice cần có vấn đề cụ thể và bằng chứng.
- Lịch ôn/lượt hỗ trợ/redo/shared phải giữ riêng với phép đo năng lực lần đầu. Không trộn ba điểm học thành một “CEFR chính thức”.
- Nội dung đã có lịch sử cần version/copy thay vì sửa transcript/facts/audio làm đổi kết quả cũ.
- Giữ CSRF, ownership, revision/lease, validation upload, quyền S3 và bảo vệ account; không bỏ gate để làm test hoặc deploy dễ hơn.
- Phân biệt retry an toàn trước khi gửi provider và trường hợp đã gửi nhưng chưa rõ kết quả; không hứa exactly-once cho API tính phí hoặc SMTP.
- Mỗi cải tiến logic đáng kể cần một kiểm tra chạy lại được. Không chạy load/provider thật vào production chỉ để thử ý tưởng.
- Các số tiền, số người dùng và thời hạn roadmap cần dữ liệu của chủ dự án hoặc tra cứu hiện hành; tài liệu này không có đủ thông tin để suy ra chúng.

### 24.2. Thông tin còn cần chủ dự án cung cấp

1. Dự án ưu tiên giúp người học thực tế, sản phẩm có doanh thu, đồ án hay portfolio Backend Java?
2. Nhóm người học ưu tiên đang ở level nào, học vì mục đích gì và đang gặp khó khăn gì?
3. Có bao nhiêu người sử dụng thực, họ thường bỏ dở ở bước nào, phản hồi nổi bật là gì?
4. Có bao nhiêu thời gian phát triển và ngân sách hạ tầng/AI/nội dung mỗi tháng?
5. Trong đợt phát triển tiếp theo, tiêu chí thành công là tiến bộ học tập, quay lại học, chất lượng nội dung hay độ ổn định kỹ thuật?
