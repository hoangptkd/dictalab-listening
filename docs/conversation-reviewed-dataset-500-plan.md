# Thu thập dữ liệu đã duyệt và train Qwen3.5-4B

Ngày chốt: 23/10/2026. Đây là yêu cầu cho chiến dịch mới; các báo cáo benchmark cũ giữ nguyên.

## Yêu cầu đã được chủ dự án chốt

| Tập | A1 | A2 | B1 | B2 | Tổng bài |
| --- | ---: | ---: | ---: | ---: | ---: |
| Train | 125 | 125 | 125 | 125 | 500 |
| Validation | 25 | 25 | 25 | 25 | 100 |
| Test | 25 | 25 | 25 | 25 | 100 |

- Một bài gồm nội dung tiếng Anh, bản dịch tiếng Việt, câu hỏi, slot, đáp án và bằng chứng.
- Pipeline hiện xuất hai bản ghi SFT/bài: content và translation. Mục tiêu tương ứng là
  1.000 bản ghi train, 200 validation, 200 test, sau kiểm tra trùng lặp.
- Sinh mới toàn bộ. Dữ liệu cũ chỉ phục vụ tránh trùng lặp/rò rỉ; không lấy lại bài cũ để bù số lượng.
- DeepSeek sinh qua provider registry hiện có; một lời gọi sinh cả nội dung và bản dịch.
- ChatGPT Web chấm từng bài; Codex kiểm tra độc lập và quyết định cuối.
  Lưu duyệt là AI được ủy quyền, không ghi thành duyệt bởi con người.
- Thu thập không cần teacher đạt 28/30 hoặc judge vượt gold gate. Kiểm tra JSON,
  Unicode, độ dài, schema, ngữ nghĩa và chống rò rỉ vẫn áp dụng từng bài.
- Bài lỗi/chưa chắc chắn bị loại, sinh ứng viên mới để bù; không sửa target rồi nhận là bản gốc.
  Codex có thể bác lỗi giả của Web khi có căn cứ rõ, phải lưu bằng chứng và lý do.
- Trần 1.500 ứng viên cho bộ chính, gồm cả ứng viên bị loại. Dừng khi đủ các ô mục tiêu,
  chạm trần hoặc hết quota; lưu checkpoint để tiếp tục. Không gọi lại request đã gửi
  khi chưa xác định kết quả, không đổi model hoặc reset quota ngầm.
- Sau khi chốt loại, xóa nội dung chi tiết ứng viên bị loại. Giữ hash, nhóm lỗi,
  lý do loại, chi phí/usage nếu nguồn cung cấp, trạng thái gửi để chống replay.
  Giữ đầy đủ nguồn sinh, nội dung và bằng chứng duyệt của bài được nhận.
- C1/C2 thử riêng 10 bài/mức sau bộ chính; khó khăn ở hai mức này không chặn
  thu thập, train hoặc đánh giá A1–B2. Không trộn mẫu thử vào 500 bài train chính.
- Audio vẫn ngoài phạm vi. Không tuyên bố các bài ngắn này bao phủ toàn bộ CEFR Listening.
- Chủ dự án đã cho phép sửa pipeline, tạo/duyệt dữ liệu, chạy SFT/LoRA và đánh giá
  trên máy hiện tại. Không bao gồm triển khai production.

## Quản lý Chrome

- Bridge dùng profile riêng. Tái sử dụng một tab ChatGPT và điều hướng về hội thoại mới
  cho mỗi bài; không nối các bài chấm độc lập vào cùng lịch sử chat.
- Dọn các tab ChatGPT mồ côi và tab trống của profile bridge sau khi restart.
- Khi phải tạo tab thay thế, đóng các tab cũ của bridge trước. Chạy trong cùng hàng đợi
  với prompt, không đóng trang đang nhận phản hồi. Giữ trang không liên quan.
- Bản vá lưu trong `scripts/chatgpt-web-compat.patch`; checkout chạy local ở
  `data/chatgpt-api-web` được Git bỏ qua. Không đưa cookie/profile/log riêng vào Git.
- Kiểm tra có thể chạy không cần Chrome: `node scripts/test-chatgpt-web-tabs.cjs`.
- Kiểm tra live ngày 23/10: một tab sau restart và trong ba lượt chấm độc lập;
  cùng tab được tái sử dụng, mỗi bài có hội thoại ChatGPT mới.

## Trạng thái và các bước tiếp theo

1. **Đã làm:** kiểm tra nguồn code, phần cứng, các điểm chặn B2 và vòng đời tab.
   Sửa bản vá bridge và checkout local để dùng một tab, thêm kiểm tra mock.
2. **Đã triển khai runner, chờ kết quả live:** tạo chiến dịch thu thập riêng, khóa model/prompt/rubric/schema,
   ngân sách và các ô số lượng; giữ nguyên chiến dịch benchmark lịch sử.
3. Mở hỗ trợ B2 trong artifact thu thập, chuẩn bị dataset và trainer. Tận dụng
   profile/schema shadow hiện có nhưng giữ phạm vi offline, không bật B2 production.
   B2 cần lập luận/quan điểm và yêu cầu hiểu phù hợp, không chỉ thêm từ khó.
4. Chia các nhóm nguồn trước khi sinh để train/validation/test độc lập. Các lượt cùng
   hội thoại và cặp content/translation luôn cùng tập. Không đổi groupId để né chống rò rỉ.
   Catalogue hiện có 24 scenario, 66 nhánh/mức A1/A2/B1; phải lập phân bổ đủ nguồn mới,
   không nhân bản bài hoặc dùng lại holdout để đạt chỉ tiêu.
5. Chạy lô nhỏ để kiểm tra luồng sinh → hard validator → Web judge → Codex duyệt.
   Judge ghi căn cứ ngắn cho đáp án; phân biệt bản dịch có thể đúng với nghĩa đủ căn cứ
   trong ngữ cảnh. Chỉ xét nghĩa thay thế hợp lý và làm thay đổi kết quả cần chấm.
6. Thu thập theo lô có checkpoint; duyệt thật từng bài, ghi hash và bằng chứng duyệt
   trước khi đưa lịch sử của bài vào lượt phụ thuộc. Mẫu pending không vào SFT.
7. Đủ số lượng thì audit coverage, exact/near duplicate và holdout, xuất dataset
   cùng manifest/decisions có hash; không tự đánh dấu audit đạt khi chưa kiểm tra.
8. Sau khi dataset đã được duyệt và đóng các ứng dụng nền, thử train LoRA thật.
   Chủ dự án yêu cầu không dùng preflight tài nguyên làm điều kiện chặn, kể cả
   RAM thấp hơn mức ước lượng. Cho phép lần thử có ủy quyền rõ ràng bỏ chặn RAM
   ước lượng, giữ kiểm tra tính toàn vẹn dữ liệu/loss mask và ghi lỗi runtime/OOM
   thực tế. Không tuyên bố train thành công nếu không có optimizer step/checkpoint.
   Chọn checkpoint chỉ bằng validation.
9. Đánh giá base và adapter trên test đã khóa; báo cáo hard pass, ngữ nghĩa, CEFR,
   nhóm lỗi và giới hạn. Không dùng test để sửa prompt/chọn checkpoint.
10. Thử C1/C2 riêng nếu bộ chính đã hoàn tất; báo rõ phần không chạy được.

### Cập nhật triển khai 23/10/2026

- Runner `training.collect_reviewed` hỗ trợ prepare/collect/status/adjudicate/export;
  không tự duyệt mẫu. Mỗi quyết định Codex gắn hash bài, ngữ cảnh và kết quả Web,
  có bằng chứng từng đáp án và lý do riêng cho lỗi Web bị bác.
- Artifact thu thập riêng mở B2 offline; giữ validator production hiện tại. Nhóm nguồn
  cùng scenario/branch dùng chung tập trên mọi mức; loại nhóm gold/benchmark cũ.
- Web cần trích dẫn và xác nhận từng fact, kể cả khi kết luận tất cả kiểm tra đạt.
- Trainer có `--attempt-below-memory-estimate` cho lần thử CPU được ủy quyền;
  vẫn giữ kiểm tra dữ liệu và loss mask, không thay model.
- Watchdog ngoài worker được bật rõ bằng `--shutdown-on-worker-failure`, giám sát
  vòng collect; không giám sát Codex hoặc Windows bị treo. Lệnh shutdown kiểm thử
  bằng mock. Khi chờ Codex duyệt, watchdog kết thúc bình thường.
- Kiểm tra hiện tại: 114 passed, 1 skipped; UTF-8 đạt.
- Lô thật đầu tiên tại `data/fine-tuning/reviewed-500-20261023`: gửi bốn ứng viên
  DeepSeek A1/A2/B1/B2. Ba bài qua hard checks và được Web chấm; Codex duyệt A1,
  loại A2 vì slot/nguồn chưa khớp chắc chắn và B2 vì lệch slot/câu hỏi cùng sai taskType.
  B1 gặp ProviderError; log gọn lô đầu chưa giữ mã lỗi chi tiết nên không kết luận sâu hơn.
- Tổng giữ: 1 bài train A1, 0 validation, 0 test; còn thiếu 499/100/100 bài.
  Nội dung chi tiết ba bài loại đã xóa, giữ hash/nhóm lỗi/lý do/usage/trạng thái gửi.
  Code đã bổ sung giữ mã provider kiểm soát cho các lần sau, không giữ exception dump.
- Quota thực đo đã dùng 100/100, còn 0. Checkpoint dừng để tiếp tục khi có quota;
  không gửi lại bốn request cũ, không lấy dữ liệu cũ thay thế. Chưa export hoặc train,
  chưa có optimizer step. Mốc tắt máy áp dụng do hết quota theo ủy quyền của chủ dự án.

## Phần cứng và mốc tạm dừng

- Máy hiện có khoảng 32 GB RAM, Radeon tích hợp, không có CUDA.
- CPU LoRA float32 Qwen3.5-4B có admission estimate khoảng 23,7 GiB RAM trống
  trong runner hiện có. Cập nhật mới nhất: chủ dự án yêu cầu thử train thật dù
  không đạt ước lượng; triển khai lựa chọn rõ ràng cho lần thử này, không tự coi
  thiếu RAM ước lượng là lý do dừng trước khi nạp model. Có thể gặp OOM/runtime
  failure; phải ghi kết quả thật, không bảo đảm thành công.
- Đã đóng Riot Client/Crash Handler, Phone Link, Widgets và Widget Service đang chạy nền.
  Đo tại thời điểm đóng: RAM trống tăng từ 12,91 lên 13,37 GiB; chưa đủ để nạp checkpoint.
- Cập nhật ủy quyền: đến bước train, chủ dự án xác nhận máy không có công việc quan trọng
  và cho phép buộc đóng mọi ứng dụng không phục vụ train, kể cả trình duyệt/bridge,
  Docker Desktop và các workload WSL. Lưu checkpoint và bằng chứng thu thập trước;
  giữ Docker/registry/quota khi vẫn đang sinh dữ liệu. Giữ Codex, tiến trình train
  và các dịch vụ hệ thống cần thiết. Thử train dù không đạt admission estimate
  theo ủy quyền mới nhất; không coi việc đóng ứng dụng là bằng chứng đủ RAM.
- Chủ dự án yêu cầu làm phần nhỏ trước, sau đó tiếp tục trong chat này sau 40 phút.
  Lượt tiếp tục phải đọc file này, kiểm tra Git/tiến trình hiện tại rồi làm bước 2;
  không lặp lại các câu hỏi đã chốt hoặc tự coi bộ dữ liệu đã được tạo.

## Tắt máy sau công việc hoặc khi không thể tiếp tục

- Chủ dự án đã yêu cầu **buộc tắt máy, không hỏi lại** sau khi hoàn thành mục tiêu
  thu thập/train/đánh giá, hoặc gặp lỗi khiến công việc dừng và không còn chạy tiếp.
  Hết quota mà không thể tiếp tục cũng phải lưu checkpoint trước khi tắt.
- Khoảng nghỉ 40 phút được yêu cầu, kết thúc lượt cập nhật cấu hình này, hoặc một
  lỗi C1/C2 có thể bỏ qua không phải tín hiệu tắt máy. Giữ máy bật cho lượt tiếp tục.
- Trước khi tắt: ghi checkpoint, kết quả/lỗi thật và báo cáo ngắn; flush các file
  đang ghi. Lưu thay đổi code đúng phạm vi; lỗi mạng/Git không được làm mất kết quả local.
- Khi đủ điều kiện, gọi `shutdown.exe /s /f /t 60` để Windows tự thực hiện buộc tắt,
  có 60 giây ghi thông báo cuối. Không cần xin xác nhận thêm. Không tắt ở bước kiểm thử.
- Khi triển khai runner dài, gắn xử lý kết thúc/lỗi và giám sát ngoài worker cho trường hợp
  worker thoát bất thường; kiểm tra bằng mock thay vì thực sự shutdown trong test.
  Chỉ cập nhật prompt không bảo đảm tắt máy nếu chính Codex hoặc Windows bị treo;
  phải báo đúng mức đã triển khai cơ chế giám sát, không hứa watchdog đã có khi chưa tạo.
