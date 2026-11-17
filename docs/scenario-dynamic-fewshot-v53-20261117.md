# Candidate V5.3: chọn ví dụ theo nhiệm vụ

Đã tích hợp bản thiết kế và 27 ví dụ hư cấu được chủ dự án duyệt ngày 17/11/2026. Đây là candidate để thử trong Studio local, chưa thay prompt mặc định của bài học. V5.2 vẫn là `5ac2eb0f3d04190bdc8e4dd5ecabf05d4f6d44f3d35edd2a7a99941a4006672b`; candidate V5.3 là `8dd20313f302ce5b79f50864f974470330fefba4e1a534890606107f5492c49f`.

## Luồng và bảo vệ

- Cả chín bước EXTRACT, GENERATE, QUESTIONS, REVIEW_QUESTIONS, TRANSLATE, REVIEW, GRADE, REPAIR_QUESTIONS và REPAIR_TRANSLATIONS dùng instruction nền V5.2, phần bổ sung đã duyệt và 0–3 ví dụ phù hợp. Chỉ candidate tách các khối EXAMPLES cố định; các nguyên tắc và phần trước/sau khối được giữ nguyên. V5.2 và các pin cũ không bị viết lại.
- Chọn bằng metadata/quy tắc, không thêm lượt AI, embedding hoặc dịch vụ. Lọc stage, mode, model/schema và phạm vi fragment trước; xếp nhiệm vụ/nguy cơ trước trình độ và chương trình. Không khớp thì dùng 0. Không nâng ví dụ vượt trình độ, không ép supported-inference và không ép câu thứ hai.
- Tách review nguồn với bản dịch; sửa lúc tạo với sửa khóa sau khi học viên trả lời; sửa mã dịch với sửa nghĩa. Review không dùng nhận xét lỗi trong verificationContext để chọn ví dụ; repair dùng chẩn đoán thực từ Harness. Mẫu PASS đối ứng được ưu tiên khi có cùng loại rủi ro.
- Trần khối ví dụ là ba mẫu và 6.000 byte UTF-8 đã serialize, gồm dấu phân cách; không cắt evidence hoặc object. Chỉ gửi id, outputScope, referenceInput, expectedOutput, không gửi ghi chú sửa hoặc nhãn đánh giá bài thật. Fragment không giảm số hoạt động/check cần trả.
- Candidate có artifact release riêng. Fingerprint chứa bank hash, selector version/hash và toàn bộ ID. Runtime xác minh fingerprint trước khi chèn. Kho không đọc được hoặc sai fingerprint: dùng instruction không kèm ví dụ, lưu lý do; không phát sinh lượt AI hoặc nới cổng chấp nhận. Giữ LF cho mã selector để hash tái hiện qua Windows/Linux.
- Dấu vết tách khỏi JSON bài thật: observer, metadata generation và result.exampleSelections có ID theo thứ tự, số byte, mode, lý do chọn/bỏ, thiếu ca sạch và fingerprint. Trường calls của Core không đổi. Chỉ các ID tin cậy trong artifact được miễn che như chuỗi bí mật; credential khác vẫn bị che.
- Giữ giới hạn 17 lượt tạo/7 lượt chấm, deadline, kiểm nguồn, evidence, khóa và bản dịch. Không đổi câu hỏi đã hiển thị hoặc xóa câu học viên đã trả lời. Core hiểu pin candidate, nhưng cấu hình tạo bài và pin mặc định local vẫn là V5.2.

## Kiểm tra offline

27 output qua đúng model Pydantic/phạm vi, provenance và evidence học viên; hai scoped merger và ba hàm tổng hợp finding được chạy lại từ bản soạn và đạt. Có test riêng cho GRADE, kho không đọc được, chế độ, giới hạn, chọn 0 và bảo vệ ID tin cậy. Core Scenario/Studio: **224 test, 0 failure/error, 1 skipped**; 14 output benchmark hoàn tất đều qua kiểm tra chuỗi Harness và nội dung Core. Cổng Core không chứng minh ngữ nghĩa đúng.

Bộ Python đầy đủ trên mã cuối đã đóng băng: **1.015 passed, 27 skipped** (359,94 giây). Lần chạy trước bị cổng chống đổi nguồn từ chối vì mã đang được sửa giữa lượt test; lần cuối chạy mới hoàn toàn và đạt. Kiểm tra UTF-8 và `git diff --check` đạt. Không có lượt gọi AI thật trong bộ test offline.

CI đầu phát hiện gói phát hành chưa đưa hai JSON candidate vào danh sách tệp được phép. Đã bổ sung đúng `scenario-dynamic-examples.json` và `scenario-dynamic-release.json`, giữ loại trừ cấu hình riêng; test hồi quy đóng gói lỗi trước sửa và đạt sau sửa (**19 test, 7 skipped trên Windows**). Thay đổi này không đổi instruction, pin hoặc runtime local đang thử.

## 24 lượt AI thật được duyệt

Tập được cố định trước khi gọi: sáu đầu vào hồi quy đã biết và sáu đầu vào mới chưa dùng trong ví dụ. Mỗi đầu vào chạy V5.2 và V5.3 một lần, xen kẽ thứ tự giữa các cặp, tối đa hai lượt song song. Cùng snapshot model Gemini 3.5 Flash Lite, fallback 3.1 Flash Lite, nhiệt độ 0 và ngân sách hiện hành. Chạy EXTRACT và toàn bộ bước sinh/phụ thuộc thật; không mock, không cấp lại artifact, không gọi judge bổ sung. Tổng thời gian thực **364,93 giây**. Mọi 503 và lỗi được giữ; không gửi lại request thất bại/chưa rõ.

| Thước đo | V5.2 | Candidate V5.3 |
|---|---:|---:|
| Hoàn tất kỹ thuật | 8/12 | 6/12 |
| QualityRejected | 2/12 | 3/12 |
| ProviderHttpError | 2/12 | 3/12 |
| Hồi quy: hoàn tất | 5/6 | 5/6 |
| Ca mới giữ lại: hoàn tất | 3/6 | 1/6 |
| Trung vị thời gian mọi lượt, gồm lượt lỗi | 27,32 giây | 29,16 giây |
| p95 quan sát được, gồm lượt lỗi | 46,08 giây | 40,97 giây |
| Lần fallback | 6 | 9 |
| Request có fallback | 5 | 8 |
| Lần thử sửa / được Harness nhận | 4 / 3 | 5 / 0 |
| Input token được ghi nhận | 183.092 | 235.707 |
| Output token được ghi nhận | 61.143 | 60.198 |

Token là usage các attempt có ghi nhận, chưa phải hóa đơn đầy đủ hoặc bằng chứng chi phí nhân quả; hai nhánh có số bước và lỗi khác nhau. Candidate chèn tổng cộng 66 ví dụ trên các lời gọi, khoảng 90.199 byte khối ví dụ. Trần mỗi lần vẫn là 6.000 byte. Không suy ra cải thiện tốc độ từ p95 vì nhiều lượt lỗi kết thúc sớm.

| Đầu vào | V5.2 | V5.3 |
|---|---|---|
| plants | Hoàn tất | Từ chối, sửa câu hỏi hết lượt |
| handover | Hoàn tất | Hoàn tất |
| boxes | Từ chối provenance EXTRACT | Hoàn tất |
| poster | Hoàn tất | Hoàn tất |
| artclub | Hoàn tất | Hoàn tất |
| essay | Hoàn tất | Hoàn tất |
| Mới: bàn giao cho mèo ăn | Hoàn tất | Từ chối nguồn |
| Mới: tình nguyện viên đổi lối vào | Hoàn tất | Từ chối, sửa câu hỏi hết lượt |
| Mới: xuất máy chiếu có kiểm định | Từ chối provenance EXTRACT | 503 tại review nguồn |
| Mới: hợp đồng vận tải | 503 tại GENERATE | 503 tại GENERATE |
| Mới: thiên lệch mẫu khảo sát | 503 tại GENERATE | 503 tại review câu hỏi |
| Mới: giả thuyết đọc bản đồ | Hoàn tất | Hoàn tất |

## Đối chiếu nội dung và quyết định

Không dùng trạng thái hoàn tất, reviewer PASS hoặc test mã làm nhãn chất lượng. Đối chiếu agent trên output cho thấy ca candidate artclub giữ tên người hướng dẫn, ngày học và đồ dùng đúng phạm vi; candidate essay phân biệt mô tả sinh động với kết luận vượt dữ liệu. Nhưng lỗi vẫn còn:

- Candidate handover a1 hỏi lý do dùng phòng in trong khi hoạt động đó chưa nói rõ nhu cầu in; a3 hỏi hành động giúp cụ thể nhưng khóa chỉ ghi chung “offers to help”, có nguy cơ chấm quá rộng.
- Candidate ca giả thuyết đọc bản đồ, a3 có các phần chấm lặp lại chính hành động đã cho trong câu hỏi. Nguồn a1 còn khẳng định giữ độ dài tuyến đường sẽ loại được ảnh hưởng của độ phức tạp, điều không được bảo đảm chỉ bởi độ dài bằng nhau.
- Baseline artclub hỏi ngày trong tuần nhưng khóa thêm buổi tối. Baseline plants hỏi dụng cụ nhưng khóa kèm hành động/thời điểm đã cho. Ca tình nguyện viên của baseline hỏi địa điểm thống nhất nhưng khóa chia thành nhắc lại người đề xuất và người đồng ý.

Đây là quan sát agent, chưa có nhãn chuyên gia hoặc tỷ lệ đúng nội dung được hiệu chuẩn. Chỉ một lần mỗi nhánh/đầu vào và lỗi provider không cân bằng: **chưa có bằng chứng candidate tốt hơn**. Giữ candidate riêng, không thay V5.2 mặc định; không tinh chỉnh ví dụ theo kết quả rồi gọi lại tập giữ lại như kiểm định độc lập.

## Bằng chứng và khả năng tái hiện

Dữ liệu riêng nằm ngoài Git: `C:/Users/HP/.dictalab/local/dynamic-fewshot-ab24-20261117/` có plan.json, started.json, một JSON cho mỗi trong 24 request, summary.json, per-request.csv và core-gate-input.json. CSV có thời gian mỗi bước, loại/bước lỗi, usage, fallback và kết quả Harness. Do bộ che log cũ che một số ID dài, đã tái dựng ID trong selection-reconstruction.json bằng đối chiếu **chính xác instruction đã lưu** với bank/pin cố định cho 73 lời gọi candidate; bản ghi gốc không bị sửa.

Sau benchmark, release được đóng băng thành JSON riêng và bổ sung kiểm tra kho lỗi/ID log. Đã kiểm tra instruction và pin candidate không đổi; backend hash của bản cuối được xác minh riêng. Chưa chạy thêm lượt AI trả phí sau 24 lượt đã duyệt. Runtime Core được đóng gói từ snapshot chỉ gồm HEAD và thay đổi nhiệm vụ, tránh đưa các chỉnh sửa từ vựng đang dở vào local. Giữ nguyên pool bốn cặp giọng và chính sách tắt hạn mức. Không merge hoặc deploy production.

Runtime cuối đã khởi động lại sau khi hoàn tất mã nguồn: Core/AI ready, worker sống, catalog khớp mã đang nạp. Backend hash `128fb81a0cb068716a781fbe814a573ce6e72e3111df5c76dce833905d19baf9`; pin V5.2/V5.3 khớp các giá trị trên. Biên nhận riêng: `dynamic-fewshot-final-runtime-verification.json`. Không tạo phiên đăng nhập QA trong đợt này.
