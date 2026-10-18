# Tạo câu hội thoại bằng AI local

Trạng thái: prompt V2 và đường nối Ollama đã có; **pilot local chưa đạt cổng chất lượng, không bật production**.

## Phạm vi đợt này

- Viết lại prompt tạo câu `CONVERSATION_GENERATION_V2`: mô tả input, hợp nhất quy tắc fact, phân định lượt 1–3/lượt 4, vai người học, cách chọn skill và mục đích của task/question.
- Mỗi request chỉ nhận một few-shot đúng A1/A2/B1; có ví dụ sai → đúng cho việc tách slot. Local dùng cặp user/assistant minh họa riêng, rồi mới gửi input thật trong `<data>`. Backend từ chối output chép nguyên ví dụ. `levelProfile` vẫn quyết định giới hạn số từ, kể cả phiên cũ.
- Chọn Ollama cho **tạo câu**. Câu khởi động có sẵn không gọi LLM. Không tự động chuyển sang cloud khi local lỗi.
- Giữ nguyên `GRADE_INSTRUCTION`, `REVIEW_INSTRUCTION` và provider của hai phần đánh giá. Làm rõ câu trả lời và TTS cũng chưa chuyển local. Đây **không phải chế độ offline hoàn toàn**.

Chưa làm RAG, fine-tuning, variation planner mới, bộ nhớ hội thoại có cấu trúc hay thay giao diện.

## Model và cấu hình

Model mặc định cho generator local: `hf.co/Qwen/Qwen3-4B-GGUF:Q4_K_M`, đã có trên máy HP. Chọn bản này sau khi thử Qwen 3.5 4B trên CPU gặp timeout ở cả ba lượt A1 đầu tiên với giới hạn 30 giây. Đây là lựa chọn thực dụng cho máy hiện tại, không phải kết luận chất lượng chung giữa hai model.

```dotenv
DICTALAB_CONVERSATION_GENERATION_PROVIDER=ollama
DICTALAB_CONVERSATION_LOCAL_BASE_URL=http://127.0.0.1:11434
DICTALAB_CONVERSATION_LOCAL_MODEL=hf.co/Qwen/Qwen3-4B-GGUF:Q4_K_M
DICTALAB_CONVERSATION_LOCAL_TIMEOUT_SECONDS=40
```

Chạy bằng `powershell -File scripts/run-local.ps1 -WithConversationLocal`. Script kiểm tra model, làm nóng weights và báo nếu app cũ vẫn đang chạy. Cần khởi động lại app để nhận cấu hình mới. Không tự dừng app đang phục vụ người dùng.

Backend và `.env.example` giữ provider `shopaikey` để không bật một pilot chưa đạt cổng chất lượng. Cờ chạy local hoặc cấu hình `ollama` ở trên chọn local rõ ràng. Không thay `SHOPAIKEY_MODEL` hay `DICTALAB_CONVERSATION_MODEL`: model cloud vẫn kiểm duyệt đầu ra trước khi đưa vào phiên học.

Chỉ chấp nhận địa chỉ loopback, không theo redirect và không dùng tag cloud. Request/response có giới hạn dung lượng; tắt thinking, dùng JSON schema, giới hạn một request generation local đồng thời và circuit breaker. Coach có giới hạn riêng, vì vậy bật cả hai vẫn có thể tranh CPU.

Mỗi call local tối đa 40 giây. Generation + validation + review + một lần sửa chung deadline 55 giây, dưới timeout giao diện 60 giây; luồng cloud hiện tại giữ 45 giây. Không retry lỗi mạng. Lỗi vẫn giữ phiên, giải phóng lease để người học thử lại. Local sử dụng quota local và kill switch hiện có; review/grading tiếp tục dùng quota cloud.

JSON schema không bảo đảm đúng nghĩa. Backend vẫn kiểm tra grounding, số fact, độ dài, skill và trùng lặp; reviewer cloud vẫn kiểm tra câu hỏi/đáp án và mạch hội thoại. Không gỡ lớp kiểm tra để tăng tỷ lệ trả kết quả.

## Kiểm tra chạy lại

```powershell
mvn --batch-mode --no-transfer-progress '-Dtest=ConversationGenerationPromptTest,ConversationLocalClientTest' test
& scripts/test-postgres.ps1 -PostgresBin 'C:/Users/HP/Tools/postgresql-18.4/pgsql/bin' -Port 15439 -MavenArgs @('-Dtest=ConversationIntegrationTest,ConversationUxIntegrationTest,ShopAiKeyClientTest,CoachLocalServiceIntegrationTest','test')
mvn --batch-mode --no-transfer-progress '-Dtest=ConversationLocalEvaluationTest' '-Dconversation.evaluate=true' test
```

Smoke test local dùng dữ liệu khách sạn tổng hợp ở A1/A2/B1, không gửi dữ liệu người học hay gọi API trả phí. Báo cáo nằm tại `target/conversation-local-evaluation.json`, không commit dữ liệu runtime. Có thể đổi model bằng `-Dconversation.model=...`.

Smoke test chỉ kiểm tra ràng buộc cơ bản và thời gian của generator; **không thay thế đánh giá ngữ nghĩa độc lập, CEFR hoặc đo toàn bộ generation + cloud review + TTS**. Thời gian sẽ tăng khi model lạnh hoặc CPU bận.

Kết quả trên CPU HP ngày 18/10/2026: Qwen3-4B Q4_K_M chỉ qua 2/5 trường hợp đã chạy bằng validator phiên học. A1 lượt 3 và A2 lượt 3 lặp nguyên câu dù đã sửa một lần; B1 lượt 2 hết deadline. Dừng từng level khi lỗi nên chưa đi hết chín lượt mục tiêu. Test opt-in **thất bại**, không hạ tiêu chí cho nó xanh. Hai câu qua hard validation cũng chưa được coi là đạt chất lượng ngữ nghĩa. Thời gian các lượt là 32–55 giây, chưa tính cloud reviewer/TTS.

Một thử nghiệm trước chỉ kiểm tra schema/grounding đạt 9/9 nhưng nội dung thực tế chép ví dụ/lịch sử; không dùng kết quả đó để tuyên bố model đạt. Bộ kiểm tra hiện dùng thêm validator tiến trình và chặn chép ví dụ. Cần đánh giá/chọn model hoặc thay cách cung cấp trạng thái ở đợt sau; prompt-only chưa giải quyết độ tin cậy của model này.

Context local hiện cố định 8K token. Smoke test chưa chứng nhận trường hợp có đủ 10 phiên lịch sử dài; cần đo token và làm ngân sách context trước khi rollout rộng. Không bật local trên production chỉ dựa vào smoke test này.

Tham chiếu triển khai: [Ollama chat/structured outputs](https://docs.ollama.com/api/chat), [model Qwen chính thức](https://huggingface.co/Qwen/Qwen3-4B-GGUF).

## Tích hợp nhánh

Đợt này không có migration. Tại thời điểm kiểm tra, `origin/main` ở `ae6da19` có payment migration V39, còn nhánh hiện tại đã có Coach V39 áp dụng ở máy HP. Không merge trực tiếp hai lịch sử này hoặc đổi tên migration đã áp dụng chỉ để vượt kiểm tra. Cần xử lý tích hợp migration riêng trước khi phát hành chung lên main; push nhánh tính năng không phải deploy production.
