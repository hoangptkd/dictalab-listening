# Thử cải thiện inference full07 trên GPU — 26/10/2026

> Cập nhật tiếp nối: xem [study pipeline có trợ giúp thuật ngữ](full07-inference-guided-pipeline-20261026.md), tổng 160 đầu ra hoàn tất đã lưu. Báo cáo dưới giữ kết luận tại mốc 60 đầu ra; ứng viên tiếp nối vẫn chưa vượt đầy đủ gate vì adapter bỏ màu vàng trong một câu xác nhận mới.

## Kết luận

Đã hoàn tất ba lượt so sánh trên Tesla T4, lưu **60 đầu ra** từ sáu tình huống phát triển tự soạn. Không có ứng viên đạt đầy đủ điều kiện chọn: không giảm kiểm tra cấu trúc/nghĩa và phải tốt hơn prompt v2. **Chưa chọn prompt hoặc model để đưa vào ứng dụng.** Bốn tình huống riêng dành cho bước kiểm tra tiếp theo vẫn chưa chạy vì chưa có ứng viên vượt vòng phát triển.

Đây là kết quả tiếp nối [đối chiếu CPU/GPU](full07-inference-diagnostics-20261026.md) và [prompt v2](full07-inference-prompt-followup-20261026.md). Training đã hoàn tất và sao lưu được ghi riêng trong [báo cáo training](full07-training-verification-backup-20261026.md); các lỗi inference dưới đây không phủ định bằng chứng checkpoint hoặc tự chứng minh cần huấn luyện lại.

## Phạm vi giữ nguyên

- Base `Qwen/Qwen3.5-4B`, revision `851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a`.
- Adapter checkpoint-200 SHA-256 `1670910dcad7f1e8f66d1dd74e6297fe914c2067d0c26240b72b25fd36c922bf`; đủ 64 tensor khớp sau nạp.
- T4, base FP16, LoRA FP32, greedy, seed17, không quantization. Torch `2.11.0+cu130`, torchao `0.17.0`, Transformers `5.20.2`, PEFT `0.21.2`; không cài thêm trong bộ thử cải thiện.
- Kiểm hash bảy tệp base theo metadata phát hành, tokenizer/template khớp adapter. Control tắt LoRA có logit khớp pristine base; bật LoRA đổi logit hữu hạn. Đây là kiểm tính toán, không phải chứng nhận chất lượng.
- Local training giữ `PAUSED_BY_USER`; không đọc/tokenize dữ liệu train/validation/test, không thay dataset, checkpoint hoặc cấu hình sản phẩm.
- Không constrained decoding, không sửa từ/ngày/cú pháp sau sinh. Việc chấm cấu trúc dùng giải mã độc lập từ token IDs, bỏ special tokens; raw output và preview gốc vẫn được giữ.

Trong mỗi cặp base/adapter, đầu vào và giới hạn sinh khớp nhau. Giới hạn toàn bộ đầu vào cộng đầu ra là 4.608 token; không truncate. Nhánh `base` trong bộ thử là PEFT-disabled sau control pristine, không tự nhận từng câu đã được sinh lại bằng model chưa gắn LoRA.

## Thiết kế và kết quả cấu trúc

Sáu trường hợp gồm ba câu dịch, A1 nhận vé và hai tình huống B1 nhận đồ có lịch sử. Đây là các trường hợp đã biết lỗi/control ở công việc trước, được dùng để phát triển prompt; không phải benchmark mù.

| Lượt hoàn tất | Cấu hình | Base: JSON / cấu trúc | Adapter: JSON / cấu trúc | Số đầu ra |
|---|---|---:|---:|---:|
| 18:24 UTC+7 | v2 | 6/6 · 6/6 | 6/6 · 5/6 | 12 |
| Cùng lượt | concise | 6/6 · 4/6 | 6/6 · 4/6 | 12 |
| 18:34 UTC+7 | v2 lặp lại | 6/6 · 6/6 | 6/6 · 5/6 | 12 |
| Cùng lượt | fewshot | 6/6 · 6/6 | 6/6 · 6/6 | 12 |
| 18:45 UTC+7 | guarded | 6/6 · 5/6 | 5/6 · 5/6 | 12 |

Các con số là kiểm JSON/schema và cấu trúc bổ sung, **không phải tỷ lệ đúng nghĩa**. Không đầu ra nào trong 60 row hoàn tất chạm giới hạn token. Ba worker đều thoát với `exitCode=0`; đã kiểm PID không còn trước khi xuất bằng chứng. Mười hai đầu ra v2 lặp lại khớp từng token với v2 của lượt trước trên cùng GPU; không tính chúng thành tình huống độc lập mới.

Các cấu hình phát triển nối tiếp:

1. `concise`: rút ngắn chỉ dẫn; vẫn sai ngày/tuổi quan hệ, sai continuity, vượt hoặc thiếu độ dài và có evidence không phải một đoạn nguyên văn.
2. `concise-thinking`: giới hạn 2.048 token; các lượt đã hoàn tất không ra được phần trả lời cuối trước giới hạn. Dừng thích nghi vì không thể đạt điều kiện chọn; giữ nguyên lỗi và giới hạn bằng chứng ở phần dưới.
3. `fewshot`: thêm ví dụ tự soạn khác tình huống đang chấm. Sửa deadline và A1, nhưng adapter vẫn tự chọn anh trai, base/adapter đổi Saturday thành Friday; adapter gọi đèn đã sửa là đèn mới.
4. `guarded`: dùng cùng runner, thêm bảng thuật ngữ tạo bằng quy tắc từ câu nguồn/fact và ngữ cảnh đồ đang chờ nhận. Câu nguồn, facts, lịch sử và schema gốc giữ nguyên. Đây là phép thử có trợ giúp thuật ngữ, không phải dịch không trợ giúp hoặc thay trọng số.

## Đọc nghĩa lượt guarded

| Trường hợp | Base | Adapter |
|---|---|---|
| Deadline Wednesday | Giữ thứ Tư, chị/em gái và mã; câu dịch mất nghĩa hạn chót `by`, dù fact có “chậm nhất”. “Xanh” mơ hồ và diễn đạt trình mã lỗi | Giữ thứ Tư, chị/em gái và mã; vẫn mất nghĩa `by` trong câu dịch, dù fact đúng; “xanh” mơ hồ |
| Xin nhận đèn trước Thursday morning | Giữ câu hỏi, trước sáng thứ Năm, anh/em trai, biên lai và điều kiện | Giữ các nghĩa này; sửa được lỗi tự chọn anh trai của fewshot |
| A1 nhận vé | Hợp lệ, gọn, hai chi tiết có evidence và câu hỏi không lộ đáp án | Raw JSON thiếu dấu nháy mở của giá trị `bring_item`; bị loại, không tự sửa |
| B1 nhận đèn | Giữ Friday, cùng chiếc đèn, ý kiến/lý do và điều kiện; chỉ 29 từ, dưới tối thiểu 30 | Giữ các yêu cầu trên và đủ cấu trúc/độ dài trong trường hợp này |
| Đồng hồ trước Saturday afternoon | Giữ thứ Bảy, ranh giới trước, chị/em gái, phiếu nhận đồ, hành động để đồ và điều kiện | Giữ các nghĩa này; sửa được lỗi đổi ngày của fewshot |
| B1 nhận máy ghi âm đã mượn | Giữ Monday nhưng bịa việc sửa máy; evidence ý kiến chỉ bao phủ lý do | Giữ Monday nhưng bịa việc sửa máy và cơ cấu băng; sai continuity của tình huống mượn |

Bảng thuật ngữ đúng không bảo đảm model áp dụng mọi mục đồng thời. Ở deadline, cả hai nhánh dùng nghĩa “chậm nhất” cho fact nhưng bỏ nó trong câu dịch. Đây là lỗi quan sát trên đầu ra nguyên bản, không bị che bằng thay từ sau sinh.

Ngữ cảnh mới của guarded có một lỗi thiết kế: `continuityConstraint` dùng “original repaired item” cho mọi lịch sử, kể cả đồ mượn; ví dụ fewshot cũng thiên về sửa đồ. Đây là chỉ dẫn thêm của phép thử, không có trong nguồn gốc tình huống mượn. Nó làm phép thử không còn tách riêng ảnh hưởng của trọng số đối với lỗi bịa sửa máy. Không quy lỗi này riêng cho adapter hoặc dataset. Chưa sửa cấu hình thử rồi chạy tiếp nhiều vòng trên cùng sáu câu để tìm một tỷ lệ đẹp.

## Lỗi và giới hạn bằng chứng được giữ nguyên

- Lượt đầu dừng trước khi nạp model vì `snapshot_download(local_files_only=True)` đòi cả tệp không dùng trong cache. Retry chỉ giới hạn đúng bảy tệp đã xác minh bằng `allow_patterns`; không tải thêm/cài package hoặc bỏ kiểm hash. Giữ script/lỗi/exit code cũ và dùng thư mục kết quả mới.
- Lượt thinking dừng bằng SIGINT sau khi xác minh đúng command line PID 12011. Console ghi ba đầu ra thinking chạm 2.048 token chưa có phần trả lời cuối và lượt thứ tư bị ngắt. Quyết định dừng là thích nghi, không được đăng ký trước.
- Raw JSONL của lượt thinking có 25.750 NUL trong 72.334 byte; nguyên nhân chưa xác minh. Tệp từng được mở trong editor Colab trong lúc worker ghi. Chỉ khôi phục đọc được sáu object hoàn chỉnh, gồm hai object thinking; console có bằng chứng lượt thinking thứ ba. Không sửa tệp gốc hoặc dùng nó như bộ kết quả đủ 11 row. Các kết quả hoàn tất khác được kiểm riêng; không mở chúng trong editor khi worker đang ghi.
- Preview của runner giữ special tokens `im_end/endoftext`. Verifier dùng tokenizer local giải mã lại native IDs với `skip_special_tokens=True`; không thay nội dung ngôn ngữ, không chữa JSON và không ghi đè raw preview. Không coi đây là một bước sửa câu trả lời.
- Nhận xét nghĩa do trợ lý đọc, không phải hội đồng người chấm độc lập. Sáu tình huống và các lượt chỉnh thích nghi không chứng nhận chất lượng tổng quát.

## Quyết định và bước tiếp theo

**Không chọn concise, thinking, fewshot hoặc guarded; `releaseAllowed=false`.** Điều kiện chọn ban đầu yêu cầu không hồi quy cấu trúc/nghĩa trên cả hai nhánh và có cải thiện so với v2. Không ứng viên đạt điều kiện này, nên chưa chạy bốn tình huống riêng để chọn ứng viên. Chúng là prompt tự soạn, tách biệt với tập test SFT vẫn không được đụng tới.

Đã dừng các lượt inference phát triển sau khi kiểm worker thoát; runtime không bị shutdown. Không deploy, train lại hoặc thay cấu hình ứng dụng. Bước phát triển sau nên sửa cách dựng ngữ cảnh theo đúng trạng thái mượn/sửa và thiết kế bước từ chối đầu ra sai cấu trúc/nghĩa, rồi đóng băng một ứng viên trước khi đánh giá trên tình huống mới. Báo cáo này chưa triển khai các thay đổi đó và chưa có cơ sở chọn model thắng.

## Bằng chứng và xác minh lại

Thư mục private ngoài Git:

`C:/Users/HP/Desktop/English_dictition/data/fine-tuning/colab-transfer-20261025/inference-improvement-20261026/`

| Gói | Kết quả chính | SHA-256 raw results |
|---|---|---|
| `round01-evidence.zip` | Lượt lỗi đầu, thinking đã dừng, nonthinking 24 row, inputs và logs | Nonthinking: `f2d6951d2d34a2e8d9ba256510facd10b276e5c5d6415a12bf5e3b0a889cb7ff` |
| `round02-evidence.zip` | v2/fewshot, 24 row, inputs và log | `a462776e41d92d69c8c7b5872fb3a56fc20355b2ee62bb31373cf1065f307dd7` |
| `round03b-evidence.zip` | guarded, 12 row, inputs và log | `c4549f08687596d20fdfece3f894e4a9a5463c87eca16b7691e787c7acb9a790` |

Gói guarded có SHA-256 `eaecd8185d19f4d7b16da0c2187981952ca169d0d8aa646f1113c5c8b71c7c23`; đủ tám tệp trong export manifest khớp sau tải về. Cùng runner SHA-256 `23201e6379a2e1eae283dd547e5392f88322160c69ca9647806d890935fd65ee` được dùng cho các lượt hoàn tất. `round01-semantic-review.json`, `round02/semantic-review.json`, `round03b/semantic-review.json` giữ đánh giá và giới hạn từng lượt. Ảnh `gpu-study-completed.png` ghi tổng kết thật từ notebook. Không đưa model, raw output, dataset hoặc credential vào Git.

Kiểm tra bằng chứng local, không nạp model hoặc sinh lại:

```powershell
$evidenceRoot = 'C:/Users/HP/Desktop/English_dictition/data/fine-tuning/colab-transfer-20261025/inference-improvement-20261026'
& 'C:/Users/HP/Desktop/English_dictition/data/fine-tuning/.venv-cpu/Scripts/python.exe' -X utf8 "$evidenceRoot/verify_study.py"
```

Verifier kiểm hash gói/tệp, manifest đầu vào, row count/uniqueness, metadata, control, native token IDs, schema/cấu trúc và quyết định giữ holdout chưa chạy. Python compile/self-check và UTF-8 được kiểm riêng. Việc xác minh toàn vẹn bằng chứng không biến các đầu ra bị loại thành đạt chất lượng.
