# So sánh 20 tình huống: V5 + GRADE V4 và few-shot

## Trạng thái

**Chưa hoàn tất và chưa thể kết luận bản nào tốt hơn.** Hai lần thử trên hai tình huống khác nhau đều gặp HTTP 429 của Gemini; lần thứ hai diễn ra hơn một phút sau lần đầu. Không có bài nào được cả hai bản hoàn tất, nên không có tỷ lệ thắng hoặc so sánh chất lượng hợp lệ cho 20 bài.

## Dữ liệu và cách đo

- Bộ đầu vào: [20 tình huống hư cấu](../services/ai-generation/tests/fixtures/evaluation/scenario-prompt-paired-20-20261102.json), gồm ăn uống 3, di chuyển 3, mua sắm 3, học tập/công việc 4, dịch vụ 3, giao tiếp 4. Mọi brief đều là happy case.
- Cùng mỗi brief, cùng `gemini-3.5-flash-lite`, temperature 0, profile `standard`, schema, validator, output budget và các bước kiểm duyệt hiện có. Hai bản được chạy xen kẽ và đảo thứ tự theo case.
- Baseline đang dùng: `SCENARIO_LISTENING_V5_GRADE_V4_1`, SHA-256 `d43a3e228449dc64d7b6c103c2013558ac6e9415feabad8ff50e083900b0ae21`.
- Candidate: `SCENARIO_LISTENING_V5_FEWSHOT_1_GRADE_V4`, SHA-256 `3dbd141b3dc9f8652f3797a4cd8487b8145d167eb2ee83727913faa6610888f1`. Đây là candidate 10 ví dụ từng bước của đợt trước; **không có 20 bản nháp sinh trước đó trong prompt**. GRADE giữ V4 ở hai bản.
- 20 brief này đã được dùng để sinh các bản nháp theo một prompt khác trước khi người dùng đổi yêu cầu. Hai candidate đã được đóng băng từ trước và không sửa theo các bản nháp, nhưng người thực hiện đã thấy một số output; vì vậy không coi đây là bộ đánh giá mù. Các bản nháp cũ không được tính vào kết quả so sánh.
- Chỉ process benchmark dùng `operational_limits_enabled=False` theo yêu cầu test local; nhà cung cấp vẫn thực thi giới hạn. Không đổi dịch vụ hay prompt đang hoạt động. Mỗi lần gọi được lưu request, schema, response, wire và outcome trong thư mục riêng.

## Kết quả thực tế

| Case | Bản hiện tại | Few-shot |
|---|---|---|
| 01 – hỏi món chay | EXTRACT thành công; GENERATE bị gate từ chối ở REVIEW_QUESTIONS | EXTRACT thành công; hội thoại đã sinh, bước QUESTIONS nhận HTTP 429: **UNKNOWN** |
| 02 – chuẩn bị dã ngoại | Chưa chạy | EXTRACT thành công; GENERATE nhận HTTP 429: **UNKNOWN** |
| 03–20 | Chưa chạy | Chưa chạy |

Case 01 bản hiện tại: REVIEW_QUESTIONS trả `valid=true`, nhưng validator phát hiện một số đáp án gồm nhiều đơn vị thông tin. Ví dụ câu “Hôm nay nhà hàng có những món ăn chay nào?” yêu cầu ba món. Gate đã từ chối; không coi câu trả lời `valid=true` là bằng chứng bài đạt chất lượng.

Ở case 01 few-shot, hội thoại đã có trong capture nhưng chưa có bộ câu hỏi/đáp án hoàn chỉnh. Không so sánh hội thoại đơn lẻ này với cả bài của baseline và không coi lỗi 429 là lỗi chất lượng nội dung.

Hai attempt ghi được **7 HTTP 200 và 2 HTTP 429**. Wire chỉ giữ mã HTTP và hash body lỗi, không giữ nội dung body; chưa xác định được giới hạn theo phút, ngày hay nguyên nhân cụ thể khác. Tình huống UNKNOWN không bị chạy lại để thay kết quả; lần tiếp theo chỉ thử case chưa chạy. Lần thứ hai bắt đầu bằng case 02 và dừng ngay sau 429.

## Tracing và bảo toàn evidence

- [Trace của attempt đầu](https://jp.cloud.langfuse.com/project/cmvx6vyks000iad0j2ph4qdbu/traces/a52bfc2be7fdf56b30d7d75278559fe0).
- [Trace của attempt tiếp theo](https://jp.cloud.langfuse.com/project/cmvx6vyks000iad0j2ph4qdbu/traces/f39f0a037cd71329c564b41fdb0a68d2).
- Evidence gốc ở `data/e20/`: `paired/`, `remaining/`, `interrupted.json`, `remaining-interrupted.json`, `cases.json` và scripts chạy. Hai phần chưa có `finished.json` hay seal hoàn thành; không dùng comparator dành cho run đã hoàn tất với chúng.
- Bản sao ngoài worktree: `C:/Users/HP/Desktop/English_dictition/data/scenario-evidence/paired-20-20261102.zip`, kèm receipt và SHA-256. Archive cũng tách riêng các bản nháp trước khi đổi yêu cầu; không dùng chúng làm kết quả của hai arm.

Khi provider cho phép trở lại, giữ nguyên hai prompt và profile đã ghi, chạy các arm/case chưa có outcome trong một capture mới. Giữ 429 và các kết quả đã có; nếu đo lặp một case UNKNOWN, báo đó là lượt mới riêng và không xóa lần lỗi. Sau đó đọc toàn bộ case cả hai bản hoàn tất, kiểm vai, tiến triển, độ dài, từng câu hỏi/đáp án/bằng chứng, reviewer và token; tiếp tục giữ baseline cho đến khi có đủ bằng chứng.

## Kiểm tra xoay ba nguồn Gemini

Kiểm tra local ngày 02/11/2026: registry có ba nguồn đang bật `a`, `b`, `c`, ba giá trị khóa và ba project ID cấu hình khác nhau; cùng model `gemini-3.5-flash-lite`. Không thể chỉ từ cấu hình xác minh chúng thuộc ba tài khoản Google hoặc ba project thực sự độc lập về quota.

Trong capture của case 01, sáu request HTTP 200 lần lượt dùng `a → b → c → a → b → c`; lần EXTRACT thành công ở case 02 dùng `a` sau khi bắt đầu process mới. Vì vậy **ba nguồn thực sự được dùng**, không phải chỉ một nguồn. Thông tin nguồn không được ghi trong capture lỗi 429: `trace_generation` chỉ ghi `sourceId` sau khi request thành công. Không gán nguồn cụ thể cho hai lỗi khi thiếu bằng chứng.

Logic hiện tại trong `model_gateway.py` và `gemini_pool.py`:

1. Trước mỗi HTTP, pool chọn luân phiên một nguồn cùng model, bỏ qua nguồn đang cooldown hoặc hết hạn mức admission.
2. Nếu nguồn trả 429, gateway ghi cooldown cho nguồn đó rồi trả lỗi lên caller; **không tự gửi lại cùng request sang nguồn khác**. Request tiếp theo có thể chọn nguồn khác. Worker scenario không replay cả workflow đã có các request thành công.
3. Script benchmark của đợt này chủ động dừng lượt khi thấy 429; do đó các case còn lại chưa được thử dù nguồn khác có thể tồn tại. Đây là giới hạn của kịch bản benchmark, không phải bằng chứng bộ xoay chỉ có một nguồn.

Ảnh chụp bộ đếm Redis local lúc kiểm tra: `a=505`, `b=501`, `c=500` lượt admission trong ngày Pacific; mức RPD được cấu hình là 500 cho mỗi nguồn. Benchmark đặt `operational_limits_enabled=false`, nên admission local không chặn ở mức 500, nhưng không xóa giới hạn của Gemini. Các số này bao gồm reservation, không chứng minh số request Google đã tính và không chứng minh nguyên nhân HTTP 429. Bộ đếm local dự kiến sang ngày mới lúc **15:00 ngày 02/11/2026 giờ Việt Nam**; không khẳng định quota thực tế của nhà cung cấp cũng sẽ khả dụng ngay lúc đó.

Đã chạy tám kiểm tra tập trung của pool: **8 PASS**, gồm xoay dưới tải giả lập, giữ cooldown, admission không giới hạn local và không retry request đã nhận 429. Chưa có kiểm tra thực tế chứng minh cả ba project còn quota tại thời điểm lỗi. Có thể cải thiện benchmark để tiếp tục case độc lập khác và ghi riêng mọi 429; nếu muốn thử lại request có 429 trên nguồn khác, cần ghi thành attempt mới với source ID, mã lỗi và chi phí, không ghi đè outcome cũ.
