# Tiếp tục cải thiện inference full07 — 26/10/2026

## Trạng thái hiện tại

Đã lưu và kiểm chứng native **308 đầu ra hoàn tất** trong toàn bộ study, trên 30 tình huống tự soạn; số này gồm các lượt lặp và các ứng viên bị loại. Đây không phải 308 tình huống độc lập. Trong đó, 60 đầu ra đầu được ghi ở [báo cáo trước](full07-inference-improvement-gpu-20261026.md), 248 đầu ra tiếp nối có gói bằng chứng và kiểm tra riêng.

**Vòng13 đạt gate trong phạm vi dịch giao nhận có glossary:** 16/16 đầu ra hồi quy đã quan sát giữ đủ nghĩa; trên bốn source mới, ứng viên đạt4/4 ở cả base và adapter, cấu hình cũ `v2` đạt0/4 ở mỗi nhánh. Cả16 đầu ra xác nhận đều qua kiểm tra native/cấu trúc. Đây là cải thiện pipeline có trợ giúp, chưa chứng minh thay đổi năng lực trọng số hoặc adapter thắng base về chất lượng tổng quát. Chưa đưa ứng viên vào ứng dụng.

Lịch sử thất bại được giữ nguyên: vòng09 sửa mất màu; vòng10 thêm thuật ngữ vật lý; vòng11 sửa mã/phủ định nhưng base mất `locker`; vòng12 sửa cụm này nhưng base còn bỏ phủ định của fact và đổi Chủ nhật thành sáng thứ Sáu. Vòng13 gắn glossary vào từng mệnh đề/fact, đóng băng trước generation lúc `2026-10-27T15:18:42.612741+00:00`. Không hạ gate, thay source hoặc chữa đầu ra trong lượt xác nhận.

Console vòng09 cũ có tám output metadata, `COMPLETED`, exit0 lúc `2026-10-26T13:07:59.219513+00:00`. Chưa khôi phục raw/token IDs của lượt cũ, nên không cộng tám output console-only này vào tổng. Lượt retry trên T4 mới có gói raw riêng: đủ8 đầu ra được kiểm chứng native, nghĩa4/4 ở mỗi nhánh và giữ màu vàng. Không gọi retry là tình huống độc lập mới.

**Khôi phục ngày 27/10/2026:** đã đăng nhập và điều khiển lại đúng notebook inference. Phiên cũ không còn được liệt kê trong Manage sessions. Đã kết nối runtime T4 mới theo phạm vi inference được duyệt: T4 15.360 MiB, GPU memory used0 trước inference; không có process runner/trainer hoặc thư mục vòng09 tại các đường dẫn cũ trong runtime này. Không suy ra nguyên nhân phiên cũ kết thúc. Sau xác nhận tại thời điểm cài, đã cài riêng wheel torchao0.17.0 với hash khóa và `--no-deps`; Python3.13.16, PyTorch/CUDA cùng sáu thư viện còn lại giữ nguyên. Import CUDA/torchao/PEFT và self-check đúng biến thể đều PASS. Gói khôi phục15 tệp đã xác minh hash trước giải nén. Giữ lỗi kiểm hash lúc upload chưa xong và lỗi self-check dùng default variants; cả hai xảy ra trước generation và đã được xử lý đúng nguyên nhân.

## Phạm vi và cách chấm

- Chỉ thay prompt/ngữ cảnh inference; không thay trọng số, model, adapter hoặc môi trường. Đây là cải thiện pipeline có trợ giúp thuật ngữ.
- Giữ Qwen3.5-4B revision `851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a`, T4 FP16, LoRA FP32, greedy seed17; cùng runner SHA-256 `23201e6379a2e1eae283dd547e5392f88322160c69ca9647806d890935fd65ee`.
- Adapter SHA-256 `1670910dcad7f1e8f66d1dd74e6297fe914c2067d0c26240b72b25fd36c922bf` giữ nguyên; control pristine/LoRA-disabled khớp, LoRA-active thay đổi logit hữu hạn, đủ 64 tensor được kiểm.
- Mọi gói hoàn tất giữ metadata, input hashes, token IDs, raw output, log, completion và exit code. Giải mã độc lập từ token IDs để chấm; không chữa JSON/ngôn ngữ sau sinh. Không truncate; đầu vào cộng giới hạn đầu ra phải nằm trong 4.608 token.
- Kiểm cấu trúc gồm JSON/schema, hai slot/fact căn chỉnh, độ dài, evidence nguyên văn và phần trả lời cuối hợp lệ. Chấm nghĩa thêm ngày/ranh giới thời gian, phủ định/điều kiện, tuổi quan hệ, mã/màu/tài liệu, vai nói, continuity và câu hỏi không lộ đáp án.
- Người chấm nghĩa là trợ lý, không có hội đồng độc lập. B1 được thu hẹp vào ý kiến về chính đồ vật cùng lý do mang/cất giữ và một điều kiện giấy tờ khi nhận đồ; không suy rộng sang mọi dạng B1.
- Không đọc/tokenize tập train/validation/test; local training giữ `PAUSED_BY_USER`. Không train, deploy, sửa checkpoint hoặc shutdown runtime. Ngoại lệ môi trường duy nhất: khôi phục đúng torchao0.17.0 bằng wheel/hash khóa và `--no-deps` sau xác nhận của chủ tài khoản; các thư viện khác không đổi.

## Các vòng phát triển và xác nhận

| Vòng | Đầu ra | Kết quả và quyết định |
|---|---:|---|
| 04 `grounded` | 12 | Dịch khá hơn; base thiếu độ dài B1, adapter hỏi sai đối tượng. Loại. |
| 05 `grounded-length` | 12 | Cấu trúc 12/12, còn bịa việc trả bóng và thêm điều kiện ID. Loại. |
| 06 `collection-plan` | 12 | Cấu trúc 12/12, adapter bịa dây điện mới; ảnh hưởng ví dụ sang đồ vật khác. Loại. |
| 07 `main-item` | 12 | Cấu trúc/nghĩa chấp nhận 12/12 trên sáu câu phát triển; đóng băng trước bốn câu riêng. |
| Bốn câu riêng đầu: v2 + main-item | 16 | Main-item nghĩa base 3/4, adapter 4/4; base còn chữ Trung Quốc và bỏ “gốc” của biên lai. V2 lần lượt 1/4 và 0/4. Chưa đạt gate. |
| 08 `lexical-complete`: sáu câu phát triển + bốn câu đã quan sát | 20 | Cấu trúc/nghĩa chấp nhận 20/20. Mười đầu ra content khớp token với vòng07; giữ đánh giá cũ và các giới hạn diễn đạt. |
| Bốn câu xác nhận mới: v2 + lexical-complete | 16 | V2 nghĩa base 1/4, adapter 0/4; ứng viên base 4/4, adapter 3/4. Adapter bỏ “yellow”. Giữ thất bại và tiếp tục kiểm màu. |
| 09 retry: bảng màu chung | 8 | Cấu trúc/nghĩa4/4 mỗi nhánh; yellow được giữ. Đây là lượt lặp riêng, console cũ vẫn giữ. |
| Xác nhận màu/vật thể: v2 + ứng viên09 | 16 | V2 nghĩa1/4 mỗi nhánh; ứng viên base2/4, adapter3/4. Base dịch chìa khóa vật lý thành phím; folder chưa rõ vật thể. Loại. |
| 10: hồi quy vật thể và bốn câu cũ | 16 | Nghĩa8/8 mỗi nhánh; giữ bìa hồ sơ, chìa khóa, màu, ngày và điều kiện. |
| Xác nhận10: v2 + ứng viên10 | 16 | V2 nghĩa0/4 mỗi nhánh; ứng viên base3/4, adapter4/4. Base thiếu mã và phủ định ở câu grey folder. Loại theo gate đã đóng băng. |
| 11: mã và phủ định, hồi quy + development | 36 | Native/cấu trúc36/36; sửa mã/phủ định. Base bỏ `locker` ở cùng câu xanh lá, được lặp trong hai suite; loại trước khi sinh fresh. Sáu content output khớp token vòng08, giữ giới hạn cũ. |
| 12: cụm danh từ vật lý | 8 | Hai nhánh giữ `locker`, mã và các control; nghĩa4/4 mỗi nhánh. Chỉ các source context có `locker key` thay đổi so với11. |
| Xác nhận12: v2 + ứng viên12 | 16 | Native/cấu trúc16/16. V2 nghĩa base0/4, adapter1/4; ứng viên base2/4, adapter4/4. Base mất phủ định ở fact pink-folder và thay Chủ nhật bằng sáng thứ Sáu ở blue-bag. Loại. Audit thuật ngữ cũng bắt đúng hai hàng lỗi. |
| 13: căn chỉnh glossary theo mệnh đề/fact | 16 | Native/cấu trúc/thuật ngữ16/16, nghĩa8/8 mỗi nhánh trên các source đã quan sát. Giữ cả phủ định của fact và hai mốc thời gian, cùng control locker/màu/mã. |
| Xác nhận13: v2 + ứng viên13 | 16 | Native/cấu trúc16/16. V2 nghĩa0/4 mỗi nhánh; ứng viên4/4 mỗi nhánh, audit8/8. Đạt gate đã đóng băng trong phạm vi này. |

Tổng tiếp nối:248; cộng60 trước đó thành308. Các bộ đã được xem rồi dùng sửa prompt được gọi là **hồi quy đã quan sát**, không gọi lại là kiểm tra mù. Mỗi lần chọn có timestamp/input hash trước generation; không sửa ứng viên giữa đợt xác nhận để tính lại là đạt. Bốn source vòng11 chưa được sinh; trước freeze12 đã bổ sung `locker` vào source silver-key ở cả baseline/candidate để kiểm cụm danh từ, giữ cả bản input cũ. Bốn câu xác nhận13 vẫn theo các mẫu giao nhận tương tự, không phải bằng chứng tổng quát hóa rộng hoặc đánh giá độc lập.

### Bốn câu xác nhận13

| Source mới | V2 base / adapter | Ứng viên base / adapter | Điều đã đối chiếu |
|---|---|---|---|
| Bìa hồ sơ tím, không mở trước sáng thứ Tư | Sai / Sai | Đạt / Đạt | Giữ hành động/phủ định trong fact; em trai, unless, biên lai gốc và pL4. |
| Túi vàng, nhận sau tối thứ Ba, không trả trước sáng thứ Bảy | Sai / Sai | Đạt / Đạt | Không lẫn hai mốc thời gian; giữ chị gái và quyền ký nhận. V2 đổi thứ Ba thành thứ Hai. |
| Chìa khóa tủ vàng kim, chậm nhất tối thứ Hai | Sai / Sai | Đạt / Đạt | Giữ anh trai, câu xin phép, deadline và phiếu cM8 thay biên lai gốc nếu cần. |
| Bìa hồ sơ đen, giữ đến chiều thứ Năm | Sai / Sai | Đạt / Đạt | Giữ tuổi chưa rõ của chị/em gái, điều kiện xuất trình biên lai gốc và kD9. |

Ứng viên đóng băng: `round13/candidate/experiment.json`, SHA-256 `fcdd5554cb0cf2364f4539138858be3014fc02a4379ded053533fad1af75c7b2`. V2 còn chữ Trung Quốc, sai vật thể hoặc sai thời gian ở cả bốn source; không chỉ thất bại do cách diễn đạt. Đánh giá nghĩa do trợ lý thực hiện; JSON PASS tự nó không chứng minh dịch đúng.

### Điều đã sửa và giới hạn còn lại

Ngữ cảnh content giữ chính đồ vật, quyền sở hữu và lịch đã chốt; nhân viên giao đồ cho người học, không yêu cầu mang lại món đồ đang nằm tại quầy. Ý kiến phải nói về đồ vật chính, có lý do thực tế, cùng đúng một điều kiện giấy tờ và evidence phủ hết fact. Cách này tránh bịa sửa đồ mượn hoặc thêm phụ kiện mới.

Dịch dùng bảng thuật ngữ suy ra từ nguồn, danh sách cụm bắt buộc trong câu dịch và cùng quy tắc cho facts. Vòng08 thêm `show → xuất trình`, `badge → huy hiệu`, `original receipt → biên lai gốc` cùng yêu cầu tiếng Việt hoàn chỉnh. Tuy nhiên glossary màu cũ chưa bao phủ yellow; adapter bỏ thuộc tính này dù giữ đúng phủ định, ngày, anh trai và biên lai gốc. Không che lỗi bằng điểm JSON.

Vòng09 mở rộng14 màu thông dụng, vòng10 thêm `key → chìa khóa`, `folder → bìa hồ sơ` trong ngữ cảnh giao nhận vật thể. Không áp dụng quy tắc key này cho bàn phím hoặc mật mã. Giữ system prompts và runner. Vòng11 thêm định danh chữ–số nguyên văn cùng `do not/must not/cannot`; vòng12 thêm cụm đầy đủ `locker key`, tránh mất loại vật thể khi dùng thuật ngữ chung `key`. Đây là bổ sung input có trợ giúp tiếng Việt; không phải thay đổi trọng số hoặc phép so sánh dịch không trợ giúp.

Vòng13 thêm `sourceTermAlignment` vào context: từng mệnh đề và từng fact mang phần glossary tương ứng, chọn biểu thức nguồn dài nhất khi có chồng lấn. Ví dụ, mốc nhận đồ và mốc cấm trả đồ được đặt cạnh đúng mệnh đề nguồn; phủ định của fact được giữ trong glossary riêng của fact đó. Source, facts gốc, system prompts, schema, runner và trọng số không đổi. Input không chứa bản dịch hoàn chỉnh và không chữa output sau sinh. Các self-check kiểm ranh giới từ, chọn biểu thức dài nhất, tính idempotent và bảo toàn context cũ. Đây vẫn là quy tắc trong phạm vi giao nhận vật thể, chưa phải bộ phân tích cú pháp tổng quát.

Đây là lexicon có giới hạn ngữ cảnh, chưa phải xử lý thuật ngữ production. Base gọi giấy tờ nhận máy ảnh là `return form`, adapter lặp chị/em gái hơi vụng. Một output B1 base35 từ nằm trong hard `levelProfile`30–90 nhưng không đạt mục tiêu45–55 của system; phần giải thích cùng điều kiện giấy tờ chưa luôn đầy đủ. Rubric trước chấp nhận nghĩa/profile bounds, **không chứng minh tuân thủ mọi hướng dẫn**. Chưa có cơ sở chọn adapter thắng base về chất lượng tổng quát.

## Bằng chứng private và chạy lại kiểm tra

Root ngoài Git:

`C:/Users/HP/Desktop/English_dictition/data/fine-tuning/colab-transfer-20261025/inference-improvement-20261026/`

| Gói | SHA-256 |
|---|---|
| round04-evidence.zip | `fa17afe33783415842d7d466f9b8d3e4755157c3328029a86295c3ecbb0c7baf` |
| round05-evidence.zip | `4e76a583346a40c805a468f9746010bfd1ce8fc8853c87133eca47a0199e13ea` |
| round06-evidence.zip | `c4ef01327c327e9a3d5099463f37a9e904bf44cc3aff5ac7304aa9a3ef4c608b` |
| round07-evidence.zip | `bbebe15b4f93ea6aec68f22fda495f1a55a31816ac8ec84fcbb5f18c8025b8ff` |
| holdout-evidence.zip | `8d2f79449147f9ad74aad38954f0bee123533fa116fa0df9ed074643994b1531` |
| round08-evidence.zip | `56c093bff710c85cab161a38a2ccd0f61d622ac30849704e921facb9d3db9fcf` |
| confirmation-evidence.zip | `2549fe7013598cf577be03be6bb34f3ec43ca24411da01b9e3048f5d766f80de` |
| round09-retry-evidence.zip | `39314ca35326b44a9a22fa3ed6feab153ca6ef7d4440517858ceb8fe4087aed0` |
| colour-confirmation-evidence.zip | `cb338c91c23840dca282152da7db44d691c80b29df858cd9d26288014352f052` |
| round10-regression-evidence.zip | `793d7697b7bbe47c19ce3e2a0d89dd0202171251d983b70cc13abf87e7858686` |
| round10-confirmation-evidence.zip | `7aa2680be11a03a0e099d08d9255689cf454068feb0c6411ed2425b839c5bb25` |
| round11-regression-evidence.zip | `b088ae0e6efddcc1331adbfb1a6f39f6749eba2ed48979f5600001bed640a6e3` |
| round11-development-evidence.zip | `ba57d740b98959f9ba1f8521e00f09e90835515a03ec06a9f80ad280af4fa420` |
| round12-regression-evidence.zip | `52403fa5922d545cdf8a80a008abf796cec760e6fb940c114150e615e96a1760` |
| round12-confirmation-evidence.zip | `5c5dd429c4efea1ee564bf0d2781500f0f67ec46cf1d178ffe59ff09a61ef2f7` |
| round13-regression-evidence.zip | `425a519641587f417de3b8591400675dda18dad5d9c9a782658eb8554caeba93` |
| round13-confirmation-evidence.zip | `b1ce533ea3309a13d65aece83792131cb8fb2351128ece09230e1aabb0725444` |

Các `semantic-review.json`, `holdout-review.json`, `confirmation/freeze.json` và `round07/holdout-freeze.json` giữ đánh giá, thất bại và thứ tự chọn ứng viên. Lần export holdout đầu gặp SyntaxError trước khi chạy; giữ lỗi, chỉ thử lại export bằng ô mới, không chạy lại inference.

```powershell
$evidenceRoot = 'C:/Users/HP/Desktop/English_dictition/data/fine-tuning/colab-transfer-20261025/inference-improvement-20261026'
$taskPython = 'C:/Users/HP/Desktop/English_dictition/data/fine-tuning/.venv-cpu/Scripts/python.exe'
& $taskPython -X utf8 "$evidenceRoot/verify_continuation.py"
& $taskPython -X utf8 "$evidenceRoot/verify_colour_continuation.py"
& $taskPython -X utf8 "$evidenceRoot/verify_latest_continuation.py"
& $taskPython -X utf8 "$evidenceRoot/verify_round13.py"
& $taskPython -X utf8 "$evidenceRoot/audit_supplied_terms.py" --self-check
```

Bốn verifier kiểm100+56+60+32 row tiếp nối, không sinh lại và không coi toàn vẹn là chất lượng đạt. Các verifier giữ kết luận loại vòng11/12, xác minh đúng hai hàng audit thất bại ở vòng12 và kiểm gate cùng chronology/hash của vòng13. `audit_supplied_terms.py` chỉ kiểm thuật ngữ đã cung cấp và CJK; không thay thế đọc nghĩa đầy đủ. Vòng10 là phản ví dụ cụ thể: audit PASS nhưng bỏ mã/phủ định chưa có trong danh sách; đánh giá nghĩa vẫn loại ứng viên.

Ngày27/10, các verifier100,56,60 và32 row tiếp nối đều exit0. Archive/file hashes, input manifest, native-token decode, cấu trúc, control và thứ tự đóng băng trước generation được xác minh. Verifier60 giữ quyết định loại vòng12; verifier32 xác minh ứng viên13 đạt gate. Self-check bộ kiểm thuật ngữ/căn chỉnh, Python compile, liên kết báo cáo, UTF-8 và `git diff --check` đạt. Log private gồm `continuation-check-20261027.log`, `latest-check-20261027.log` và `round13-check-20261027.log`. Kiểm toàn vẹn không loại bỏ lỗi nghĩa của các lượt trước.

## Kết luận và giới hạn sử dụng

Chọn context vòng13 cho các phép thử tiếp theo trong phạm vi dịch giao nhận vật thể có glossary. Không cần train lại để có cải thiện đã đo trong đợt này. Chưa tích hợp vào ứng dụng hoặc deploy. Việc chọn không xóa các giới hạn B1, không chứng minh adapter thắng base và không chứng nhận production. Nếu mở rộng miền hoặc triển khai, cần đánh giá thêm với người chấm độc lập và kiểm các lỗi đã lưu; không dùng lại các source đã quan sát làm bằng chứng kiểm tra mù.

Ảnh private `round13/final-gpu-evidence-20261027.png` cho thấy bốn lượt GPU, mỗi lượt8 đầu ra, exit0, adapter giữ nguyên và không train; điểm nghĩa nằm trong review riêng, không suy ra từ ảnh. Console vòng09 cũ chỉ chứng minh runner báo hoàn tất8 output; tám row console-only đó vẫn không được cộng vào tổng. Ảnh tổng kết60 row ở báo cáo cũ là bằng chứng lịch sử.
