# V5 thay V4.15 — bàn giao kích hoạt

Chủ dự án yêu cầu thay hoàn toàn V4.15 bằng V5 ngày 16/11/2026. V5 được chọn làm
pin mặc định để tạo bài mới; không thay nội dung prompt sau đợt đo 18 mẫu mỗi phiên bản.
Hash V5 vẫn là `b8026ffc1dabdb09393c3b419b41750ca51ece9a242363482e3a337f4a61697b`.

## Phạm vi đã chuyển

- `ScenarioAssessment.ACTIVE_PROMPT_HASH` và `scenario/workflow-v3.sha256` cùng ghim V5.
- Tạo ba định nghĩa APPROVED mới, không sửa hash/định nghĩa cũ:

| Chương trình | Phiên bản | Definition hash |
|---|---:|---|
| general-communication | 6 | `9961285d5a965e5259a8e8b001ab60d2ed678abe85cd91035639e03a40524cd4` |
| toeic-listening | 5 | `090b2adb50f8b88d1f897a30435e0824e5a152d1f92161d00f1f99a0afb1c27f` |
| ielts-listening | 5 | `c2461efdbe6ada3bc4240a039d18418897f05be4557bc5e1ec857436c3bb5fc1` |

- Catalog học viên chỉ mở ba chương trình V5 khi runtime duyệt đúng prompt và từng definition hash.
  Map runtime phải chứa ba khóa `general-communication:6`, `toeic-listening:5`, `ielts-listening:5`.
- V4.15 không thể được chọn lại bằng cờ local preview. Pin runtime V4.15 không mở chương trình V5.
- Evaluation Studio và API đánh giá chỉ cho chạy mới V5.0. Snapshot/record V4.15 và các lần chạy cũ
  vẫn đọc được; gửi mới một hash lịch sử bị từ chối trước inference.
- Mục tiêu đã lưu ở chương trình APPROVED cũ được tái sử dụng khi thang điểm và toàn bộ profile
  hoàn toàn giống chương trình APPROVED mới. Phản hồi lựa chọn và draft dùng version mới,
  không cập nhật ngầm dữ liệu mục tiêu, receipt hoặc lịch sử học. Preference DRAFT không tự nâng cấp.

## Vì sao vẫn có mã tương thích V4.15

Bài cũ lưu prompt hash, program version và các phần chấm riêng. Xóa prompt/định nghĩa cũ sẽ khiến
chúng không thể đọc/chấm đúng provenance, hoặc chấm bằng một khóa khác với lúc học.
Vì vậy V4.15 chỉ còn để xử lý bài/operation lịch sử và đọc bằng chứng; nó không còn là bản mặc định,
lựa chọn tạo bài học mới hoặc lựa chọn đánh giá mới. Không viết lại nội dung, điểm hoặc hash của bài cũ.
Các operation đã được chấp nhận trước chuyển phiên bản giữ snapshot riêng, không bị đổi prompt giữa chừng.

## Đã xác minh trên local

- Đã sao lưu riêng `core.env`, JAR và runtime receipt trước chuyển; không đưa credential vào Git.
- Core chạy profile `local`, tài khoản QA được xác minh đúng ID 14 và protected credential gốc.
- JAR đóng gói và receipt cùng hash V5; Core health UP, AI ready.
- API catalog học viên trả APPROVED cho đúng ba version mới; không mở ba version V4.15 cũ.
- API danh mục đánh giá trả duy nhất `v5.0` với đúng hash V5.
- Luồng người học thật: tài khoản Scenario Local QA đã phân tích yêu cầu và tạo thành công một bài
  ba hoạt động. Database payload và result metadata cùng ghi hash V5,
  `promptVersion=SCENARIO_MEANING_ASSESSMENT_V5_0`. Chỉ một lượt sinh được chấp nhận;
  không sinh lại khi chương trình đọc bằng chứng cần sửa tên cột.
- QA14 chưa có mục tiêu APPROVED tương thích nên phép thử lưu mục tiêu mới cho riêng tài khoản QA.
  Không thay mục tiêu tài khoản cá nhân. Các phiên QA tự tạo đều đã logout.
- Chính sách không hạn mức ứng dụng giữ nguyên; authentication, CSRF, ownership, validation,
  idempotency, provider quotas/fallback và emergency stop giữ nguyên.

Bằng chứng riêng: `C:/Users/HP/.dictalab/local/v5-activation-verification.json`,
`v5-learner-activation-smoke.json`, các bản sao lưu và log cùng thư mục. Không phát tán credential/runtime data.

## Kiểm tra và giới hạn

- Nhóm Java ScenarioProgram, ScenarioContent, ScenarioAssessment, ScenarioIntegration,
  ScenarioEngineIntegration, AiEvaluationStudio: 202/202 đạt, không bỏ qua.
- Bao gồm tạo/chấm/hoàn tất bài V5 qua mocked provider; giữ nguyên pin khi default đổi;
  từ chối quay về V4.15 qua preview; chỉ carry preference tương thích, không ghi đè bản lưu.
- Kiểm tra hash: mọi định nghĩa cũ giữ nguyên; ba định nghĩa mới chỉ thêm cuối catalog.
  `scenario-prompts.json` và kho lịch sử byte-for-byte giữ nguyên so với bản đã đo.
- Lượt Python toàn dịch vụ đầu có 940 đạt, 27 bỏ qua, hai assertion lịch sử còn đòi pin mặc định V4.15.
  Đã đổi hai assertion đó sang V5; chạy lại toàn dịch vụ: **942 đạt, 27 bỏ qua**, không lỗi.
- Nhóm tools/ai-eval, model-serving và ba nhóm training theo CI: **252 đạt**.
- UTF-8: 21 file sửa đạt; diff check đạt.
- Kiểm tra UTF-8 và diff áp dụng cho toàn bộ file sửa. Không thay prompt hoặc bỏ validator để qua test.

V5 vẫn còn các lỗi ngữ nghĩa/phạm vi chấm đã ghi trong
[báo cáo 18 mẫu mỗi prompt](scenario-v415-v5-18-per-prompt-20261116.md) và
[kiểm thử V5 ban đầu](scenario-prompts-v5-verification.md). Quyết định kích hoạt không chứng minh đã sửa chúng.

## Production

Chưa triển khai production. Preflight SSH `54.254.221.170:22` ngày 16/11/2026 timeout;
chưa xác minh runtime, AWS backup/restore hoặc dung lượng thực tế máy chủ.
Không đổi `PRODUCTION_DEPLOY_ENABLED`, không merge main hoặc cập nhật máy chủ ngoài established main release flow.
Source và local đã chuyển V5 không phải bằng chứng website production đã chuyển.
Để phát hành cần main CI/release gates và xác minh Core, AI API/worker, Progress cùng các workflow nghiệp vụ.
