# Kiểm thử lại Conversation AI — 12/11/2026

## Kết luận

**Chưa đạt nghiệm thu toàn luồng tạo bài mới.** Có ba vấn đề đã tái hiện cần xử lý theo thứ tự QA-01 → QA-02 → QA-03. Phần trả lời, chấm, chữa và hoàn thành trên một bài đã lưu hoạt động trong các trường hợp đã kiểm tra. Điều này không chứng minh pipeline sinh bài V3 hiện tại hoặc ứng viên V4.9 đã đạt chất lượng.

Chỉ kiểm tra; không sửa source, prompt, validator hay schema. Migration hiện có được thử qua cơ chế khởi động bình thường, thất bại và database vẫn ở V51. Sau đó dùng lại cấu hình local tắt Flyway vốn có để kiểm tra phần còn lại; đây không phải cách khắc phục được nghiệm thu.

## Môi trường và phạm vi

- Source được kiểm tra: `e375d23f8b4521b07fd5912879c738454e3d1e7c`, branch `codex/fix-scenario-provider-503-20261106`.
- Build JAR mới từ source hiện tại. Bốn asset giao diện phục vụ qua HTTP khớp hash với source.
- Local: `http://127.0.0.1:18082/conversation`; profile `local`, QA ID 14, database QA hiện có. Giữ xác thực, CSRF, ownership, quota nhà cung cấp và emergency stop.
- Pin cấu hình hiện tại: V3, `650193db73e2fb03c4f94a7ccb8f29fc9bc1c3f77c8391fabc0b0e07803f287e`. **V4.9 không được kích hoạt.** Bài lịch sử vẫn dùng provenance của chính bài đó.
- EXTRACT thật: một mô tả riêng và một đầu vào trống để kiểm tra AI đề xuất theo mục tiêu đã lưu. Cả hai đến được màn hình xác nhận; tạo session sau đó đều trả 409.
- Bài lịch sử program version 3 được luyện từ trạng thái ACTIVE đến COMPLETED, đủ ba hoạt động/sáu câu; dùng bản chép để kiểm tra nội dung và đánh dấu có hỗ trợ.
- Bài lịch sử program version 4 được mở tổng kết, chữa bài và phát đủ ba đoạn âm thanh đã lưu.
- Kiểm tra desktop và viewport 390/320 px; đây không phải kiểm thử thiết bị di động thật hoặc screen reader.

## Kết quả kiểm tra

| Phần | Kết quả | Giới hạn/bằng chứng |
| --- | --- | --- |
| JavaScript regression | 418 passed | Toàn bộ `src/test/js/*.test.cjs` |
| Java trọng tâm | 127 passed, 0 failed/skipped | Assessment, harness, engine integration, content, AI usage budget; không phải toàn bộ Maven suite |
| Python | Lần đầy đủ: 747 passed, 2 failed, 27 skipped. Hai test lỗi import chạy lại: 2 passed | Hai lỗi do thiếu repository root trong `PYTHONPATH`, không phải lỗi tính năng. Tổng 749 test khác nhau đạt qua hai lần chạy; 27 test chưa chạy. Không có sửa source để làm test xanh |
| Khởi động với migration | **Không đạt** | V52: `Missing scenario call constraint` |
| EXTRACT → xem trước | Có kết quả thật ở hai đầu vào | Chưa đủ mẫu để kết luận độ ổn định ngữ nghĩa |
| Xem trước → GENERATE | **Bị chặn trước dispatch** | POST sessions trả 409 ở hai bản nháp; chưa tạo được bài mới |
| Audio đã có sẵn | Phát hết 3/3 đoạn | Khoảng `ended → playing`: 58 ms và 55 ms; không tính khoảng lặng nằm sẵn trong WAV |
| Pause/resume/stop | Đã thao tác bằng bàn phím và nút dừng | Chỉ trên desktop Chromium; không suy ra mọi trình duyệt |
| Nháp câu trả lời | Giữ sau nút tải lại bài | Đã kiểm tra một câu tiếng Anh trước khi nộp |
| Chấm thật: đủ/thiếu ý | Đúng trong mẫu | Tiếng Anh đủ ý → hiểu đúng; tiếng Việt chỉ nêu cửa kính, thiếu vị trí đối diện sảnh → hiểu một phần |
| Chấm thật: sai/bỏ trống | Đúng trong mẫu | `Near the main exit.` → chưa đúng; bỏ trống → bỏ lỡ |
| Chấm thật: Anh–Việt trộn | Được chấp nhận | `Because he is in a hurry, tức là đang vội.` → hiểu đúng |
| Chữa → hoàn thành | Đạt trên bài lịch sử | DB xác nhận cả ba activity COMPLETED; tổng kết 3/6 câu hiểu đúng, ghi nhận có hỗ trợ |
| Luyện tương tự/lịch sử | Mở được | Mô tả luyện tiếp giữ chủ đề và gợi ý nghe chi tiết; tải thêm lịch sử hoạt động |
| Hai nhân vật/hai giọng | Snapshot có hai voice khác nhau | Chưa kiểm chứng tổng hợp giọng mới hoặc đánh giá chủ quan ngữ điệu |
| Lỗi đọc 503 rồi thử lại | Phục hồi, không có POST mới | **Fixture giả lập chỉ cho GET**, không phải sự cố Gemini thật |
| Mobile | Không tràn ngang trong hai màn đã đo | Viewport 390/320; không suy ra toàn bộ giao diện đều đạt accessibility |

Hai lỗi Python là `test_teacher_pool_keeps_approved_model_and_records_source_without_legacy_key` và `test_teacher_pool_admission_failure_writes_no_request_checkpoint`: thiếu import `training`. Chạy lại riêng hai test với repository root trong `PYTHONPATH` đều đạt. Lần thử `--import-mode=importlib` ban đầu còn không collect được các test có import chéo; kết quả lần đó không dùng làm đánh giá sản phẩm.

## QA-01 — P1: migration V52 không chạy trên database local thực tế

**Tác động:** khởi động theo migration bình thường thất bại. Dùng cấu hình cũ tắt Flyway thì server lên, nhưng tạo bài mới vẫn thất bại.

**Bằng chứng:**

1. JAR mới, bật Flyway và target 52: khởi động thất bại tại `V52__Scenario_call_envelope.migrate`, thông báo `Missing scenario call constraint`.
2. Với quyền database của ứng dụng, `pg_constraint` nhìn thấy `scenario_operation_budget_calls_reserved_check`, giới hạn `calls_reserved` từ 1 đến 7. Câu truy vấn ghép `information_schema.table_constraints` và `check_constraints` của migration trả **0 hàng**. Không phải constraint đã biến mất.
3. Schema history vẫn ở V51. `ScenarioOperations.callEnvelope` yêu cầu 9 calls cho V3; reservation không thể thỏa constraint 1–7 hiện tại.
4. Trình duyệt thật: xác nhận tạo bài từ hai bản nháp đều nhận HTTP 409. Không có session mới được nghiệm thu, không suy ra đây là Gemini lỗi hoặc model sinh kém.

**Vị trí:** `src/main/java/db/migration/V52__Scenario_call_envelope.java:18`, `src/main/java/com/dictalab/scenario/ScenarioOperations.java:51`, `src/main/java/com/dictalab/service/AiUsageBudget.java`.

**Hướng xử lý:** làm việc tìm constraint tương thích quyền và schema PostgreSQL thực tế; kiểm tra quyền DDL cần thiết trước triển khai. Giữ giới hạn hữu hạn 1–11 và dữ liệu cũ. Không sửa tay DB hoặc tắt validator để vượt lỗi. Kiểm chứng migration V51 → V52 trên bản database dùng cùng mô hình quyền, rồi tạo được một bài V3 hoàn chỉnh.

**Nghiệm thu:** migration thành công bằng đúng tài khoản vận hành; dữ liệu budget cũ giữ nguyên; constraint mới đúng; rollback giao dịch khi lỗi; tạo bài không trả conflict do reservation.

## QA-02 — P2: lỗi tạo bài bị báo thành xung đột phiên và giữ màn hình chờ

**Tái hiện:** từ xem trước hợp lệ → “Xác nhận và tạo bài” → POST sessions trả 409 như QA-01.

**Thực tế:** giao diện hiện “Phiên đã thay đổi. Câu trả lời nháp vẫn được giữ; bấm tải lại bài để tiếp tục”, nhưng vẫn ở “Đang chuẩn bị bài nghe / Đang lưu yêu cầu tạo hội thoại và câu hỏi…”. Chỉ có liên kết “Về mô tả đã lưu”, **không có nút tải lại bài được hướng dẫn**. Người dùng chưa hề vào màn trả lời.

**Nguyên nhân từ code:** `ApiExceptionHandler.java:59` dùng 409 chung cho lỗi toàn vẹn dữ liệu; `scenario.js:71` diễn giải mọi 409 như xung đột phiên. `scenario.js:303` thay preview bằng loading trước khi request tạo session được chấp nhận, nhưng nhánh lỗi không phục hồi preview đó.

**Hướng xử lý:** phân biệt mã lỗi tạo/admission với version conflict; khi chưa nhận được operation hợp lệ, kết thúc loading và phục hồi bản xem trước hoặc màn lỗi có hành động đúng. Dùng lại quy trình idempotency hiện có, không tự chạy lại tác vụ có kết quả chưa xác định.

**Nghiệm thu:** lỗi trước dispatch không còn spinner giả; mô tả và xác nhận không mất; hành động thử lại/mở bài đúng với trạng thái server; không tạo POST trả phí trùng.

**Bằng chứng riêng:** `03-generation.json`, `19-generation-repro.json`, ảnh `19-generation-failed-repro.png`, video `issue-001-002-repro.webm`. Video tái hiện thứ hai dùng đầu vào trống theo mục tiêu đã lưu, không phải bản lặp nguyên văn mô tả đầu tiên.

## QA-03 — P1: bài lịch sử không chuẩn bị được audio còn thiếu sau đổi pin chương trình

**Tái hiện:** mở bài “Hướng dẫn tìm quầy vé bảo tàng”, program version 3, khi cấu hình hiện tại mở generation cho program version 4/V3.

**Thực tế:** activity 1 có hai đoạn đã lưu nên còn nghe được. Activity 2 và 3 chưa có audio tương ứng: nút phát bị vô hiệu hóa, báo “Chương trình chưa mở để tạo bài. Audio đã lưu vẫn có thể nghe nếu nút đoạn được bật.” Bản chép và chấm bài vẫn dùng được; đã dùng chúng để kiểm tra luồng hoàn thành, **không coi đó là đã nghe đủ bài**.

**Nguyên nhân từ code:** `ScenarioEngine.session` dùng `requireProgram(user,draft)` để xác định `audioAllowed`; việc này kiểm tra điều kiện tạo nội dung của chương trình lịch sử theo pin hiện tại. `publicActivity` sau đó chỉ mở nút cho audio có cache đúng fingerprint. Grading có đường `requireGrade` riêng cho lịch sử, nhưng chuẩn bị audio chưa được tách tương ứng.

**Hướng xử lý:** định nghĩa quyền chuẩn bị TTS cho bài lịch sử dựa trên quyền sở hữu, recipe/provenance đã đóng băng, chương trình lịch sử còn được hỗ trợ và các gate TTS hiện có. Không buộc mở lại generation của prompt cũ. Nếu recipe lịch sử thiếu/không hỗ trợ, thông báo rõ trạng thái và hành động phục hồi được phép.

**Nghiệm thu:** sau đổi pin, bài cũ có recipe hợp lệ vẫn chuẩn bị được đoạn thiếu và phát liên tiếp; cache đã có được tái dùng; người khác không truy cập được; các gate quota, emergency stop và recipe validation vẫn hiệu lực.

**Bằng chứng:** `04-historical-audio.json`, `11-activity2.json`, `runtime.json`; ảnh `04-historical-audio-blocked.png`, `11-activity2-audio-blocked.png`.

## Năm lỗi ý nghĩa đã biết — vẫn chưa có bản sửa được nghiệm thu

Đây là các vấn đề từ fixture đóng băng và quá trình hiệu chỉnh trước đó. **Không gọi chúng là năm lỗi mới phát hiện qua bài vừa tạo**, vì GENERATE hiện bị chặn. Cũng không lấy bài lịch sử hoặc test hợp đồng làm bằng chứng model hiện tại hiểu đúng.

| Mã | Nội dung cần xử lý riêng | Trạng thái bằng chứng |
| --- | --- | --- |
| F1 | Câu hỏi chỉ hỏi lý do nhưng tiêu chí chấm đòi cả sở thích đã cho trong câu hỏi | Candidate V4.9 với Gemini 3.1 phát hiện trong mẫu chẩn đoán; chưa tích hợp/đạt nghiệm thu toàn pipeline |
| F2 | Hỏi lợi ích nhưng tiêu chí lại đòi nhắc hành động và thời lượng đã cho sẵn | V4.9/3.5 từng bỏ sót; đổi riêng sang 3.1 phát hiện trong mẫu nhỏ. Chưa chứng minh sửa tổng quát |
| F3 | `might fall behind` bị chuyển thành quan hệ chắc chắn gây tụt lại | Checker chẩn đoán V4.10/3.1 vẫn bỏ sót |
| F4 | Ý cha có sự đồng ý nhưng requiredParts không kiểm tra sự đồng ý | Checker chẩn đoán V4.10/3.1 vẫn bỏ sót |
| F5 | Một scoring part gộp hai lợi ích có thể thiếu độc lập | Checker chẩn đoán V4.10/3.1 vẫn bỏ sót |

Hai bộ chẩn đoán có tổng bốn POST mới, đều HTTP 200: V4.9/3.1 kiểm tra input lỗi và input đã sửa; V4.10/3.1 cũng kiểm tra hai input này. Không có retry/fallback trong các phép đo đó. Input sửa đều qua; điều đó **không bù cho false acceptance trên input lỗi**. Đây là phép đo riêng được lưu từ phần công việc trước khi chuyển sang full-flow audit, không phải prompt đang chạy trên local và không phải bài benchmark mới.

Giữ nguyên quyền trả lời tiếng Anh, tiếng Việt hoặc trộn; câu hỏi nhiều chi tiết hợp lệ. Cần đánh giá đủ/thiếu các ý được hỏi, không ép mọi câu hỏi chỉ có một ý. Reviewer ngữ nghĩa hiện là advisory; không khôi phục blanket rejection chỉ để làm số lỗi đẹp hơn.

Tham chiếu: [kế hoạch và checkpoint trước](conversation-assessment-staged-fix-plan-20261112.md).

## Quan sát cần đánh giá thêm

Ở mẫu EXTRACT đầu, input chỉ nói “cùng đồng nghiệp lên kế hoạch”, nhưng preview gán vai người học “Đồng nghiệp chủ trì kế hoạch” mà danh sách giả định không nêu rõ lựa chọn vai chủ trì. Một số dữ kiện đã cung cấp còn được lặp ở phần giả định. Ảnh `02-extract.png` giữ kết quả thực tế. Cần kiểm tra thêm trước khi coi đây là lỗi ổn định; không tự sửa prompt từ một mẫu.

Khoảng chuyển audio 55–58 ms chỉ chứng minh chuyển file nhanh khi các file đã sẵn sàng. Chưa đánh giá ngữ điệu xuyên lượt nói, khoảng lặng bên trong file hoặc TTS lạnh. Không kết luận vấn đề hội thoại thiếu tự nhiên đã được giải quyết.

## Gói bằng chứng và phần chưa xác minh

Gói local ngoài Git: `data/conversation-full-audit-20261112/` gồm ảnh, video tái hiện, snapshot nội dung, response JSON không chứa header xác thực, runtime/hash, `summary.json`, `checksums.json` và hướng dẫn chạy test trong báo cáo này. Không đưa private runtime config hoặc thông tin đăng nhập vào báo cáo/commit.

Log tự động ở `data/local-resume-20261106/audit-{package,java,js,python}-20261112*.log` và `audit-python-import-fix.log`. Hash của gói bằng chứng dùng để kiểm tra file, không phải chứng nhận chất lượng model.

Chưa xác minh: bài V3 mới từ đầu đến cuối; cold TTS sau thay phiên bản; recap/adaptive checks/parts grading trên bài V3 mới; model fallback trong lỗi provider thật; nhiều người dùng đồng thời; thiết bị mobile thật; screen reader; đánh giá chất giọng bởi người nghe. Phải chạy lại những phần phụ thuộc sau QA-01/QA-03, thay vì ghi toàn bộ hệ thống đã pass.

Đã đăng xuất chính phiên QA kiểm thử, đóng browser riêng và xóa credential profile tạm của phép thử. Database xác nhận không còn operation PENDING/RUNNING của QA 14 khi chốt. Local được giữ chạy để kiểm tra tiếp; không triển khai production.
