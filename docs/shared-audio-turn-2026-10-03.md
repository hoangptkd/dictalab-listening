# Nghe chung và TURN — 03/10/2026

## Cách dùng

1. Chủ phòng tạo phòng, mời và duyệt bạn học, chọn bài có file audio.
2. Cả hai bấm **Bật nghe chung** một lần. Đây là yêu cầu cấp phép âm thanh của trình duyệt, không bật microphone.
3. Chủ phòng chọn tốc độ và bấm **Nghe chung**. Cả hai phát cùng đoạn audio và thấy cùng lượt nghe. Khách không tự phát bằng nút hoặc Alt+P.
4. Điền đáp án riêng, mỗi người bấm **Sẵn sàng**, chủ phòng chuyển câu. Lượt nghe chung đặt về 0.
5. Gọi thoại: mỗi người vẫn cần tham gia cuộc gọi và cấp quyền microphone. TURN chỉ là đường truyền dự phòng, không thay quyền microphone.

## Triển khai và bảo vệ

- Windows VPS `160.191.244.27`, web HTTPS cổng 18443, Spring Boot loopback 18082.
- TURN chạy trực tiếp bằng Go + [Pion TURN v5.1.2](https://github.com/pion/turn/tree/v5.1.2), không dùng WSL/Docker. Mã nằm trong `scripts/turn/`, dependencies pin bằng go.mod/go.sum.
- NSSM `DictaLabTURN`, Auto Start, tài khoản LocalService; UDP/TCP 3478 và IPv4 UDP relay 49160–49959. Firewall chỉ cho executable TURN ở các cổng này.
- Secret sinh ngẫu nhiên 48 byte trên VPS, ở `C:\ProgramData\DictaLab\turn\shared-secret.txt`; ACL chỉ SYSTEM/Administrators và LocalService. Không đưa vào repo, frontend, câu lệnh process hay báo cáo.
- App cấp username `expiry:userId`, Base64 HMAC-SHA1, TTL 3600 giây. TURN xác minh realm/user/expiry và message integrity bằng thư viện; từ chối hạn trên 3600 giây.
- Chặn peer riêng/nội bộ/loopback/link-local/reserved/multicast; không cho TCP peer allocation hay yêu cầu cổng ngoài dải. TURN/TCP là TCP tới server, UDP từ relay tới peer.
- Quota mềm 4 allocation/user và 400 tổng. Callback kiểm tra/tạo allocation không nguyên tử, nên quota có thể vượt nhẹ khi cạnh tranh; dải 800 cổng là giới hạn cứng. Chưa có giới hạn băng thông/DoS toàn diện.
- Script cấu hình `scripts/configure-turn-windows-vps.ps1` kiểm SHA-256, lưu environment app cũ bằng DPAPI trước khi thêm TURN; không xóa cấu hình ShopAIKey/Cartesia. Thay environment có hiệu lực khi app restart.

## Bằng chứng kiểm thử

- Java: 142 test, 0 lỗi, 1 skipped (benchmark opt-in); JavaScript: 75/75.
- TURN: `go test ./...` và `go vet ./...` qua. `govulncheck` không phát hiện lỗ hổng ở symbol code gọi; có cảnh báo ở package/module không được gọi, không đồng nghĩa toàn bộ dependencies không có advisory. Race detector chưa chạy được vì môi trường thiếu CGO.
- Browser bắt buộc `iceTransportPolicy: relay`: DataChannel truyền đi và nhận echo qua VPS, local/remote đều là relay, với UDP và TCP riêng. Cổng relay đúng dải. Mật khẩu sai bị từ chối; credential được ký đúng nhưng đã hết hạn cũng bị từ chối.
- Hai browser tài khoản QA ở H2 tách biệt: cùng đoạn `offset=2.86s`, `duration=0.595s`, `speed=0.75`; thời điểm Web Audio được lên lịch lệch khoảng 0,5 ms trong một lần chạy local. Đây không phải SLA đồng bộ giữa thiết bị thật.
- Khách Alt+P không tăng lượt. Chủ phòng phát lại: cả hai tăng lên 2. Cả hai hoàn thành, chuyển câu 2: cả hai về lượt 0. Unit tests phủ command cũ/trùng, snapshot cũ, disconnect, watchdog và suspended AudioContext.
- Kiểm UTF-8 qua. UI probe ở 375/1280px và sweep 1440→375px: nút mới đã sửa thành text-button có sẵn, cao tối thiểu 44px. Không tràn ngang.
- Probe P1 toast: máy tính nền sai vì lớp nền/opacity; ảnh thực là chữ trắng trên nền xanh đậm, không phải nền giấy như kết quả máy. P2 word-slot xuống dòng là chủ ý giữ nguyên chiều dài từ. P3 link Về phòng xuống dòng ở 440–500px là bố cục hiện có, không che nút/tràn ngang; giữ nguyên vì ngoài phạm vi thay thiết kế. Các tín hiệu nhỏ chữ/viền/focus của UI cũ không được refactor trong lượt này.

Không thay database schema trong chức năng nghe chung. Điểm/PracticeSession riêng vẫn giữ nguyên. Shared count là số lệnh phát được chấp nhận, không phải xác nhận âm thanh đã đến tai mỗi người.

## Vận hành và giới hạn

- Đã deploy thành công; SHA-256 JAR chạy thực tế: `780BD1837FC943D687F1B8A8E6CBBC49F60C87E35D1C859E420516140E0686C6`. Health public UP, HTML/JS/CSS mới trả HTTP 200, API phòng chưa đăng nhập trả 401. Cả DictaLab và DictaLabTURN Running, TURN tự start dưới LocalService. ShopAIKey/Cartesia environment được giữ nguyên.
- JAR đang chạy và JAR previous được lưu tại `C:\DictaLab\backups\shared-audio-turn-20261003-011916` trước update.
- DB backup trước update: `dictalab-20261003-012602.dump`, 4.860.120 byte; `pg_restore --list` đọc thành công, chưa phải restore drill.
- Không khởi động lại VPS. Thử Ubuntu/WSL bị kẹt ở registration, LxssManager StopPending; helper riêng đã dừng. Thư mục `C:\DictaLab\turn-wsl` giữ nguyên để kiểm tra/dọn khi có cửa sổ bảo trì, không phục vụ TURN/web và không chứa secret TURN. Không tự ép reboot hoặc xóa distribution chưa rõ trạng thái.
- Chưa có TURN/TLS 443: mạng doanh nghiệp chặn cả UDP/TCP 3478 vẫn có thể không gọi được.
- AudioContext cần gesture mỗi browser; trình duyệt/mobile có thể đình chỉ khi tab vào nền. Mạng muộn quá 1,5 giây yêu cầu phát lại. Watchdog 5 phút giải phóng lượt đang kẹt, yêu cầu bật nghe lại.
- Backend restart làm mất phòng đang mở theo thiết kế in-memory. Người dùng cần tạo phòng mới sau update.
- Chưa tái hiện cuộc gọi thật giữa Wi-Fi và 4G/5G của người dùng. Kiểm forced relay chứng minh hạ tầng relay hoạt động, không chứng minh mọi thiết bị/firewall đều thành công.

## Chạy lại kiểm tra

```powershell
node --test src/test/js/*.test.cjs
mvn --batch-mode --no-transfer-progress package
Push-Location scripts/turn
go test ./...
go vet ./...
go build -trimpath -o ../../target/dictalab-turn.exe .
Pop-Location
```

Browser probe: `scripts/verify-turn-browser.js`, gọi `verifyTurn(validIceServer, expiredSignedCredentials)` trong browser riêng. Chỉ dùng credential tạm, không truyền secret; đầu ra chỉ trạng thái và loại candidate. PowerShell wrapper agent-browser.ps1 không chuyển stdin, nên với `eval --stdin` dùng executable gốc của agent-browser.
