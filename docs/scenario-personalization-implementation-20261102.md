# Cá nhân hóa AI Conversation — triển khai ngày 02/11/2026

## Phạm vi đã làm

Nâng cấp `/conversation`, dùng lại Scenario Engine, lịch sử bài cũ, gợi ý 24 tình huống thuộc 6 nhóm và các gate hiện có. Không tạo thêm mục điều hướng.

1. Người dùng chọn TOEIC Listening, IELTS Listening hoặc giao tiếp; nhập mục tiêu, mức hiện tại nếu biết và kỹ năng cần luyện.
2. Lưu lựa chọn trên server theo tài khoản/chương trình; optimistic version và idempotency bảo vệ khi lưu lại hoặc mở nhiều tab.
3. Khi tạo bản nháp, Core chụp mục tiêu, mức hiện tại, thang điểm, profile đầy đủ, kỹ năng, nguồn dữ liệu và phiên bản chính sách. Sửa lựa chọn về sau không thay đổi bản nháp/bài/remix cũ.
4. Có thể để trống mô tả để AI chọn bối cảnh theo mục tiêu. Ô mô tả vẫn cho phép tùy chỉnh; gợi ý chỉ điền vào ô này.
5. Truyền snapshot vào EXTRACT, GENERATE, QUESTIONS, REVIEW_QUESTIONS, TRANSLATE, REVIEW và GRADE. GRADE giữ nguyên chỉ dẫn V4; chấp nhận câu trả lời tiếng Anh, tiếng Việt và trộn hai ngôn ngữ.
6. GENERATE chỉ viết hội thoại. Diễn biến theo thể loại trao đổi thông tin, thảo luận, giải thích, trải nghiệm, phỏng vấn, giải quyết vấn đề hoặc giao dịch; không ép mọi bài phải chốt một thỏa thuận.
7. QUESTIONS trích ý và viết câu hỏi/đáp án/bằng chứng trong một bước sau hội thoại. Hỗ trợ chi tiết, ý chính, lý do, mục đích, quan điểm và suy luận có căn cứ. Ít nhất một câu kiểm tra kỹ năng đã chọn.
8. REVIEW kiểm tra thêm profile và mục tiêu. Mọi gate vẫn fail closed; reviewer đồng ý không thể vượt gate độ dài, số lượng hoặc bằng chứng nguyên văn.

## Độ khó và giới hạn của bằng chứng

Mục tiêu là Listening, không tự quy đổi từ Overall/điểm tổng. Mức hiện tại do người dùng khai, không phải kết quả đánh giá. Nếu có mức hiện tại, chọn profile theo mức đó; nếu chưa biết, chọn theo mục tiêu.

| Chương trình | Có hỗ trợ | Tiêu chuẩn | Thử thách |
| --- | --- | --- | --- |
| TOEIC Listening | 5–250 | 255–375 | 380–495 |
| IELTS Listening | 0–5 | 5.5–6.5 | 7–9 |
| Giao tiếp | supported | standard | challenge |

Đây là nhóm luyện nội bộ tạm thời, **chưa được hiệu chuẩn sư phạm**. Không tuyên bố bài sinh ra tương đương band chứng chỉ. Profile điều khiển từ vựng, cú pháp, mật độ thông tin, chính sách suy luận và ngân sách từ; không chỉ số từ. Tốc độ/pause trong profile là cấu hình cho giai đoạn sau; TTS hiện dùng recipe cũ.

Layout hiện tại giữ ba activity, mỗi activity 2–6 segment và hai câu hỏi. Đây là luyện kỹ năng theo mục tiêu, chưa phải định dạng đề thi TOEIC/IELTS thật. Profile thấp không mở suy luận; UI chỉ cho chọn kỹ năng được profile hỗ trợ.

## API và dữ liệu

- `GET /api/v2/scenarios/catalog`: bổ sung `practicePrograms`, profile đầy đủ và trạng thái mở tạo bài từng chương trình.
- `GET /api/v2/scenarios/learning-preferences`: đọc lựa chọn của tài khoản đăng nhập.
- `POST /api/v2/scenarios/learning-preferences`: lưu lựa chọn; cần CSRF và `Idempotency-Key` UUID.
- `POST /api/v2/scenarios/drafts`: dùng programVersion=2; Core tự chọn profile từ lựa chọn đã lưu. UI chặn tạo khi lựa chọn đang sửa chưa lưu.
- Các API session, answer, audio, history và remix tiếp tục dùng luồng hiện có.
- Migration V50 thêm bảng `scenario_learning_preferences` và cột nullable `scenario_drafts.learning_context_json`. Không backfill, không sửa bài cũ.
- Giữ nguyên catalog program v1, prompt pin v1 và serialization job v1. Phiên bản chương trình mới là 2; job mới là `scenario-job.v2`.

Ví dụ request lưu mục tiêu:

```json
{
  "programCode": "ielts-listening",
  "programVersion": 2,
  "target": {"scaleId": "ielts-listening-band", "component": "LISTENING", "value": "6.5"},
  "currentValue": "5.0",
  "focusSkill": "reason",
  "expectedVersion": 0
}
```

Mức hiện tại chưa biết truyền `""`; snapshot chuẩn hóa thành null và provenance UNKNOWN. Xung đột version trả 409. Mục tiêu Overall, điểm ngoài thang, bước điểm sai hoặc kỹ năng ngoài profile bị từ chối.

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
      approved-prompt-hash: b9c6479955e2db1573d3791c600c15c5b499f4dcd0d9280176754799f8d56a55
      approved-programs:
        "[general-communication:2]": 059372a412266fdc258c9149a21983b85fff81035eedb18993802c5413449423
        "[toeic-listening:2]": a05f4049cdf816773342b65d3ddb1b6b107504cf685c8efc9c292bf8a9df684c
        "[ielts-listening:2]": bb2ec5cd120373be1384cea529187c0bb289f6d99064ed3c85a2c346c7c33b87
```

Worker cần `DICTALAB_SCENARIO_ENABLED=true`, cấu hình provider hợp lệ và cùng mã nguồn/pin. Không đặt khóa vào request UI, report hoặc Git. Cấu hình trên không tự khởi động server và không chứng minh local đang chạy bản mới.

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

Kết quả local đã xác minh: 99 kiểm thử Java Scenario (gồm migration PostgreSQL), 193 kiểm thử Python thuộc bốn bộ trên và 360 kiểm thử JavaScript đều đạt. Kiểm tra UTF-8 tiếng Việt và `git diff --check` đạt. Có cảnh báo tương thích/deprecation từ Python 3.14/Langfuse/Pydantic/Starlette; không có dependency mới được cài trong thay đổi này. Chưa kiểm tra giao diện bằng trình duyệt thật và chưa chạy benchmark model mới.

## Mở rộng và rollout tiếp theo

1. Benchmark nội dung thật theo từng chương trình/profile/kỹ năng, dùng lại corpus và lưu toàn bộ output, prompt/model/context hash. Chuyên gia duyệt chất lượng và reviewer false acceptance trước khi đổi trạng thái APPROVED.
2. Hiệu chuẩn profile và audio bằng dữ liệu thực; thử Listening riêng, không ước lượng band từ sáu câu hỏi tự sinh.
3. Thêm question type/blueprint và layout có version cho TOEIC/IELTS, gồm phương án nhiễu và dạng điền đáp án; không ép vào layout hiện tại.
4. Dùng lịch sử trả lời không trợ giúp để đề xuất mức và kỹ năng. Mọi thay đổi mức cần có provenance; không tự ghi đè mức người dùng khai bằng điểm chứng chỉ giả định.
5. Mở text chat rồi 1:1 voice trên session/context hiện có, bổ sung turn state và ngân sách audio khi triển khai thực sự.

Triển khai worker hỗ trợ cả v1/v2 trước Core. Chạy migration bổ sung, kiểm tra pin/capability rồi mở preview cho người thử. Chỉ mở production khi có artifact duyệt và cấu hình pin tương ứng. Rollback rollout bằng cấu hình, giữ bảng/cột và các artifact lịch sử; không rollback bằng xóa dữ liệu. Tắt admission cũng chặn thao tác AI mới trên bài v2, còn bài đã lưu vẫn đọc được; để các job đang chạy kết thúc trước khi hạ worker.
