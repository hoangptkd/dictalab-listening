# Distillation hội thoại: dữ liệu nghiên cứu, chưa huấn luyện

## Mục tiêu và phạm vi

Giữ nguyên prompt production V2, thứ tự schema, reviewer và validation backend. Tạo baseline 50 ca bằng `qwen3.5:9b`, sau đó thu 500 output teacher và lọc **offline** để chuẩn bị supervised fine-tuning. Không fine-tune, không deploy, không dùng dữ liệu thật của người học trong đợt này.

Đây là response distillation: học từ output tổng hợp của teacher; không phải chuyển trọng số, logits hay chain-of-thought. Chưa có cơ sở nói 500 mẫu đủ hoặc 2–3 nghìn sẽ tốt hơn. Chỉ tăng dữ liệu sau một lần fine-tune nhỏ và đánh giá trên tập giữ lại.

## Model đã kiểm tra thực tế

- Student/baseline: Ollama `qwen3.5:9b`, CPU, context 8192, timeout 600 giây, một request đồng thời, không repair và không chạy validation backend.
- Teacher: ShopAIKey `deepseek-v3.2`, tắt thinking. Qwen3.5 397B/122B có trong danh sách nhưng probe hàng loạt trả `503 No available channel ... under group cheap`; không tiếp tục dùng model chỉ vì nó xuất hiện trong `/v1/models`.
- Judge: ShopAIKey `claude-sonnet-4-5`, tách khỏi teacher. Đây là judge offline riêng, không thay prompt đánh giá production. Judge vẫn có thể sai; kết quả là nhãn máy, không phải chứng nhận của con người.
- Judge phiên bản v3 được hiệu chỉnh bằng chín mẫu có nhãn định trước: tiếp nối hợp lệ với slot mới; câu hỏi lệch fact; dịch sai phủ định; fact thêm lý do không được hỏi; lặp loại slot; sai tình huống; giờ không ghi sáng/tối nhưng đáp án cũng không thêm; ngữ cảnh ghi rõ buổi chiều; đáp án tự thêm buổi sáng. Judge hiện tại đạt 9/9 trên bộ nhỏ này, không phải phép đo tổng quát về độ chính xác judge.
- Pilot v2 từng bắt sai giờ không ghi AM/PM và hiểu sai câu hỏi vị trí so với hướng rẽ. Đã dừng batch để sửa tiêu chí, thêm ca hiệu chỉnh và chấm lại output cũ qua `teacher-refilter.jsonl`; không ghi đè nhãn máy cũ. Chỉ verdict phiên bản hiện hành được xuất vào tập provisional.

## Định dạng input và thiết kế thử nghiệm

`ConversationDistillationTest#exportProductionContract` gọi chính `ConversationService.promptContext` trên trạng thái tổng hợp; xuất catalogue 198 context, schema và prompt/few-shot hiện hành. Không tự đoán cấu trúc input và không đọc database người học.

Baseline cố định trước khi chạy: 5 tình huống (`cafe`, `hotel`, `directions`, `plans`, `delivery`) × 3 level × lượt 2/3/4 = 45; thêm 5 ca lượt 4 có 10 phiên luyện gần đây. Câu 1 hiện dùng nội dung curated nên không giả lập gọi LLM ở lượt đó. Lịch sử lượt 2–3 là fixture song ngữ riêng cho từng tình huống; không dùng lịch sử khách sạn cho mọi tình huống. Đây là bộ tập trung rủi ro, **không đại diện đủ cả 24 tình huống**.

Mỗi ca local chỉ lấy output đầu tiên, lưu cả lỗi và thời gian. Input có SHA256, không tái sinh khi resume. Test Java chỉ xác nhận đủ bản ghi, không coi thu đủ 50 ca là nội dung đạt 50/50. Cùng 50 input có thể chạy teacher tham chiếu, nhưng reference không được đưa vào train.

Catalogue teacher phủ 24 tình huống, cả A1/A2/B1 và lượt 2–4. Giữ lại toàn bộ nhóm `scenario/branch/level` thuộc benchmark, không cho các lượt cùng nhánh/level rơi vào train. Tăng tần suất qua `practiceFocus` cho lý do, hành động, 2–3 slot và chuyển giao lượt 4; khoảng một phần tư kế hoạch session có 10 phiên gần đây. Đây là mục tiêu lấy mẫu; báo cáo coverage thực tế mới cho biết đã đạt hay chưa.

Teacher tạo theo chuỗi: chỉ output qua cả hard filter và judge mới được nối làm lịch sử cho lượt sau. Output không đạt và lỗi API vẫn được lưu; không âm thầm sửa hoặc chọn lại cùng ca cho đến khi pass. Vì vậy số ca từng lượt sau lọc có thể mất cân đối. 500 là mục tiêu **output thu thập**, không hứa có 500 mẫu sạch.

## Bộ lọc offline

1. Kiểm tra field/type/độ dài, số fact theo level, ID liên tiếp, skill thuộc danh sách, word bounds, `evidenceEn` là substring chính xác; chặn chép nguyên history/few-shot.
2. Judge kiểm tra câu hỏi ↔ từng fact, trả lời được từ bài nghe, fact một slot, bản dịch đầy đủ, mạch hội thoại, độ khó và input có nhất quán không. Mọi cờ phải true và `issues` phải rỗng mới đủ điều kiện.
3. Loại output trùng hoàn toàn sau chuẩn hóa và input/nhóm trùng benchmark. Chia train/validation/test theo hash **nhóm hội thoại**, không chia ngẫu nhiên từng lượt.
4. Xuất `*.provisional.jsonl` theo messages system/user/assistant, giữ few-shot production và thứ tự output cũ. Metadata provenance nằm ngoài messages. Đây chưa phải dữ liệu được duyệt để huấn luyện.
5. Tạo hàng đợi người duyệt 15–20% mẫu sau lọc, kèm CSV quyết định và HTML có input/output. Không điền giả danh tính người duyệt. Nếu phát hiện một kiểu lỗi trong mẫu duyệt, cần rà lại cả nhóm lỗi trước khi đóng băng bản train.

Baseline được judge trên mọi output có nội dung, kể cả output trượt hard filter, để tách lỗi cấu trúc khỏi lỗi ngữ nghĩa. Kết quả máy cần báo theo từng tiêu chí, không gộp thành một “điểm AI” duy nhất.

## Lưu trữ, giới hạn và chạy lại

Code ở `scripts/conversation-distill.py`, kiểm tra không mạng ở `scripts/test-conversation-distill.py`. Runtime tại `data/conversation-distillation/` được Git bỏ qua. `finalize` tạo bản sao vào `Documents/DictaLab-research-backup/conversation-distillation/` trên máy HP; đây là sao lưu cục bộ, không phải backup khác ổ đĩa hoặc cloud.

Key chỉ đọc từ environment hoặc `.env` được bỏ qua; không truyền key trên dòng lệnh, không ghi vào dữ liệu hay Git. Endpoint HTTPS cố định, không theo redirect. Theo tài liệu provider, kiểm tra credit dùng endpoint `/usage` có key trong query, chỉ tạo URL trong bộ nhớ và không log URL/phản hồi thô.

Mỗi call có deadline, giới hạn response/token, ghi reservation trước request và usage sau response; tối đa 1800 request hoặc 12 triệu token reservation ước lượng bảo thủ theo byte. Sau pilot, điều chỉnh trần thăm dò 5 credit thành 8 credit hiện có để hoàn tất 500 output và các phép so sánh. Kiểm tra mỗi 10 request; đây không phải báo giá USD/VND chính xác và có thể vượt nhẹ bởi các request giữa hai lần đo hoặc hoạt động khác trên cùng key. Không tự nạp tiền. Ba lỗi provider liên tiếp sẽ dừng job để kiểm tra, không retry vô hạn hoặc tự đổi model.

```powershell
mvn --batch-mode --no-transfer-progress '-Dtest=ConversationDistillationTest#exportProductionContract' '-Dconversation.distillation=true' test
python scripts/conversation-distill.py prepare
python scripts/conversation-distill.py calibrate
python scripts/conversation-distill.py refilter
mvn --batch-mode --no-transfer-progress '-Dtest=ConversationDistillationTest#collectFiftyFirstOutputs' '-Dconversation.distillation=true' '-Dconversation.benchmark50=true' '-DforkCount=0' test
python scripts/conversation-distill.py generate --count 500
python scripts/conversation-distill.py teacher-benchmark
python scripts/conversation-distill.py judge-baseline
python scripts/conversation-distill.py finalize
python scripts/test-conversation-distill.py
```

Không chạy đồng thời nhiều tiến trình cloud trên cùng thư mục ledger. Có thể chạy baseline Ollama song song một job cloud. Các job resume giữ kết quả lỗi đã thu; muốn thử lại model/judge khác phải dùng phiên bản thư mục/kết quả riêng, không ghi đè baseline.

`forkCount=0` chỉ dành cho batch offline dài: cấu hình Surefire hiện giới hạn fork 600 giây tổng cộng, không đủ chạy 50 lần inference trên CPU. Lần chạy đầu dừng sau 12 bản ghi và đã resume từ bản ghi 13; không phải model timeout, không thay trần timeout của bộ unit test hoặc backend.

## Cổng trước fine-tuning

Cần người thật duyệt hàng đợi, xử lý lỗi theo nhóm, kiểm tra dedup gần nghĩa/độ phủ, xác nhận quyền sử dụng dữ liệu từ nhà cung cấp, rồi đóng băng manifest và train split. Giấy phép [DeepSeek V3.2](https://huggingface.co/deepseek-ai/DeepSeek-V3.2/blob/main/LICENSE) không thay thế điều khoản dịch vụ của reseller. Chỉ output teacher được dùng làm target; không huấn luyện vào reasoning hoặc lời phê bình của judge.

Sau đó thử LoRA/QLoRA nhỏ, giữ nguyên 50 input và cùng bộ judge để so baseline → model fine-tuned; báo cả tỷ lệ đúng cấu trúc, grounding, câu hỏi/fact, dịch, mạch và latency. Không dùng test set để chọn hyperparameter hoặc tự thêm đáp án test vào train. Chỉ tăng dữ liệu khi validation cải thiện và test không bị dùng lặp làm mục tiêu tối ưu.

Nguồn API: [ShopAIKey chat format](https://shopaikey.com/en/docs/openai-format), [DeepSeek thinking mode](https://api-docs.deepseek.com/guides/thinking_mode/).

## Kết quả chạy

Sẽ bổ sung số liệu sau khi job hoàn tất. Không coi trạng thái đang chạy là hoàn thành dữ liệu hoặc đủ điều kiện fine-tuning.
