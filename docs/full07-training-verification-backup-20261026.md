# Báo cáo xác minh và sao lưu Qwen3.5-4B full07

- **Thời điểm lập:** 2026-10-26 10:50 (UTC+7)
- **Cập nhật:** 2026-10-26 12:23 (UTC+7), sau khi hoàn tất so sánh inference.
- **Phạm vi:** xác minh kết quả huấn luyện full07, sao lưu checkpoint-200/adapter/final bundle và so sánh inference bằng prompt riêng.
- **Kết luận ngắn:** huấn luyện đã hoàn thành `200/200` step; checkpoint-200 và adapter đã được đối chiếu hash. Toàn bộ bản sao cục bộ đã được xác minh. Lượt kiểm tra bổ sung ngày 26/10 đã tải lại cả gói Drive, xác minh đủ 14/14 tệp trong manifest và danh sách 15 tệp, không có lỗi. Không huấn luyện lại, không triển khai production.
- **Chất lượng sinh:** bốn cặp prompt riêng cho thấy adapter cải thiện cấu trúc đầu ra, nhưng vẫn có lỗi nghĩa và chưa tuân thủ đầy đủ yêu cầu B1. Chưa đủ căn cứ đưa vào phục vụ thực tế.

## 1. Mục tiêu

1. Kiểm tra xem lần huấn luyện full07 đã thực sự hoàn thành chưa.
2. Xác minh checkpoint-200, adapter LoRA và các số liệu huấn luyện.
3. Tạo bản sao lưu an toàn cho checkpoint, adapter và bằng chứng liên quan.
4. Không thay đổi dataset, split, target hay loss mask; không huấn luyện lại.

## 2. Ràng buộc đã tuân thủ

- Chỉ dùng Chrome thật đang mở, không tạo trình duyệt mới.
- Không đổi dataset, split, target hoặc `last-assistant-only` loss mask.
- Không huấn luyện lại.
- Không đưa credential vào Git, source code, log hoặc báo cáo.
- Không kết luận huấn luyện/triển khai thành công nếu chưa có bằng chứng kiểm tra.
- Giữ nguyên các thay đổi không liên quan trong repository.

## 3. Môi trường và nguồn dữ liệu

- **Máy:** Windows; dùng Chrome hiện có của người dùng để kiểm tra Drive.
- **Drive:** thư mục `DictaLab/training-checkpoints/full06`.
- **Nguồn checkpoint:** `DictaLab/training-checkpoints/full06/checkpoints/checkpoint-200`.
- **Trạng thái Colab:** runtime đã khởi động lại; thư mục `/content/qwen-sft-full07` không còn tồn tại.
- **Hệ quả:** không thể chạy lại script postflight cuối cùng trong runtime; việc xác minh được thực hiện trên checkpoint-200 đã tải từ Drive.

## 4. Các bước đã thực hiện

### 4.1. Kiểm tra trạng thái trên Drive

- Truy cập đúng tài khoản Drive và thư mục `full06`.
- Kiểm tra `full07-status.json`: tệp này là snapshot cũ, vẫn ghi `status=running`, `completedFullTraining=false`, `wrapperPid=18325`.
- Xác nhận runtime không còn tiến trình huấn luyện và `/content/qwen-sft-full07` không còn.
- Kết luận: không dùng `full07-status.json` để đánh giá hoàn thành; dùng `trainer_state.json` và checkpoint đã đối chiếu hash.

### 4.2. Tải và kiểm tra checkpoint-200

- Tải thư mục `checkpoint-200` từ Drive.
- Giải nén và kiểm tra danh sách tệp: 13 tệp.
- `resume-integrity.json` chứa SHA-256 của 12 tệp; tệp thứ 13 là chính manifest này.
- Đối chiếu SHA-256 của cả 12 tệp: **12/12 khớp, 0 lỗi**.
- Kiểm tra `trainer_state.json`:
  - `global_step=200`
  - `max_steps=200`
  - `epoch=2.0`
  - checkpoint tốt nhất: `checkpoint-200`

### 4.3. Kiểm tra số liệu huấn luyện

- Validation loss tại step 100: `0.38129329681396484`.
- Validation loss tại step 200: `0.37072810530662537`.
- Mức giảm tương đối: khoảng **2,77%**.
- Training loss tại step 200: `0.20730003714561462`.
- `eval_mean_token_accuracy` tại step 200: `0.8988239858299494` (khoảng **89,88%**).
- Dashboard hiển thị `200/200` và training loss `0.2073`. Đây là loss ghi ở step cuối, không phải loss trung bình của 200 step.
- `eval_mean_token_accuracy` là độ chính xác token trong lượt validation có target, không phải tỷ lệ câu trả lời sinh tự do đạt yêu cầu.
- Wrapper kết thúc với `FileNotFoundError` do kiểm tra `checkpoint-1000` thay vì `checkpoint-200`, sau khi trainer đã thoát với mã `0`. Cảnh báo telemetry `missing_update` là phân loại của reporter khi bằng chứng cập nhật chưa thỏa điều kiện; reporter cũ còn so số cập nhật mới sau resume với tổng step. Hai vấn đề này cần phân biệt, không quy trực tiếp `missing_update` thành lỗi optimizer hoặc chỉ thành lỗi đường dẫn checkpoint.

### 4.4. Kiểm tra adapter

- Base model: `Qwen/Qwen3.5-4B`.
- LoRA: `r=16`, `alpha=32`, `dropout=0.05`.
- Target modules: `q_proj`, `k_proj`, `v_proj`, `o_proj`.
- Task type: `CAUSAL_LM`.
- `adapter_model.safetensors`:
  - 64 tensor.
  - 3.145.728 tham số.
  - Tất cả tensor hữu hạn (finite).
  - Cả 64 tensor đều có giá trị khác 0.
- SHA-256 adapter: `1670910dcad7f1e8f66d1dd74e6297fe914c2067d0c26240b72b25fd36c922bf`.

### 4.5. Tạo bản sao lưu cục bộ

Đã tạo:

- `data/fine-tuning/colab-transfer-20261025/checkpoint-200-backup-20261026/checkpoint-200/`
  - 13 tệp checkpoint.
  - 12 tệp đã đối chiếu hash thành công.
- `data/fine-tuning/colab-transfer-20261025/full07-final-20261026/`
  - `adapter/`: 6 tệp adapter/tokenizer.
  - `evidence/`: 7 tệp bằng chứng.
  - `BACKUP-NOTES.md`.
  - `final-integrity.json`.

`final-integrity.json` chứa hash của 14 tệp (không tính chính nó) và các metadata:

- `step=200`, `maxSteps=200`, `epoch=2.0`.
- `evalLossStep100=0.38129329681396484`.
- `evalLossStep200=0.37072810530662537`.
- `trainingLossStep200=0.20730003714561462`.
- `adapterHash=1670910dcad7f1e8f66d1dd74e6297fe914c2067d0c26240b72b25fd36c922bf`.
- `datasetHash=84c243b56dd37ba991585d70158fb19336dfaefca5fc1afa07449514a7b18c53`.

### 4.6. Tải lên Google Drive

- Tải thư mục `full07-final-20261026` lên `DictaLab/training-checkpoints/full06`.
- Đổi tên thư mục trên Drive thành `full07-final`.
- Xác minh cấu trúc:
  - `adapter/`: 6 tệp.
  - `evidence/`: 7 tệp.
  - `BACKUP-NOTES.md`.
  - `final-integrity.json`.
  - Tổng cộng: **15 tệp**.

### 4.7. Đối chiếu lại bản đã tải lên

- Tải lại `adapter_model.safetensors` từ Drive.
  - SHA-256 khớp: `1670910dcad7f1e8f66d1dd74e6297fe914c2067d0c26240b72b25fd36c922bf`.
- Tải lại `final-integrity.json` từ Drive.
  - SHA-256 khớp: `cd62867a0c3272e02b525a72cf31ca21f9d7099ab262f8b81c5bfd43c4dda88d`.

## 5. Bảng tổng hợp kết quả

| Hạng mục | Kết quả |
|---|---|
| Số step huấn luyện | `200/200` |
| Epoch | `2.0` |
| Checkpoint tốt nhất | `checkpoint-200` |
| Validation loss step 100 | `0.38129329681396484` |
| Validation loss step 200 | `0.37072810530662537` |
| Mức giảm validation loss | khoảng `2,77%` |
| Training loss step 200 | `0.20730003714561462` |
| Eval token accuracy step 200 | `0.8988239858299494` |
| Checkpoint manifest | `12/12` tệp khớp hash |
| Adapter tensor | 64 tensor, 3.145.728 tham số |
| Adapter finite/nonzero | Có, toàn bộ 64 tensor |
| Dataset hash | `84c243b56dd37ba991585d70158fb19336dfaefca5fc1afa07449514a7b18c53` |
| Drive `full07-final` | 15 tệp |
| Tải lại adapter từ Drive | Hash khớp |
| Tải lại `final-integrity.json` | Hash khớp |

## 6. Vị trí lưu trữ

### Cục bộ

- Checkpoint:
  `data/fine-tuning/colab-transfer-20261025/checkpoint-200-backup-20261026/checkpoint-200/`
- Final bundle:
  `data/fine-tuning/colab-transfer-20261025/full07-final-20261026/`
- Ghi chú backup:
  `data/fine-tuning/colab-transfer-20261025/full07-final-20261026/BACKUP-NOTES.md`
- Integrity:
  `data/fine-tuning/colab-transfer-20261025/full07-final-20261026/final-integrity.json`

### Google Drive

- Checkpoint gốc:
  `DictaLab/training-checkpoints/full06/checkpoints/checkpoint-200`
- Bản final:
  `DictaLab/training-checkpoints/full06/full07-final`

## 7. Hạn chế và những gì chưa xác minh

1. **Không chạy được postflight cuối trong Colab** vì runtime đã khởi động lại và `/content/qwen-sft-full07` không còn.
2. Các tệp chỉ tồn tại trong runtime không còn trên Drive:
   - `run-manifest.json`
   - `optimizer-evidence.json`
   - `optimizer-evidence-trainer.json`
   - `adapter-delta-evidence.json`
   - `preflight-adapter/`
   - `training.log`
3. Không thể so sánh trực tiếp adapter ban đầu với adapter cuối vì không còn `preflight-adapter/`. Adapter cuối có toàn bộ tensor hữu hạn và khác 0; riêng điều này không chứng minh số lần optimizer update hoặc chất lượng câu trả lời. Bằng chứng cập nhật đã xác minh trong runtime trước đó và số liệu validation được giữ riêng, không tái tạo các tệp gốc đã mất.
4. `full07-status.json` là dữ liệu cũ, vẫn ghi `running`; không dùng để kết luận.
5. **Đã chạy inference thăm dò** bằng bốn cặp prompt riêng, đủ tám đầu ra, không dùng tập test; chi tiết tại mục 12. Phép thử nhỏ này phát hiện lỗi ngữ nghĩa thực tế, không chứng nhận chất lượng tổng quát hoặc độ sẵn sàng production.
6. Đây không phải là triển khai production; chưa có endpoint hay kiểm thử phục vụ nào được triển khai.

## 8. Kết luận

- Huấn luyện full07 đã hoàn thành `200/200` step, `epoch=2.0`.
- Checkpoint-200 là checkpoint tốt nhất theo validation loss.
- Toàn bộ 12 tệp trong manifest checkpoint đã được đối chiếu hash thành công.
- Adapter LoRA đúng cấu hình, đủ tensor và không có giá trị bất thường.
- Bản sao lưu cục bộ đã được xác minh toàn bộ. Lượt bổ sung ngày 26/10 tải lại toàn bộ gói Drive và đối chiếu đủ 14/14 hash; manifest và danh sách 15 tệp cũng khớp.
- So sánh sinh tự do cho thấy cấu trúc tốt hơn ở adapter; chất lượng nghĩa chưa ổn định. Không dùng số liệu validation token accuracy thay cho tỷ lệ câu trả lời đạt yêu cầu.
- Không huấn luyện lại, không thay đổi dataset/split/target/loss mask, không triển khai production.

## 9. Cách kiểm tra lại nhanh

### Kiểm tra checkpoint-200

```powershell
$cp = "C:\Users\HP\Desktop\English_dictition\data\fine-tuning\colab-transfer-20261025\checkpoint-200-backup-20261026\checkpoint-200"
$m = Get-Content (Join-Path $cp "resume-integrity.json") -Raw | ConvertFrom-Json
$bad = @()
foreach ($n in $m.files.PSObject.Properties.Name) {
    $p = Join-Path $cp $n
    if (-not (Test-Path $p)) { $bad += $n; continue }
    if ((Get-FileHash $p -Algorithm SHA256).Hash.ToLower() -ne $m.files.$n.ToLower()) {
        $bad += $n
    }
}
"failed=$($bad.Count) step=$($m.step)"
```

Kết quả mong đợi: `failed=0 step=200`.

### Kiểm tra adapter

```powershell
& .\data\fine-tuning\.venv-cpu\Scripts\python.exe -c "from safetensors.torch import load_file; import torch; p=r'C:\Users\HP\Desktop\English_dictition\data\fine-tuning\colab-transfer-20261025\checkpoint-200-backup-20261026\checkpoint-200\adapter_model.safetensors'; d=load_file(p); print(len(d), sum(v.numel() for v in d.values()), all(torch.isfinite(v).all().item() for v in d.values()))"
```

Kết quả mong đợi: `64 3145728 True`.

### Kiểm tra final bundle

```powershell
$out = "C:\Users\HP\Desktop\English_dictition\data\fine-tuning\colab-transfer-20261025\full07-final-20261026"
$j = Get-Content (Join-Path $out "final-integrity.json") -Raw | ConvertFrom-Json
$bad = @()
foreach ($n in $j.files.PSObject.Properties.Name) {
    $p = Join-Path $out $n
    if (-not (Test-Path $p)) { $bad += $n; continue }
    if ((Get-FileHash $p -Algorithm SHA256).Hash.ToLower() -ne $j.files.$n.ToLower()) {
        $bad += $n
    }
}
"files=$(@($j.files.PSObject.Properties).Count) failed=$($bad.Count)"
```

Kết quả mong đợi: `files=14 failed=0`.

## 10. Trạng thái cuối

- Máy hiện đang bật; không có lệnh tắt máy nào đang chờ tại thời điểm lập báo cáo.
- Báo cáo này chỉ ghi nhận công việc xác minh và sao lưu; không thay đổi mã nguồn ứng dụng.
- Không có credential nào được ghi vào báo cáo.

## 11. Kiểm tra bổ sung ngày 26/10/2026, khoảng 11:11 (UTC+7)

- Đã chạy lại kiểm tra checkpoint cục bộ: **12/12 hash khớp**, 13 tệp gồm manifest.
- Đã chạy lại kiểm tra gói final cục bộ: **14/14 hash khớp**, 15 tệp gồm manifest.
- Đã nạp adapter bằng `safetensors` trong môi trường CPU hiện có: **64 tensor, 3.145.728 tham số**, toàn bộ hữu hạn, mỗi tensor có ít nhất một giá trị khác 0.
- Đã tải lại toàn bộ thư mục `full07-final` qua giao diện Google Drive của đúng tài khoản đã dùng để huấn luyện:
  - Tệp tải về: `C:/Users/HP/Downloads/full07-final-20261026T040701Z-1-001.zip`.
  - SHA-256 ZIP: `13afda96a357853d7bda5ed328028ea6000acd68f8b3ec9031001c896bd5a1bb`.
  - Đối chiếu trực tiếp nội dung ZIP: danh sách đúng 15 tệp, **14/14 hash trong manifest khớp**, 0 lỗi.
  - SHA-256 manifest vẫn là `cd62867a0c3272e02b525a72cf31ca21f9d7099ab262f8b81c5bfd43c4dda88d`.
- Bằng chứng chi tiết ngoài Git: `data/fine-tuning/colab-transfer-20261025/full07-final-reaudit-20261026.json`.
- Không chạy lại helper sao lưu cũ vào `full07-final` vì bản final đã tồn tại và phải được giữ nguyên.
- Trạng thái huấn luyện local vẫn `PAUSED_BY_USER`. Người dùng đã duyệt bước inference riêng: so sánh base và adapter trên bốn prompt tự soạn, không đọc hoặc tokenize tập test. Kết quả dịch được lưu riêng trong `data/fine-tuning/colab-transfer-20261025/inference-check-20261026/`; lượt tạo nội dung với schema rõ ràng nằm trong `inference-content-20261026/` cùng thư mục cha.

## 12. So sánh inference base và adapter bằng prompt riêng

### Phương pháp và phạm vi

- Hai cặp dịch và hai cặp tạo nội dung A1/B1 do người thực hiện tự soạn; không đọc hoặc tokenize record thuộc train/validation/test.
- Base: `Qwen/Qwen3.5-4B`, revision `851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a`, dùng cache hiện có, không tải mạng.
- Adapter: đúng SHA-256 tại mục 4.4. Đối chiếu toàn bộ 64 tensor sau khi PEFT nạp, tất cả tham số đều `requires_grad=False`.
- Cùng model đã nạp: tắt adapter cho nhánh base, bật adapter cho nhánh adapter; `eval()` và `inference_mode()`, seed 17, greedy, không constrained JSON decoding, không quantization.
- Môi trường inference chính: Windows CPU, Python 3.12.13, torch `2.10.0+cpu`, transformers `5.20.2`, peft `0.21.2`, safetensors `0.8.0`, trọng số `float32`. Đây không phải môi trường CUDA đã dùng để train. Hai cặp dịch FP16 ban đầu được giữ làm tham khảo riêng; toàn bộ bốn cặp được chạy lại ở FP32 để so sánh trong cùng điều kiện.
- Tokenizer checkpoint và cache model cho cùng effective backend và chat template. Lượt nội dung khẳng định điều này trước khi sinh.
- Không truncate đầu vào; tổng giới hạn đầu vào và đầu ra nằm trong 4.608 token. Giới hạn đầu ra: dịch 256, nội dung 512 token.
- Định dạng được kiểm bằng parser JSON nghiêm ngặt và schema; evidence phải là chuỗi con của lời thoại. Ý nghĩa, vai nói/nghe, continuity và rò đáp án được kiểm riêng bằng đọc câu trả lời.
- Đây là phép thử thăm dò trên bốn trường hợp, không phải benchmark đại diện hoặc kết quả trên tập test.

### Phát hiện mâu thuẫn trong prompt nội dung đóng băng

Artifact `artifacts/prompts/conversation-reviewed-collection-v12-cache-low-b1-20261024.json` có instruction yêu cầu wire `aligned-targets-v1` gồm `targets`, đồng thời cấm `slots/facts/questionVi`; phần `contentSystem` và schema cuối lại yêu cầu các trường vừa bị cấm. Hàm ghép prompt nối cả hai chỉ dẫn này. Hash canonical artifact `bcb7d51a140545b9362be1d40bc0cb5cd61965489c7f285eda7bf15dc59ae971` và hash system `58db5c8f7484649c959ba4dd020a62af31055c58c88959d011192466fa990d7c` khớp manifest đóng băng, nên đây là mâu thuẫn của đầu vào hiện có.

Lượt thăm dò đầu tiên sinh một câu A1 base theo wire `targets`; không thể quy toàn bộ lỗi schema của lượt đó cho model. Đã giữ nguyên raw output và ghi `probe-disposition.json`, dừng riêng tiến trình inference này, sau đó dùng một prompt riêng chỉ yêu cầu schema sáu trường cho cả base và adapter. Không sửa artifact, dataset, split hoặc hash đóng băng. Lượt chẩn đoán mâu thuẫn được loại khỏi bảng so sánh chính.

### Hai cặp dịch FP16 ban đầu, giữ làm tham khảo

| Trường hợp | Base | Adapter |
|---|---|---|
| Deadline: `by Wednesday evening`, chị/em gái, mã `nR7` | JSON không hợp lệ vì key đầu thiếu dấu nháy; dịch Wednesday thành thứ Năm, tự chọn “chị gái” | JSON/schema đạt; giữ hạn chót và mã; vẫn dịch sai thành thứ Ba và tự chọn “chị gái” |
| Xin nhận chiếc đèn `before Thursday morning`; anh/em trai mang hóa đơn nếu người nói không đến | JSON không hợp lệ; tự chọn “anh trai”, dịch lamp thành bóng đèn | JSON/schema đạt; giữ ngày, nghĩa “trước”, điều kiện, “chiếc đèn” và bất định “anh/em trai” |

Trong hai trường hợp dịch FP16 này, adapter đạt cấu trúc **2/2**, base **0/2**. Điều này không đồng nghĩa adapter đúng ngữ nghĩa 100%: lỗi ngày ở trường hợp đầu là lỗi nội dung thực tế.

Lượt nội dung FP16 với schema rõ ràng được dừng riêng sau khi xác minh đúng PID inference: chưa có câu trả lời hoàn chỉnh, tốc độ CPU quá chậm. Phép đo tám phép nhân ma trận-vector 4.096 × 4.096 ở tám thread mất 1,2852 giây với FP16 và 0,0327 giây với FP32. Đây là phép đo phép tính, không phải mức tăng tốc bảo đảm cho toàn bộ inference. Các tệp cũ được giữ nguyên; lượt bốn cặp FP32 có thư mục và metadata riêng tại `data/fine-tuning/colab-transfer-20261025/inference-fp32-20261026/`.

### Đọc kết quả tạo nội dung A1

- **Base:** lời thoại 17 từ, nhận vé tại main office và mang valid ID card; đủ hai chi tiết yêu cầu. JSON/schema đạt, hai fact khớp evidence trong lời thoại, không lộ đáp án trong câu hỏi. `questionVi` (“Người nghe cần biết hai thông tin gì?”) còn chung chung, dù hai câu hỏi slot rõ hơn.
- **Adapter:** lời thoại 20 từ, nhận vé tại blue box near the gate và mang small bag for the camera; đủ hai chi tiết theo prompt. JSON/schema và evidence đều đạt. Tuy nhiên, slot, câu hỏi tổng và task dùng “đặt vé” thay vì “nhận vé” cho `collect your ticket`; đây là lệch nghĩa câu hỏi, không được schema phát hiện.
- Hai bên giữ đúng vai nhân viên hướng dẫn khách, không dùng lịch sử cho lượt gặp mới. Vật cần mang không bị ràng buộc phải là giấy tờ; không chấm túi máy ảnh thành sai chỉ vì khác ID card của base.

### Kết quả chính FP32: đủ bốn cặp, tám đầu ra

| Kiểm tra tự động trên bốn trường hợp mỗi phía | Base | Adapter |
|---|---:|---:|
| JSON nghiêm ngặt hợp lệ | 2/4 | 4/4 |
| Schema và kiểm tra bổ sung: số fact/slot, độ dài, evidence, task type | 1/4 | 4/4 |
| Đầu ra bị cắt bởi giới hạn token | 0/4 | 0/4 |

Các tỷ lệ trên chỉ mô tả bốn prompt đã chạy, không phải tỷ lệ đúng ngữ nghĩa hoặc kết quả trên tập test. Riêng B1 base có JSON và schema hợp lệ nhưng trượt kiểm tra bổ sung do chỉ có 17 từ, dưới mức 30 từ.

| Trường hợp | Đánh giá base | Đánh giá adapter |
|---|---|---|
| Dịch deadline | Sai JSON, sai ngày và tự chọn độ tuổi chị gái | Đúng cấu trúc nhưng vẫn sai ngày và độ tuổi chị gái |
| Dịch lời xin nhận đèn | Sai JSON; tự chọn anh trai và thu hẹp lamp thành bóng đèn | Giữ được yêu cầu, ngày, điều kiện và quan hệ anh/em chưa rõ tuổi |
| Tạo A1, nhận vé | Đúng cấu trúc, hai thông tin có evidence; câu hỏi tổng còn chung chung | Đúng cấu trúc, nhưng “đặt vé” lệch nghĩa “nhận vé” |
| Tạo B1, tiếp nối nhận đèn | 17 từ; thiếu ý kiến/lý do; tự chuyển ngày đã chốt Friday sang Thursday, hỏi lại ý nghĩa ngày nhận | 34 từ, giữ Friday và thêm điều kiện sau 16 giờ; vẫn thiếu ý kiến được phát biểu rõ, lý do cho điều kiện chưa hợp lý |

Hai cặp dịch FP32 trùng nguyên văn cả bốn đầu ra FP16 tham khảo. Các lỗi dịch đã thấy không biến mất khi đổi FP16 sang FP32 trên CPU.

**B1 adapter cần đọc riêng phần nghĩa:** câu “Since you didn't pay the extra fee” đưa thêm tiền giả định khách chưa trả phụ phí, chưa có trong lịch sử. Điều kiện mới là nhận sau 16 giờ thứ Sáu; lý do “the shop is closed earlier” không giải thích hợp lý vì sao phải đến sau 16 giờ nếu không có thêm thông tin mở cửa lại. Model sinh điều kiện và lý do, thay vì một ý kiến có lý do thực tế như prompt yêu cầu. Fact/evidence khớp chuỗi và câu hỏi không lộ đáp án vẫn không giải quyết được các lỗi này.

### Bằng chứng, cách chạy lại và kết luận

- Lượt FP32 đã kết thúc với mã `0`, lưu đủ tám cặp `(case, arm)` duy nhất. Bốn cặp đều dùng cùng đầu vào cho base/adapter, không đầu ra nào chạm giới hạn token. Tổng thời gian sinh ghi trong tám row khoảng 1.267 giây.
- SHA-256 script `compare_fp32.py`: `b219669c29bf2dfeef779f8136d0472b729c0c75c40f7d7b91c4363a7440c217`.
- SHA-256 `prompts.json`: `a7a8c6009bb5eb8c87df0f97e806446423fea98c06eddd0a39c7debde07f7d5f`.
- SHA-256 `results.jsonl`: `4dac9379776b1892a7577d4458c9f08328afcdf34469a15dfa41317368a96e3b`.
- Script, prompt, raw output, metadata và đánh giá chi tiết nằm ngoài Git tại `data/fine-tuning/colab-transfer-20261025/inference-fp32-20261026/`. Giữ riêng toàn bộ lượt chẩn đoán trước đó.
- Adapter sau inference vẫn khớp SHA-256 tại mục 4.4; không sửa model, checkpoint hoặc bộ dữ liệu. Không có optimizer/training được khởi chạy.

Kiểm tra nhanh script mà không nạp model hoặc sinh lại:

```powershell
& 'C:/Users/HP/Desktop/English_dictition/data/fine-tuning/.venv-cpu/Scripts/python.exe' -X utf8 'C:/Users/HP/Desktop/English_dictition/data/fine-tuning/colab-transfer-20261025/inference-fp32-20261026/compare_fp32.py' --self-check
```

Script mặc định từ chối ghi đè `results.jsonl`; muốn làm lượt thử mới phải dùng thư mục riêng và giữ metadata tương ứng.

**Kết luận:** việc huấn luyện và tính toàn vẹn sao lưu đã có bằng chứng riêng; phép thử câu trả lời cho thấy cải thiện cấu trúc nhưng còn lỗi nghĩa. Chưa triển khai model. Trước một lượt đánh giá rộng hơn cần thống nhất wire contract của prompt nội dung trong phiên bản mới, giữ nguyên artifact/dữ liệu đóng băng của full07, và kiểm tra các lỗi ngày, dịch câu hỏi, continuity và ý kiến B1. Tập test vẫn chưa được sử dụng; huấn luyện local vẫn `PAUSED_BY_USER`.
