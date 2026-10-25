# DictaLab cho iPhone — SwiftUI, cài cá nhân

Ứng dụng iOS viết bằng SwiftUI, phát âm thanh qua AVFoundation và gọi HTTPS API của DictaLab.
Không dùng WebView. Hỗ trợ iPhone chạy iOS 16 trở lên. Cần Internet để đăng nhập, chấm bài và đồng bộ.

## Chức năng bản đầu

- Đăng nhập và đăng ký bằng email; dùng chung tài khoản với website. Người dùng Google có thể đặt mật khẩu qua trang Quên mật khẩu.
- Chọn bài A1–B2; tiếp tục phiên luyện đang dở từ máy chủ.
- Phát đúng đoạn câu, tốc độ 0.75×/1×/1.25×; điền và chấm từng từ.
- Gợi ý chữ cái đầu, mở từ hoặc cả câu; bắt buộc nghe lại sau khi mở đáp án.
- Phân loại từ nghe sai, hoàn thành câu và xem kết quả/transcript.
- Lưu bản nháp trên iPhone; giữ mã thao tác để gửi lại an toàn khi mất phản hồi mạng.
- Xem lịch sử phân trang và tiến bộ; đăng xuất thu hồi phiên trên máy chủ.
- Phiên đăng nhập lưu trong Keychain, không ghi mật khẩu hoặc token vào mã nguồn, log hay UserDefaults.

Nghe hiểu, Conversation, Coach, phòng gọi thoại và quản trị chưa có giao diện iOS trong bản đầu.
Dữ liệu trên website được giữ nguyên. Bản nháp chưa gửi chỉ nằm trên thiết bị; không gỡ app nếu cần giữ bản nháp đó.

## Build `.ipa` từ Windows

Workflow [Build personal iOS app](https://github.com/hoangptkd/dictalab-listening/actions/workflows/ios.yml)
chạy khi push thay đổi trong `ios/`. Workflow dùng Xcode trên `macos-15` để:

1. Kiểm tra project, cấu hình HTTPS và icon.
2. Build và chạy XCTest trên iPhone Simulator.
3. Build Release cho iPhone arm64, đóng gói `DictaLab-unsigned.ipa` và SHA-256.
4. Khởi chạy app trên simulator, lưu ảnh màn hình.

Mở run tương ứng với commit của bạn, đợi thành công, tải artifact **DictaLab-personal-iOS** rồi giải nén.
File `.ipa` chưa ký; không thể cài bằng cách bấm trực tiếp vào file trên iPhone.
Build trên repository riêng sử dụng hạn mức GitHub Actions; kiểm tra ngân sách/hạn mức còn lại và không bật chi tiêu trả phí nếu cần giữ chi phí bằng 0.
Artifacts tự hết hạn sau 3 ngày; tải bản `.ipa` về máy để giữ lại.

Kiểm tra cấu trúc trên Windows:

```powershell
python ios/scripts/check-project.py
python ios/scripts/check-project.py C:\duong-dan\DictaLab-unsigned.ipa
Get-FileHash C:\duong-dan\DictaLab-unsigned.ipa -Algorithm SHA256
```

Trên Mac có Xcode và iPhone Simulator:

```bash
bash ios/scripts/build.sh
```

## Cài trên iPhone bằng tài khoản Apple miễn phí

Theo [Apple](https://developer.apple.com/help/account/basics/about-your-developer-account), provisioning miễn phí hết hạn sau **7 ngày**.
Đây là cài cá nhân, không phải phát hành công khai trên App Store.

1. Cài **AltServer cho Windows** theo [hướng dẫn chính thức của AltStore](https://faq.altstore.io/altstore-classic/how-to-install-altstore-windows).
   Dùng đúng bản iTunes/iCloud được tài liệu AltStore yêu cầu. Không gỡ ứng dụng hoặc dữ liệu hiện có chỉ để thử.
2. Kết nối iPhone qua USB, mở khóa và chọn tin cậy máy tính. Dùng AltServer cài AltStore lên iPhone.
3. Nhập tài khoản Apple trực tiếp trong phần mềm trên máy của bạn; không gửi mật khẩu hoặc mã 2FA vào chat/GitHub.
4. Trên iPhone, tin cậy chứng chỉ tài khoản cá nhân và bật **Developer Mode** theo hướng dẫn của iOS.
5. Chuyển `DictaLab-unsigned.ipa` sang Files trên iPhone. Trong AltStore, mở **My Apps → +**, chọn file để ký và cài.
6. Mở **DictaLab**, đăng nhập và thử một câu: phát, nhập từ, chấm, mở đáp án, nghe lại và chuyển câu.
7. Trước khi đủ 7 ngày, chạy AltServer trên PC, để iPhone kết nối qua USB hoặc cùng Wi-Fi rồi chọn **Refresh All** trong AltStore.

AltStore cố tự gia hạn khi kết nối được AltServer; cần kiểm tra thời hạn, không coi gia hạn nền là bảo đảm.
Tài khoản miễn phí giới hạn 3 app đang cài, AltStore chiếm một chỗ.
Để giữ phiên và bản nháp khi cập nhật, cập nhật cùng app/bundle identity bằng cùng tài khoản Apple; không xóa app trước.

## Cấu hình và kiểm chứng

- Backend mặc định: `https://dictalab.hoangptkdhy.workers.dev/`, trong `DictaLab/Info.plist`.
- Không đặt API secret, tài khoản Apple, provisioning profile hoặc chứng chỉ ký vào repository hay workflow.
- API dùng cookie JWT và CSRF hiện có; app chỉ cho API đi tới cùng HTTPS origin và chỉ cho media redirect qua HTTPS.
- XCTest kiểm tra CSRF, giới hạn gửi lại, refresh phiên, từ chối API khác origin/HTTP, thao tác mất phản hồi và mốc audio.
- Build simulator/arm64 thành công xác nhận biên dịch và kiểm thử tự động. Việc ký, cài và nghe thực tế cần được xác nhận trên iPhone của chủ máy.
- Chưa tuyên bố tương đương mọi tính năng website hoặc hoạt động offline.
