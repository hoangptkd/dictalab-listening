# Cá nhân hóa AI Conversation — triển khai ngày 02/11/2026

## Phạm vi đã làm

Nâng cấp `/conversation`, dùng lại Scenario Engine, lịch sử bài cũ, gợi ý 24 tình huống thuộc 6 nhóm và các gate hiện có. Không tạo thêm mục điều hướng.

1. Người dùng chọn TOEIC Listening, IELTS Listening hoặc giao tiếp và mức mục tiêu. Giao tiếp dùng sáu mức CEFR A1–C2. Không hỏi mức hiện tại hoặc kỹ năng cần cải thiện.
2. Lưu lựa chọn trên server theo tài khoản/chương trình; optimistic version và idempotency bảo vệ khi lưu lại hoặc mở nhiều tab.
3. Khi tạo bản nháp, Core chụp mục tiêu, thang điểm, profile đầy đủ, nguồn dữ liệu và phiên bản chính sách. Kỹ năng mặc định là hiểu ý chính (`main-idea`), mức hiện tại UNKNOWN. Những giá trị mức hiện tại/kỹ năng lưu từ UI cũ không điều khiển bài mới. Sửa lựa chọn về sau không thay đổi bản nháp/bài/remix cũ.
4. Có thể để trống mô tả để AI chọn bối cảnh theo mục tiêu. Ô mô tả vẫn cho phép tùy chỉnh; gợi ý chỉ điền vào ô này.
5. Truyền snapshot vào EXTRACT, GENERATE, QUESTIONS, REVIEW_QUESTIONS, TRANSLATE, REVIEW và GRADE. GRADE giữ nguyên chỉ dẫn V4; chấp nhận câu trả lời tiếng Anh, tiếng Việt và trộn hai ngôn ngữ.
6. GENERATE chỉ viết hội thoại. Diễn biến theo thể loại trao đổi thông tin, thảo luận, giải thích, trải nghiệm, phỏng vấn, giải quyết vấn đề hoặc giao dịch; không ép mọi bài phải chốt một thỏa thuận.
7. QUESTIONS trích ý và viết câu hỏi/đáp án/bằng chứng trong một bước sau hội thoại. Hỗ trợ chi tiết, ý chính, lý do, mục đích, quan điểm và suy luận có căn cứ. Ít nhất một câu kiểm tra kỹ năng đã chọn.
8. REVIEW kiểm tra thêm profile và mục tiêu. Mọi gate vẫn fail closed; reviewer đồng ý không thể vượt gate độ dài, số lượng hoặc bằng chứng nguyên văn.

## Độ khó và giới hạn của bằng chứng

Mục tiêu là Listening, không tự quy đổi từ Overall/điểm tổng. Bài mới chọn profile theo mục tiêu được hiển thị. Bài và remix đã có giữ nguyên snapshot lịch sử.

| Chương trình | Thang mục tiêu | Profile luyện |
| --- | --- | --- |
| TOEIC Listening | 5–495 | Có hỗ trợ: 5–250; tiêu chuẩn: 255–375; thử thách: 380–495 |
| IELTS Listening | 0–9 | Có hỗ trợ: 0–5; tiêu chuẩn: 5.5–6.5; thử thách: 7–9 |
| Giao tiếp (phiên bản 3) | CEFR A1, A2, B1, B2, C1, C2 | Một profile riêng cho từng mức |

Các nhóm TOEIC/IELTS là nhóm luyện nội bộ tạm thời, **chưa được hiệu chuẩn sư phạm**. Giao tiếp dùng profile riêng cho từng mức CEFR, không quy sáu mức thành ba nhóm và không tự quy đổi mức giao tiếp cũ sang CEFR. Tham khảo [khung CEFR của Council of Europe](https://www.coe.int/en/web/common-european-framework-reference-languages/introduction-and-context). Nội dung sinh chưa được chứng nhận tương đương mức CEFR/band. Profile điều khiển từ vựng, cú pháp, mật độ thông tin, chính sách suy luận và ngân sách từ; không chỉ số từ. Tốc độ/pause trong profile là cấu hình cho giai đoạn sau; TTS hiện dùng recipe cũ.

Layout hiện tại giữ ba activity, mỗi activity 2–6 segment và hai câu hỏi. Đây là luyện kỹ năng theo mục tiêu, chưa phải định dạng đề thi TOEIC/IELTS thật. Profile thấp không mở suy luận; UI chỉ cho chọn kỹ năng được profile hỗ trợ.

## API và dữ liệu

- `GET /api/v2/scenarios/catalog`: bổ sung `practicePrograms`, profile đầy đủ và trạng thái mở tạo bài từng chương trình.
- `GET /api/v2/scenarios/learning-preferences`: đọc lựa chọn của tài khoản đăng nhập.
- `POST /api/v2/scenarios/learning-preferences`: lưu lựa chọn; cần CSRF và `Idempotency-Key` UUID.
- `POST /api/v2/scenarios/drafts`: giao tiếp dùng programVersion=3, TOEIC/IELTS dùng 2; Core tự chọn profile từ mục tiêu đã lưu. UI chặn tạo khi lựa chọn đang sửa chưa lưu. Khi nâng từ giao tiếp v2, UI yêu cầu lưu mức CEFR, dùng đúng version hiện có của preference để tránh xung đột giả.
- Các API session, answer, audio, history và remix tiếp tục dùng luồng hiện có.
- Migration V50 thêm bảng `scenario_learning_preferences` và cột nullable `scenario_drafts.learning_context_json`. Không backfill, không sửa bài cũ.
- Giữ nguyên catalog program v1, định nghĩa giao tiếp v2, prompt pin v1 và serialization job v1. Thêm giao tiếp v3; catalog chỉ hiện bản mới nhất của mỗi chương trình. Job cá nhân hóa vẫn là `scenario-job.v2`, với pin schema mới hỗ trợ CEFR.

Ví dụ request lưu mục tiêu:

```json
{
  "programCode": "ielts-listening",
  "programVersion": 2,
  "target": {"scaleId": "ielts-listening-band", "component": "LISTENING", "value": "6.5"},
  "currentValue": "",
  "focusSkill": "main-idea",
  "expectedVersion": 0
}
```

Hai field `currentValue` và `focusSkill` giữ trong API để tương thích client cũ; UI gửi `""` và `main-idea`, không còn ô chọn. Draft mới luôn dùng mục tiêu + main-idea, không dùng lựa chọn ẩn từ client cũ. Snapshot mức hiện tại là null, provenance UNKNOWN. Xung đột version trả 409. Mục tiêu Overall, điểm ngoài thang, bước điểm sai hoặc kỹ năng ngoài profile vẫn bị từ chối.

## Bật preview local

Cả ba chương trình mới giữ trạng thái DRAFT. Không tự tạo bằng chứng duyệt hay mở production từ các test giả lập. Preview yêu cầu đồng thời profile `local`, không có `prod`/`production`, bind loopback, user ID cụ thể, AI đã cấu hình và đúng pin prompt/program.

Đặt trong cấu hình runtime riêng ngoài Git, ghép với cấu hình hiện có:

```yaml
server:
  address: 127.0.0.1
dictalab:
  scenario:
    enabled: true
    engine:
      enabled: true
      local-preview-enabled: true
      local-preview-users: [123] # Thay bằng ID tài khoản được phép thử.
      approved-prompt-hash: bd0e89b4391becdf84e4389595ff6fa9a4368f4323ac50eebaaf4dffb5b4c2aa
      approved-programs:
        "[general-communication:3]": 2476c2ed9f4faefe24bd312a020d9b19fdc8bdc83336392921a8cd66af91160c
        "[toeic-listening:2]": a05f4049cdf816773342b65d3ddb1b6b107504cf685c8efc9c292bf8a9df684c
        "[ielts-listening:2]": bb2ec5cd120373be1384cea529187c0bb289f6d99064ed3c85a2c346c7c33b87
```

Worker cần `DICTALAB_SCENARIO_ENABLED=true`, cấu hình provider hợp lệ và cùng mã nguồn/pin. Dừng nhận job rồi để job theo pin schema cũ kết thúc trước khi nâng đồng thời Core/worker sang pin mới. Không đặt khóa vào request UI, report hoặc Git. Cấu hình trên không tự khởi động server và không chứng minh local đang chạy bản mới.

## Tracing và kiểm tra

Langfuse dùng instrumentation sẵn có, bổ sung program/version, focus, profile, quality policy, calibration status và hash của learning context. Nội dung vẫn theo cơ chế opt-in/redaction hiện có. Có test bằng SDK thật với exporter bộ nhớ, không gửi dữ liệu ra mạng. Chưa xác minh trace mới trên Langfuse Cloud trong lần triển khai này.

Các kiểm tra cần chạy:

```powershell
mvn -q '-Dtest=Scenario*Test' test
mvn -q '-Dtest=ScenarioMigrationIntegrationTest' '-Ddictalab.testcontainers=true' test
node --test src/test/js/*.cjs
```

Trong `services/ai-generation`:

```powershell
python -m pytest tests/test_scenario_workflow.py tests/test_scenario_contract.py tests/test_observability.py tests/test_jobs.py -q
```

Kiểm tra PostgreSQL dùng schema riêng trong container dùng một lần: cài mới, nâng từ V47 giữ legacy session, nâng từ V49 giữ nguyên draft và context null. Kiểm thử workflow dùng phản hồi model giả lập để xác minh contract và gate; không dùng kết quả đó để tuyên bố chất lượng AI đã tăng.

Kết quả triển khai ban đầu: 99 kiểm thử Java Scenario (gồm migration PostgreSQL), 193 kiểm thử Python thuộc bốn bộ trên và 360 kiểm thử JavaScript đều đạt. Lần điều chỉnh giao diện/CEFR tiếp tục chạy các bộ Scenario Java, workflow/contract Python và toàn bộ JavaScript; thêm kiểm tra sáu mức CEFR, nâng preference v2→v3 và bỏ tác động của trường ẩn trên bài mới. Không thay schema database trong lần điều chỉnh này. Kiểm tra UTF-8 tiếng Việt và `git diff --check` đạt. Có cảnh báo tương thích/deprecation từ Python 3.14/Langfuse/Pydantic/Starlette; không có dependency mới được cài trong thay đổi này. Chưa kiểm tra giao diện bằng trình duyệt thật và chưa chạy benchmark model mới.

## Mở rộng và rollout tiếp theo

1. Benchmark nội dung thật theo từng chương trình/profile/kỹ năng, dùng lại corpus và lưu toàn bộ output, prompt/model/context hash. Chuyên gia duyệt chất lượng và reviewer false acceptance trước khi đổi trạng thái APPROVED.
2. Hiệu chuẩn profile và audio bằng dữ liệu thực; thử Listening riêng, không ước lượng band từ sáu câu hỏi tự sinh.
3. Thêm question type/blueprint và layout có version cho TOEIC/IELTS, gồm phương án nhiễu và dạng điền đáp án; không ép vào layout hiện tại.
4. Dùng lịch sử trả lời không trợ giúp để đề xuất mức và kỹ năng. Mọi thay đổi mức cần có provenance; không tự ghi đè mức người dùng khai bằng điểm chứng chỉ giả định.
5. Mở text chat rồi 1:1 voice trên session/context hiện có, bổ sung turn state và ngân sách audio khi triển khai thực sự.

Triển khai worker hỗ trợ cả v1/v2 trước Core. Chạy migration bổ sung, kiểm tra pin/capability rồi mở preview cho người thử. Chỉ mở production khi có artifact duyệt và cấu hình pin tương ứng. Rollback rollout bằng cấu hình, giữ bảng/cột và các artifact lịch sử; không rollback bằng xóa dữ liệu. Tắt admission cũng chặn thao tác AI mới trên bài v2, còn bài đã lưu vẫn đọc được; để các job đang chạy kết thúc trước khi hạ worker.
