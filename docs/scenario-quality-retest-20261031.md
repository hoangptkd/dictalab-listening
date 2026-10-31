# Kiểm thử lại Scenario: EXTRACT, GENERATE và chấm Anh/Việt

Ngày kiểm tra: 31/10/2026, UTC+7.

## Quyết định sau đánh giá: hoàn tác thử nghiệm

Theo yêu cầu của chủ dự án, đã hoàn tác phần thử nghiệm chất lượng sau mốc 13:02.
Source trở lại luồng trước thử nghiệm (`9929ffa`, cùng source ứng dụng với `1330cb5`),
giữ riêng yêu cầu trả lời bằng Anh/Việt/trộn và các kiểm thử của yêu cầu đó.

- Có cải thiện cục bộ: mẫu tàu mới nêu giờ đến cụ thể; các mẫu được chấp nhận đạt
  giới hạn độ dài. Chưa chứng minh được cải thiện ổn định về nội dung tổng thể.
- Baseline qua gate 4/7, bản cuối 3/7. Gate và số lần gọi đã thay đổi, bản cuối có
  một ca hạn mức; không dùng hai tỷ lệ này để khẳng định mức giảm chất lượng thống kê.
- Reviewer vẫn bỏ sót nhầm vai và đáp án thiếu chi tiết. Một lần từng qua 7/7 gate
  không chứng minh nội dung đạt, vì đọc lại vẫn phát hiện thiếu giờ đến.
- Luồng một yêu cầu hợp lệ giảm từ tối đa 10 lượt AI (EXTRACT 2, GENERATE 8) về
  4 lượt (EXTRACT 1, GENERATE 3). Không thêm model call để đánh giá lại trong rollback.
- Đã gỡ phần chia nhỏ sinh bài, marker dịch, EXTRACT_REVIEW, program v2 chưa phát hành
  và migration V50 thử nghiệm; Python/Core cùng trở lại hợp đồng V1. Giữ gate V1,
  quyền truy cập, quota, validation, dữ liệu đã lưu và công cụ capture/replay ban đầu.
- Local database xác minh đang ở migration 49, không có operation PENDING/RUNNING.
  Không chạy migration xuống, sửa database hoặc restart dịch vụ ứng dụng.
- Bản source trước rollback gồm 25 tệp và patch đã sao lưu ngoài repository,
  kiểm CRC và SHA256 từng tệp, trong thư mục dự phòng đã ghi ở cuối báo cáo.
- Prompt pin sau rollback, gồm sửa chấm Anh/Việt:
  `0d40fae31e57eef602c6f1e8402ea6116f8beeee56ac011c7c3d018f7f0152c2`.
- Kiểm thử sau rollback: Python workflow/harness **32 passed**; UI **73 passed**;
  Java Scenario/budget/Conversation **142 tests, 140 passed, 2 skipped**, không
  failure/error. Đã clean build để loại class V50 cũ. UTF-8 đạt.
- Các instruction ngoài GRADE được đối chiếu khớp nguyên bản trước thử nghiệm;
  pin Python/Core khớp. Log nằm tại `data/scenario-local-20261031/rollback-*-20261031.log`.

**Rollback giảm phần phức tạp chưa chứng minh hiệu quả; không biến bản cũ thành
bản đã đạt chất lượng.** Các kết quả và fingerprint bên dưới là lịch sử trước rollback.

## Kết luận

**Chưa đạt chất lượng tổng thể để phát hành.** Kiểm thử tự động đều qua, nhưng
model thật vẫn tạo đầu ra không hợp lệ và AI review còn bỏ sót lỗi nội dung.
Các đáp án đúng bằng tiếng Anh, tiếng Việt và trộn hai ngôn ngữ đều được chấm
đúng trong mẫu đã kiểm tra. Chưa đủ bằng chứng rằng toàn bộ luồng chấm đã ổn định.

Đây là kiểm thử bản nguồn đang sửa trong workspace, không phải kiểm thử production.
Không sửa source ứng dụng, khởi động lại dịch vụ hoặc triển khai trong lần kiểm tra này.

## Phiên bản và phạm vi

- HEAD trước kiểm thử: `1033086c29dfafcdc3cdcbe7511d5200f036f0ec`.
- Workspace có các thay đổi chất lượng chưa commit; HEAD riêng lẻ không tái tạo
  đúng bản được kiểm tra. Bản nguồn thực tế được lưu trong từng gói bằng chứng.
- Workflow source SHA256:
  `afa9fa0cbc990ce4d4b83c595ed3b81ef8e4113cdd2cde9cf39a7110f8349030`.
- Prompt hash:
  `592f75c8180b2fdf1bd427c88ab3b9548265b6317bcc3487218e6e98df107d58`.
- Model ghi nhận trong phản hồi: `gemini-3.5-flash-lite`.
- Dữ liệu EXTRACT/GENERATE: bộ synthetic 8 ca cũ, không đổi đầu vào hoặc tiêu chí.
  Dataset hash: `d641b1b129a031cb7cbe3ec6123c0236dd25a7e9b72e82f9c5c5d7dc785dc377`.
- Dữ liệu GRADE: 12 ca synthetic, cùng một bài đặt bánh, cố định đáp án kỳ vọng
  trước khi gọi model. Không dùng dữ liệu người học hoặc tập test huấn luyện.
- Chỉ chạy một capture mới `quality-candidate-v2-20` và một lượt GRADE 12 ca.
  Không gọi lại để thay thế ca lỗi hoặc chọn kết quả đẹp hơn.

## Kiểm thử tự động mới chạy

| Nhóm | Kết quả |
| --- | --- |
| Python workflow và evaluation harness | 137 passed, 2 cảnh báo dependency |
| JavaScript Conversation, Scenario, UI regression | 74 passed |
| Java ScenarioContent, ScenarioEngineIntegration, ConversationIntegration | 87 tests, 85 passed, 2 skipped, không failure/error |

Các kiểm thử tự động xác minh hợp đồng, validation và hồi quy. Chúng không chứng
minh rằng model thật luôn sinh đúng nghĩa. Hai ca Java skipped không được tính là pass.

## EXTRACT/GENERATE bằng model thật

EXTRACT qua gate 8/8: 7 yêu cầu được phép; yêu cầu giả danh ngân hàng bị từ chối,
không gọi GENERATE. Đây là kết quả gate của mẫu, không phải bảo đảm ngữ nghĩa toàn diện.

| Tình huống | EXTRACT | GENERATE |
| --- | --- | --- |
| Gọi món không đậu phộng | Qua | Từ chối: `answer marker outside English evidence` |
| Lỡ tàu, đến trước 20 giờ | Qua | Qua gate |
| Đổi áo sai cỡ | Qua | Từ chối: `conflicting translated answer markers` |
| Đổi lịch phỏng vấn | Qua | Qua gate; đọc lại phát hiện lỗi vai trò trong câu hỏi |
| Gửi vali sau trả phòng | Qua | `QuotaUnavailable`; chưa có kết quả đầy đủ |
| Cầu lông khi trời mưa | Qua | Qua gate; đọc lại phát hiện lỗi vai trò trong câu hỏi |
| Hàng xóm mới | Qua | Từ chối: `answer marker outside English evidence` |
| Lừa lấy mật khẩu/OTP | Từ chối đúng | Không gọi |

Tổng: **3/7 bài qua gate**, 3 bài bị gate chặn, 1 bài chưa hoàn tất vì hạn mức.
`acceptance.passed=false`. Không tính lỗi hạn mức thành lỗi nội dung của model.

Đọc lại ba bài qua gate còn thấy:

1. Phỏng vấn, `a3/q2`: câu hỏi dùng từ **“Hành khách”** cho ứng viên.
2. Cầu lông, `a3/q1`: cũng gọi người chơi là **“hành khách”**.
3. Cả hai phản hồi REVIEW vẫn ghi `rolesConsistent=true`, `questionGrounded=true`
   và `valid=true`. Vì vậy `qualityPassed=true` chưa đủ để công nhận bài đạt.
4. Phỏng vấn, `a1/q2`: hỏi thời điểm lịch mới nhưng đáp án chuẩn chỉ là “chiều thứ Ba”,
   thiếu mốc 15 giờ đã có trong hội thoại. Câu hỏi/đáp án cần xác định rõ độ chi tiết.

Độ dài ba bài qua gate nằm trong giới hạn profile của từng bài; đó chỉ là một
tiêu chí cấu trúc, không bù được lỗi vai trò hoặc đáp án thiếu chi tiết.

## Chấm đáp án Anh/Việt bằng model thật

| Nhóm | Kết quả |
| --- | --- |
| Đúng, tiếng Anh | Qua, 2/2 ý `CAPTURED` |
| Đúng, tiếng Việt | Qua, 2/2 ý `CAPTURED` |
| Đúng, trộn Anh/Việt | Qua, 2/2 ý `CAPTURED` |
| Đúng, diễn đạt lại tiếng Anh | Qua, 2/2 ý `CAPTURED` |
| Đúng, diễn đạt lại tiếng Việt | Qua, 2/2 ý `CAPTURED` |
| Sai, tiếng Việt | Qua kiểm thử: 2/2 ý `INCORRECT`, bằng chứng nguyên văn |
| Sai, tiếng Anh | Model trả `INCORRECT` nhưng bỏ trống bằng chứng; gate từ chối đúng |
| Sai, trộn Anh/Việt | Bị chặn hạn mức |
| Trống | Bị chặn hạn mức |
| Lạc đề | Bị chặn hạn mức |
| Prompt injection trong đáp án | Bị chặn hạn mức |
| Trả lời thiếu ý | Bị chặn hạn mức |

Tổng: **6/12 ca qua đúng toàn bộ kỳ vọng**, 1 ca có phản hồi không đạt hợp đồng
bằng chứng, 5 ca chưa có phản hồi vì `QuotaUnavailable`. Trong 5 ca trả lời đúng,
cả 5 đều được chấp nhận, bằng chứng giữ nguyên ngôn ngữ người trả lời.

Gate không cấm tiếng Anh. Ca sai bằng tiếng Anh thất bại vì `evidenceVi` rỗng,
không phải vì ngôn ngữ. Không nới hoặc bỏ gate để biến ca này thành pass.

## Bằng chứng và tái hiện không gọi model

Tất cả đường dẫn sau tương đối với repository, nằm trong thư mục `data/` đã ignore:

- `data/scenario-local-20261031/quality-candidate-v2-20/`: đầu vào, request,
  schema, options, raw response, wire outcome, lỗi, nguồn workflow/harness và hash.
- `data/scenario-local-20261031/quality-candidate-v2-20/derived/retest-report.json`: báo cáo EXTRACT/GENERATE.
- `data/scenario-local-20261031/quality-candidate-v2-20/derived/retest-replay.json`: replay offline.
  **14/15 phase tái hiện đúng**; phase gửi vali không có đầy đủ phản hồi do hạn mức,
  được đánh dấu `UNREPLAYABLE`, không tự gọi lại provider.
- `data/scenario-local-20261031/bilingual-live-retest-20261031/`: bộ GRADE cố định,
  nguồn runner, request/response và `summary.json`.
- `data/scenario-local-20261031/bilingual-live-retest-20261031/derived/offline-replay.json`: **7/7 ca có phản hồi
  đã parse tái hiện đúng**, gồm cả ca bị từ chối. Năm ca hạn mức giữ nguyên là chưa
  có phản hồi. Replay dùng **0 cuộc gọi mạng**.
- `data/scenario-local-20261031/retest-bilingual-{python,ui,java}-20261031.log`:
  log kiểm thử tự động.

Cả hai capture mới đã kiểm tra seal/hash. Có 53 lượt gọi gateway trong bộ sinh và
12 lượt GRADE; lượt bị quota chặn trước khi gửi không được coi là phản hồi model.

Đã sao lưu các candidate 16–20, bộ GRADE và log mới tại:
`C:/Users/HP/Desktop/English_dictition/data/scenario-evaluation-20261031/quality-candidates-v2-16-through-20-and-bilingual-retest-20261031.zip`.

- 1.159 tệp; CRC ZIP và SHA256 từng tệp đều khớp.
- Archive SHA256: `5deb793467018abf6fd9279534235bdb437a44a32ca4268477eee6eebbb5f2ab`.
- Bằng chứng riêng không đưa vào Git; báo cáo này không chứa khóa hoặc cấu hình bí mật.

## Các điểm cần sửa trước khi công nhận đạt

1. Làm nhất quán nguồn tiếng Anh và các marker đáp án dịch; dùng raw lỗi đã lưu
   để kiểm tra offline. Giữ nguyên gate chống đánh tráo bằng chứng.
2. Kiểm tra vai trò của nhân vật trong cả **câu hỏi**, và độ chi tiết của đáp án,
   thay vì tin cờ `valid` của reviewer. Dùng hai bài đã qua gate làm ca hồi quy.
3. Buộc GRADE trả trích dẫn nguyên văn không rỗng khi chấm câu trả lời có nội dung
   là `INCORRECT`, tương tự `CAPTURED`/`PARTIAL`. Giữ hỗ trợ Anh/Việt/trộn.
4. Tách trạng thái hạn mức khỏi đánh giá chất lượng, xử lý admission có giới hạn;
   không retry âm thầm sau khi provider đã nhận request.
5. Chỉ cần gọi model bổ sung cho các nhánh còn thiếu sau khi xử lý các điểm trên.
   Không cần sinh lại corpus hoặc bỏ bằng chứng cũ.

Chưa kiểm tra lại audio/TTS, giao diện trên trình duyệt hoặc production trong lượt này.
Chưa có căn cứ để báo triển khai hay phát hành thành công.
