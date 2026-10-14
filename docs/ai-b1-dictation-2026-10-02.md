# Bài chép chính tả B1 sinh bằng AI — 02/10/2026

Đã thêm và xuất bản trực tiếp trên VPS ba bài mới, không thay JAR/restart service, không thay thế bài cũ.

| Lesson ID | Bài | Số câu | Số từ | Audio | ASR alignment |
|---|---|---:|---:|---:|---:|
| 121 | B1 · Đổi kế hoạch chuyến đi | 12 | 154 | 51,80 giây | 98,70% |
| 122 | B1 · Đổi trả đơn hàng | 12 | 150 | 50,12 giây | 100% |
| 123 | B1 · Lên kế hoạch hoạt động nhóm | 12 | 157 | 51,16 giây | 96,18% |

Truy cập `https://160.191.244.27:18443/catalog`, đăng nhập và chọn **B1**. Các bài mới có slug `ai-b1-*-20261002`, origin `ADMIN_UPLOAD`, speaker_count=2, assessment_calibrated=false.

## Nguồn nội dung và audio

- Nội dung gốc: ShopAIKey, model `gemini-2.5-flash`; đã đọc kiểm tra và chỉnh vài câu để mỗi mục là một câu hoàn chỉnh, từ vựng/cách hỏi phù hợp hơn với B1.
- Audio: Cartesia `sonic-3.6`, API version `2026-08-14`, voice A Skylar, voice B Daniel; luân phiên theo lượt thoại. Đây là giọng AI, không phải hai người thu âm.
- WAV mono PCM16, 24 kHz. Mỗi câu được tạo riêng, ghép PCM với khoảng nghỉ 0,2 giây. Mốc bắt đầu/kết thúc được tính từ số mẫu, không ước lượng bằng tỷ lệ số chữ.
- Mỗi bài dùng một file UUID `.wav` ngoài JAR tại `C:\DictaLab\media`; tất cả câu tham chiếu file đó với mốc riêng.
- Độ khó B1 là thiết kế nội dung, chưa phải kết quả calibration CEFR chuẩn hóa. Người học vẫn có tốc độ phát của Dictation để điều chỉnh.

Cartesia trả header WAV có thể giữ sentinel độ dài streaming; công cụ kiểm tra format và toàn bộ body, rồi ghi lại header RIFF với độ dài thật cho audio ghép. API tham khảo: [TTS Bytes](https://docs.cartesia.ai/api-reference/tts/bytes), [Voices](https://docs.cartesia.ai/api-reference/voices/list).

## Kiểm tra đã thực hiện

- Self-test cho WAV, trường hợp streaming header, mốc ghép chính xác, transcript và SQL chỉ thêm draft.
- Kiểm tra SHA-256 audio trước/sau copy vào kho media, duration, format, đoạn không im lặng và mốc không chồng lấn/vượt thời lượng.
- Nhận diện lại toàn bộ audio bằng faster-whisper tiny.en có sẵn và so sánh bằng thuật toán sequence alignment hiện có. Các số đọc thành chữ có thể được ASR ghi thành `9` hoặc `$100`, gây giảm tỷ lệ alignment.
- Tỷ lệ alignment là heuristic cho kiểm tra nội dung âm thanh, **không phải độ chính xác chấm phát âm, chứng nhận B1 hoặc bảo đảm transcript tuyệt đối**. Chưa thực hiện bài nghe đối chứng với người học hoặc human listening review đầy đủ.
- PostgreSQL xác nhận đủ ba bài active, mỗi bài 12 câu, hai người nói và calibration=false. Mỗi bài có event `AI_CONTENT_IMPORT` với actor hệ thống NULL.
- Service `DictaLab` Running, public `/actuator/health` UP sau xuất bản. Chưa kiểm thử toàn bộ luồng nhập đáp án bằng tài khoản người học qua trình duyệt trong đợt này; không tạo tài khoản thử hay thay session/credential người dùng.
- Kiểm tra UTF-8 đạt cho script và nội dung tiếng Việt.

Trước import đã tạo dump `dictalab-20261002-222238.dump`, 4.847.347 byte, kiểm tra đọc danh mục bằng `pg_restore --list`. Đây không phải restore drill.

## Công cụ và lưu trữ

- `scripts/create-b1-dictation.cjs`: các bước `generate`, `audio`, `sql`, `self-test`; dùng Node built-in, không thêm dependency. Sinh nội dung có tối đa một lần repair, không retry network vô hạn. Chế độ SQL chỉ tạo draft, không tự publish.
- `scripts/verify-ai-dictation.py`: kiểm tra PCM/mốc/hash và ASR, tái sử dụng `split-elllo-sentences.py` cùng model/library đã cài.
- `scripts/import-ai-dictation-vps.ps1`: xác minh đường dẫn/media, backup DB, insert-only transaction và kiểm tra draft; không publish hoặc deploy JAR.
- `data/generated-b1-20261002/`: manifest đã duyệt, response provider, ba WAV, SQL và `audio-check.json`. Thư mục data bị Git ignore; sao lưu riêng nếu cần giữ lâu dài.
- Bản production chuẩn bị nằm ở `C:\DictaLab\staging\b1-20261002`; media đang dùng nằm trong kho media của ứng dụng, không phụ thuộc thư mục staging.

Lệnh đọc/kiểm tra có thể chạy lại local:

```powershell
node scripts/create-b1-dictation.cjs self-test
python scripts/verify-ai-dictation.py data/generated-b1-20261002
```

Không chạy lại bước import/publish cho cùng slug. Unique constraint làm import trùng thất bại thay vì ghi đè dữ liệu cũ. Nếu cần sửa bài đã có history, dùng copy revision như quản trị hiện tại. Không đưa API key/DB secret vào manifest hoặc repository.
