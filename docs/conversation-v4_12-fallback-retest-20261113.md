# V4.12 — đo lại reviewer có fallback

Ngày: 13/11/2026. Yêu cầu: chạy lại phép đo frozen F1–F5 và mẫu đúng, bật fallback đang cấu hình trên local.

## 1. Kết luận

**Fallback đã chuyển model thành công trong thực tế.** Mẫu đúng hoàn tất và được chấp nhận; mẫu lỗi vẫn không có verdict cuối vì model dự phòng cũng gặp HTTP 503.

Không sửa prompt, schema, validator, nhãn hoặc dữ liệu. Không đủ bằng chứng cập nhật kết quả bắt lỗi F1–F5 hay khẳng định prompt tốt hơn; kết quả 2/5 trong lần đo negative hoàn chỉnh trước vẫn là phép đo gần nhất.

## 2. Cách chạy

- Cùng hai map frozen đã dùng trước: mỗi map có ba activity, chín meanings và chín questions. Không sinh hội thoại/dataset mới.
- V4.12 hash: `77d9ca8f29ef2be302741441bce8610f0502bcb7849071c1ae6d66dec78114f1`.
- Input hash: `3c2af4960a6270f221b50fbea4044ea80343963c9cba5d033f894c112d50996a`.
- Primary `gemini-3.5-flash-lite`, fallback `gemini-3.1-flash-lite`, temperature 0.
- Dùng gateway đang có và telemetry đã khôi phục; kiểm tra emergency stop trước mỗi stage. Không reset quota, sửa nguồn hoặc đổi tài khoản ngoài routing hiện hữu.
- Khi fallback thành công, các stage còn lại của cùng mẫu tiếp tục dùng model dự phòng như executor thật. Mẫu độc lập tiếp theo bắt đầu từ primary.
- Không cố tình tạo lỗi 503; tất cả response trong bảng là lỗi/đầu ra provider thật. Không retry timeout/kết quả chưa rõ, không tự thêm model thứ ba hoặc chạy lại stage đã thành công.
- Runner đầu dừng các item còn lại sau provider error. Vì vậy mẫu đúng được chạy trong experiment tiếp nối riêng, bắt đầu đủ ba stage mới của mẫu này; không gọi lại review negative thất bại và không tái dùng ledger negative cho positive.

## 3. Kết quả từng bước

| Mẫu | Stage | HTTP/model thực tế | Kết quả |
| --- | --- | --- | --- |
| Negative F1–F5 | AUDIT_MEANINGS | 3.5 → 200 | Hợp đồng hợp lệ |
| Negative F1–F5 | AUDIT_QUESTIONS | 3.5 → 503; fallback 3.1 → 200 | Fallback thành công, hợp đồng hợp lệ |
| Negative F1–F5 | REVIEW_QUESTIONS | 3.1 → 503 | UNAVAILABLE; chưa có kết luận ngữ nghĩa cuối |
| Positive đã sửa đúng | AUDIT_MEANINGS | 3.5 → 200 | Hợp đồng hợp lệ |
| Positive đã sửa đúng | AUDIT_QUESTIONS | 3.5 → 200 | Hợp đồng hợp lệ |
| Positive đã sửa đúng | REVIEW_QUESTIONS | 3.5 → 503; fallback 3.1 → 200 | **Được chấp nhận** |

Positive cuối có `valid=true`, `issues=[]`, không có false field trong meaningChecks/questionChecks; gate của riêng positive đạt. Đây là **một positive qua**, không phải toàn bộ benchmark đạt hoặc false rejection rate 0% nói chung.

Lần positive trước từng bị reviewer từ chối nhầm vì diagnostic gộp ý. Lần này reviewer cuối dùng **3.1**, hai audit dùng **3.5**; chưa thể quy cải thiện cho prompt hoặc so sánh thuần chất lượng 3.5/3.1. V4.12 không đổi giữa hai phép đo.

Negative không có final verdict nên `gatePassed=false`/score không đạt không phải kết luận reviewer chấm sai cả năm lỗi; trường hợp này phải ghi **không đo được do provider**, không ghi 0/5 hoặc giữ 2/5 như kết quả mới.

## 4. Request, usage và trace

- **8 POST thực tế**: 5 HTTP 200, 3 HTTP 503; không có 429 trong lần này.
- Hai lần chuyển 3.5 → 3.1 sau 503 đều nhận HTTP 200. Một call 3.1 khác ở stage tiếp theo gặp 503.
- Usage của năm call thành công: **11.334 input + 11.045 output = 22.379 tokens**. Các call 503 không báo usage; không suy là miễn phí. Chưa đối chiếu hóa đơn.
- Negative: 29,085 giây; positive: 28,170 giây, gồm thời gian fallback trong mỗi mẫu, không tính khoảng chờ giữa experiment.
- Đã đọc lại Langfuse bằng CLI, khớp sáu generation observations với sáu stage và đúng metadata fallback. Một generation có thể gồm hai HTTP attempts nên không đếm observation thành số POST.

| Bằng chứng | Link |
| --- | --- |
| Negative, review cuối 503 | [Trace a1251d7a…](https://jp.cloud.langfuse.com/project/cmvx6vyks000iad0j2ph4qdbu/traces/a1251d7a1c427a8ca6fdd4e1eb4ba17b) |
| Positive, fallback review thành công | [Trace 1f7b5b5e…](https://jp.cloud.langfuse.com/project/cmvx6vyks000iad0j2ph4qdbu/traces/1f7b5b5e8bbb6d438d1566b7f82bdb0b) |
| Experiment đầu | [Kết quả](https://jp.cloud.langfuse.com/project/cmvx6vyks000iad0j2ph4qdbu/experiments/results?baseline=567f7bf940e41dd3) |
| Positive tiếp nối | [Kết quả](https://jp.cloud.langfuse.com/project/cmvx6vyks000iad0j2ph4qdbu/experiments/results?baseline=d2b017240e7abf66) |

## 5. Hồ sơ và trạng thái

Evidence riêng, không đưa vào Git:

- `data/conversation-meaning-assessment-20261106/fallback-v4_12-20261113/`: runner, frozen input, started/result từng stage, đầy đủ các HTTP attempt/model/status/raw output/usage, negative result, summary, Langfuse readback, analysis và SHA-256 manifest.
- `data/conversation-meaning-assessment-20261106/fallback-positive-v4_12-20261113/`: experiment positive riêng và manifest.
- Trước khi gọi AI đã chạy preflight và kiểm tra cú pháp runner. Sau cùng xác minh source hashes và frozen input không đổi, trace đọc lại khớp. UTF-8 và diff được kiểm tra trước bàn giao.

Không thay runtime, không tạo phiên đăng nhập, không sửa bài đã lưu hoặc bật/tắt release trong task này. [V4.12 vẫn ở local preview](conversation-v4_12-activation-and-system-audit-20261113.md); chưa deploy production.
