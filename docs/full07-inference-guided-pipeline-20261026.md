# Tiếp tục cải thiện inference full07 — 26/10/2026

## Trạng thái hiện tại

Đã lưu **160 đầu ra hoàn tất** trong toàn bộ study, trên 14 tình huống tự soạn; số này gồm các lượt lặp và các ứng viên bị loại. Đây không phải 160 tình huống độc lập. Trong đó, 60 đầu ra đầu được ghi ở [báo cáo trước](full07-inference-improvement-gpu-20261026.md), 100 đầu ra tiếp nối có gói bằng chứng và kiểm tra riêng.

Ứng viên `lexical-complete` cải thiện rõ việc giữ ngày, hạn chót, quan hệ anh/chị chưa rõ tuổi, ngữ cảnh đồ đang chờ nhận, câu hỏi và evidence. Trên 14 tình huống đã quan sát, base đạt nghĩa chấp nhận được 14/14, adapter 13/14 theo rubric dưới đây. **Chưa đạt điều kiện chọn không hồi quy ở cả hai nhánh:** adapter bỏ màu vàng ở một câu xác nhận mới. Không đưa ứng viên vào ứng dụng và không tuyên bố đã hoàn tất mục tiêu chất lượng.

Đã khởi chạy vòng09 để kiểm tra bản bổ sung bảng màu chung. Lần đọc log cuối ghi PID 43273, năm đầu ra hoàn tất và đang sinh đầu ra thứ sáu. Sau đó công cụ điều khiển Chrome mất kết nối đọc tab; tab vẫn có trong danh sách. Chưa xác minh exit code, đủ tám đầu ra hoặc nghĩa của vòng09. Không chạy trùng, reload hoặc suy ra worker đã dừng. Hai tệp JSON cho bốn câu xác nhận màu mới đã tải lên; **chưa khởi chạy** chúng.

**Kiểm tra lại ngày 27/10/2026:** kết nối công cụ Chrome đã hoạt động, nhưng tab Colab cũ không còn. Mở lại đúng notebook inference hiện có cho thấy `Sign back in` và lỗi `invalid authentication credentials`; Colab ghi tài khoản Google đã đăng xuất ở tab khác. Nút Sign in chưa khôi phục được phiên. Chưa đọc được notebook/output hoặc xác minh runtime hiện tại. Ảnh private `colab-auth-blocked-20261027.png` ghi trạng thái này. Không tự kết luận kết quả vòng09 mất hay worker đã hoàn tất; cần phiên Google hợp lệ để tiếp tục kiểm tra. Mục tiêu chất lượng vẫn chưa hoàn tất.

## Phạm vi và cách chấm

- Chỉ thay prompt/ngữ cảnh inference; không thay trọng số, model, adapter hoặc môi trường. Đây là cải thiện pipeline có trợ giúp thuật ngữ.
- Giữ Qwen3.5-4B revision `851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a`, T4 FP16, LoRA FP32, greedy seed17; cùng runner SHA-256 `23201e6379a2e1eae283dd547e5392f88322160c69ca9647806d890935fd65ee`.
- Adapter SHA-256 `1670910dcad7f1e8f66d1dd74e6297fe914c2067d0c26240b72b25fd36c922bf` giữ nguyên; control pristine/LoRA-disabled khớp, LoRA-active thay đổi logit hữu hạn, đủ 64 tensor được kiểm.
- Mọi gói hoàn tất giữ metadata, input hashes, token IDs, raw output, log, completion và exit code. Giải mã độc lập từ token IDs để chấm; không chữa JSON/ngôn ngữ sau sinh. Không truncate; đầu vào cộng giới hạn đầu ra phải nằm trong 4.608 token.
- Kiểm cấu trúc gồm JSON/schema, hai slot/fact căn chỉnh, độ dài, evidence nguyên văn và phần trả lời cuối hợp lệ. Chấm nghĩa thêm ngày/ranh giới thời gian, phủ định/điều kiện, tuổi quan hệ, mã/màu/tài liệu, vai nói, continuity và câu hỏi không lộ đáp án.
- Người chấm nghĩa là trợ lý, không có hội đồng độc lập. B1 được thu hẹp vào ý kiến về chính đồ vật cùng lý do mang/cất giữ và một điều kiện giấy tờ khi nhận đồ; không suy rộng sang mọi dạng B1.
- Không đọc/tokenize tập train/validation/test; local training giữ `PAUSED_BY_USER`. Không train, deploy, cài dependency, sửa checkpoint hoặc shutdown runtime trong phần tiếp nối.

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

Tổng tiếp nối: 100; cộng 60 trước đó thành 160. Các bộ đã được xem rồi dùng sửa prompt được gọi là **hồi quy đã quan sát**, không gọi lại là kiểm tra mù. Hai lần đóng băng có timestamp/input hash trước generation; không sửa ứng viên giữa mỗi đợt xác nhận để tính lại là đạt.

### Điều đã sửa và giới hạn còn lại

Ngữ cảnh content giữ chính đồ vật, quyền sở hữu và lịch đã chốt; nhân viên giao đồ cho người học, không yêu cầu mang lại món đồ đang nằm tại quầy. Ý kiến phải nói về đồ vật chính, có lý do thực tế, cùng đúng một điều kiện giấy tờ và evidence phủ hết fact. Cách này tránh bịa sửa đồ mượn hoặc thêm phụ kiện mới.

Dịch dùng bảng thuật ngữ suy ra từ nguồn, danh sách cụm bắt buộc trong câu dịch và cùng quy tắc cho facts. Vòng08 thêm `show → xuất trình`, `badge → huy hiệu`, `original receipt → biên lai gốc` cùng yêu cầu tiếng Việt hoàn chỉnh. Tuy nhiên glossary màu cũ chưa bao phủ yellow; adapter bỏ thuộc tính này dù giữ đúng phủ định, ngày, anh trai và biên lai gốc. Không che lỗi bằng điểm JSON.

Vòng09 mở rộng 14 màu thông dụng bằng quy tắc chung, giữ nguyên system prompts. Đây vẫn là lexicon có giới hạn ngữ cảnh, chưa phải xử lý thuật ngữ production. Base gọi giấy tờ nhận máy ảnh là `return form`, còn adapter lặp quan hệ chị/em gái hơi vụng: hành động/logic vẫn đúng nhưng cần trau chuốt ngôn ngữ. Chưa có cơ sở chọn adapter thắng base về chất lượng tổng quát.

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

Các `semantic-review.json`, `holdout-review.json`, `confirmation/freeze.json` và `round07/holdout-freeze.json` giữ đánh giá, thất bại và thứ tự chọn ứng viên. Lần export holdout đầu gặp SyntaxError trước khi chạy; giữ lỗi, chỉ thử lại export bằng ô mới, không chạy lại inference.

```powershell
$evidenceRoot = 'C:/Users/HP/Desktop/English_dictition/data/fine-tuning/colab-transfer-20261025/inference-improvement-20261026'
$taskPython = 'C:/Users/HP/Desktop/English_dictition/data/fine-tuning/.venv-cpu/Scripts/python.exe'
& $taskPython -X utf8 "$evidenceRoot/verify_continuation.py"
& $taskPython -X utf8 "$evidenceRoot/audit_supplied_terms.py" --self-check
```

Verifier kiểm toàn vẹn 100 row tiếp nối, không sinh lại và không coi bằng chứng toàn vẹn là chất lượng đạt. `audit_supplied_terms.py` chỉ kiểm các thuật ngữ đã cung cấp và ký tự CJK; không thay thế đọc nghĩa đầy đủ, cũng không phát hiện thuộc tính chưa có trong danh sách.

Ngày27/10 đã chạy lại verifier đủ100 row với exit0; archive/file hashes, manifest, native-token decode, cấu trúc và hai mốc đóng băng đều xác minh được. Self-check bộ kiểm thuật ngữ, Python compile, liên kết báo cáo, UTF-8 và `git diff --check` đạt. Log private: `continuation-check-20261027.log`. Kết quả này không xác minh vòng09 hoặc loại bỏ lỗi nghĩa đã ghi.

## Việc còn lại

Khôi phục kết nối đọc tab Colab hiện có, kiểm process/completion của vòng09 trước mọi lệnh mới, xuất và kiểm tám đầu ra. Nếu sửa được yellow và không hồi quy: đóng băng palette/system/input hashes, rồi chạy bốn câu màu tự soạn chưa sinh với v2 và ứng viên bằng cùng runner. Chỉ công nhận đạt gate sau khi kiểm cấu trúc, nghĩa và bằng chứng cả hai nhánh. Không khởi chạy trùng chỉ vì công cụ mất kết nối.

Chưa có ảnh xác nhận vòng09 kết thúc. Ảnh ngày27/10 chỉ xác nhận lỗi đăng nhập; ảnh tổng kết 60 row ở báo cáo cũ là bằng chứng lịch sử, không được dùng làm ảnh hoàn tất 160 row hoặc vòng09.
