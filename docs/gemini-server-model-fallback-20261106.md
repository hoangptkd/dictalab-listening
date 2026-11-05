# Gemini: fallback model khi lỗi máy chủ

## Kết quả

- Đã triển khai fallback cho workflow Scenario: EXTRACT, GENERATE và GRADE, gồm các bước review/repair hiện có.
- Local đã bật `gemini-3.5-flash-lite` → `gemini-3.1-flash-lite`; nguồn `a-flash31` được thêm qua hàm quản lý có audit, dùng cùng project với nguồn `a`. Key vẫn được mã hóa trong registry.
- Không đổi prompt V9, profile cá nhân hóa, schema nội dung, quality harness, dataset hoặc training.
- Đây là thay đổi trên nhánh `codex/fix-scenario-provider-503-20261106`; chưa triển khai production.

## Chính sách lỗi

| Phản hồi | Xử lý |
|---|---|
| Google HTTP 503, `error.code=503`, `error.status=UNAVAILABLE` | Chuyển sang model dự phòng tiếp theo |
| Google HTTP 500, `error.code=500`, `error.status=INTERNAL` | Chuyển sang model dự phòng tiếp theo |
| HTTP 429 | Giữ cơ chế xoay tài khoản trong cùng model |
| HTTP 400/401/403, lỗi nội dung/schema hoặc JSON 200 không hợp lệ | Không fallback model |
| Timeout, mất kết nối, 502/504, 503 dạng HTML/plain text hoặc lỗi không xác định | Không fallback model vì chưa biết kết quả upstream |

Lỗi Google có thể là object hoặc array một phần tử. Phải khớp cả mã HTTP, mã lỗi và trạng thái; không suy đoán từ nội dung thông báo.

Mỗi lần chuyển model dùng lại đúng đầu vào của bước đang lỗi, trong deadline ban đầu. Không chạy lại các bước đã thành công và không gửi lại toàn bộ workflow. Sau khi fallback thành công, các bước tiếp theo trong cùng workflow giữ model đó. Tác vụ EXTRACT/GRADE mới bắt đầu từ model chính.

Mỗi model dự phòng xuất hiện một lần trong chuỗi, tối đa ba model; riêng 429 vẫn có thể thử các tài khoản khác của model hiện tại. Khi hết nguồn, quota hoặc thời gian, giữ lỗi thật và cơ chế bảo vệ kết quả chưa xác định. Job UNKNOWN cũ không bị reset hay tự chạy lại.

## Cấu hình vận hành

Đặt trong cấu hình runtime của **AI API và worker**, không đặt credential vào Core:

```dotenv
DICTALAB_AI_GEMINI_FALLBACK_MODELS=["gemini-3.1-flash-lite"]
```

- Mặc định của Settings/Compose là `[]`: không fallback khi chưa cấu hình. `.env.example` minh họa chuỗi 3.1.
- Registry mode phải có nguồn Gemini enabled cho từng model dự phòng. Tận dụng trang quản lý Gemini hiện có để thêm nguồn cùng project/key với model khác; không tạo giả account để né quota.
- Ưu tiên project đang dùng khi chuyển model; nếu project đó không có nguồn khả dụng, chọn nguồn enabled khác theo cơ chế quota sẵn có.
- Quota project/model và quota toàn cục tiếp tục được giữ; không reset khi chuyển model.
- Không fallback sang DeepSeek/Ollama hoặc provider khác. Các caller ghim model như text gateway không tự bật fallback.
- Công cụ benchmark `tools/ai-eval/scenario_quality.py` tắt fallback để tránh gán kết quả của model dự phòng cho model đang đo.
- Thay đổi chuỗi fallback đổi execution fingerprint; chỉ áp dụng cho tác vụ mới. Kiểm tra không còn job PENDING/RUNNING trước khi đổi cấu hình và restart.
- Rollback: đặt `[]` trên cả API/worker khi đã hết tác vụ đang chạy; giữ nguồn và lịch sử cũ. Không cần migration database.

Local lưu chính sách không chứa secret trong receipt `data/scenario-personalized-local-20261102/runtime.json` ở worktree preview cũ. Việc tắt máy kết thúc các process local; receipt không phải dịch vụ tự khởi động sau reboot. Khi mở lại phải dùng cùng database/broker/ledger và nạp lại cấu hình fallback cho API/worker.

## Bằng chứng chạy thật

Session local: `6a12dd79-72e7-4494-82ba-1c0897a74145`.

| Bước | Model xử lý thành công |
|---|---|
| EXTRACT | Gemini 3.5 Flash Lite |
| GENERATE | Gemini 3.5 Flash Lite |
| QUESTIONS | Gemini 3.5 Flash Lite |
| REVIEW_QUESTIONS | Gemini 3.1 Flash Lite, sau 503 của 3.5 |
| TRANSLATE | Gemini 3.1 Flash Lite |
| REVIEW | Gemini 3.1 Flash Lite |
| GRADE | Gemini 3.1 Flash Lite, sau 503 của 3.5 |

- EXTRACT, GENERATE, GRADE đều SUCCEEDED; Core chấp nhận provenance chứa nhiều model đã khai báo.
- 7 logical requests, 9 HTTP attempts, 2 lần 503 rồi fallback; mỗi logical request có đúng một attempt đánh dấu hoàn tất. GENERATE/QUESTIONS không bị chạy lại.
- Session có ba activity; đáp án riêng không xuất hiện trong API public trước khi chấm. Profile thực tế theo preference đã lưu là B1, không phải A2 trong yêu cầu mẫu.
- Langfuse API HTTP 200: tìm được 15 observations, gồm 7 generations. Hai generation fallback có model 3.1 và metadata `modelFallbacks` chứa nguồn/đích, HTTP 503.
- `modelRouting` ghi model chính và danh sách dự phòng; `calls[].model` ghi model của từng bước. Langfuse giữ model do provider trả về, không ghi đè bằng model chính.
- Bằng chứng riêng ngoài Git: `data/gemini-server-fallback-20261106/`, gồm activation, output public/private, provenance, job results, usage và observations. Lỗi helper đọc sai tên cột sau khi ba tác vụ đã thành công được sửa bằng collector chỉ đọc; không sinh lại bài.

## Kiểm thử

- Python: 365 trường hợp đạt trên các suite gateway, quota, registry/admin, jobs, workflow, harness, contract, text gateway và observability; 27 trường hợp integration cần PostgreSQL test riêng được skip. Kiểm tra mới về tracing chạy lại đạt sau chỉnh sửa cuối.
- Java `ScenarioEngineIntegrationTest`: 75/75 đạt, gồm chấp nhận mixed-model hợp lệ và từ chối routing giả/mâu thuẫn.
- JavaScript `scenario.test.cjs`: 40/40 đạt.
- Maven package đạt bằng thư mục build riêng, sau đó thay JAR khi local đã idle. Lần package ban đầu bị Windows khóa JAR đang chạy; không phải lỗi source.
- Core health và AI readiness HTTP 200 sau restart. Kiểm tra UTF-8 và `git diff --check` đạt.

Lệnh kiểm tra chính từ root, PowerShell:

```powershell
$env:PYTHONPATH="$PWD;$PWD/services/ai-generation"
python -m pytest services/ai-generation/tests/test_gemini_model_fallback.py services/ai-generation/tests/test_observability.py services/ai-generation/tests/test_scenario_workflow.py -q
mvn -B '-Dtest=ScenarioEngineIntegrationTest' test
node --test src/test/js/scenario.test.cjs
```

## Giới hạn bằng chứng

Lần chạy thật xác minh routing và GRADE bằng đáp án tham chiếu. Không phải benchmark chất lượng nội dung, audit nghe bằng người hoặc kiểm thử lại toàn bộ TTS. Fallback model có thể khác chất lượng/chi phí; không kết luận 3.1 tốt hơn 3.5 từ lần chạy này. CI chỉ được ghi đạt khi có workflow thực sự chạy thành công; push nhánh này không tự chứng minh CI hay production đã triển khai.

Tham khảo: [Google Gemini troubleshooting](https://ai.google.dev/gemini-api/docs/troubleshooting), [Langfuse tracing best practices](https://langfuse.com/docs/observability/best-practices).
