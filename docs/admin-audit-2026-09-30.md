# Kiểm tra trang quản trị DictaLab — 30/09/2026

## Phạm vi và checklist cố định

Phạm vi: quản lý Dictation, Listening Comprehension, người dùng, yêu cầu hỗ trợ; điều hướng và phiên đăng nhập chung. Kiểm tra hiện trạng, chưa sửa mã ứng dụng hoặc dữ liệu production.

Vai trò: khách, học viên, quản trị viên; trạng thái bị khóa hoặc bị thu hồi quyền. Hành trình: truy cập → tìm/lọc → phân trang → xem → sửa → lưu/xuất bản hoặc xử lý yêu cầu → kiểm tra kết quả.

| ID | Hạng mục kiểm tra áp dụng cho từng màn |
|---|---|
| C01 | Điều hướng, tiêu đề, trang đang chọn, quay lại đúng trạng thái |
| C02 | Tìm kiếm; bộ lọc theo dữ liệu nghiệp vụ; xóa lọc; không có kết quả |
| C03 | Sắp xếp, phân trang, tổng số, ổn định khi dữ liệu thay đổi |
| C04 | Bảng/danh sách rõ thứ bậc; nhãn, khoảng cách, nội dung dài |
| C05 | Desktop/mobile, reflow, không tràn ngang ngoài vùng cần thiết |
| C06 | Bàn phím, focus, tên truy cập, tương phản, thông báo trạng thái |
| C07 | Form: hướng dẫn, validation, lỗi cạnh trường, giữ dữ liệu khi lỗi |
| C08 | Tải/chờ/lưu, rỗng, lỗi mạng, thử lại, hết phiên, gửi trùng |
| C09 | Thao tác nguy hiểm, dữ liệu chưa lưu, xung đột chỉnh sửa |
| C10 | Kiểm tra quyền ở server, thu hồi quyền/khóa, bảo vệ quản trị viên cuối |
| C11 | Vòng đời nội dung/tài khoản/yêu cầu hỗ trợ và khả năng truy vết |
| C12 | Quy mô dữ liệu: lọc/phân trang server, giới hạn tải, hiệu năng |

Đối chiếu: WCAG 2.2 cho khả năng tiếp cận; OWASP Authorization cho phân quyền; Carbon Data Table, GOV.UK Validation và Web Interface Guidelines cho pattern UX. Pattern tham khảo không tự động trở thành yêu cầu bắt buộc. Dashboard thống kê, xuất Excel, thao tác hàng loạt, MFA và phân quyền nhiều cấp phải được đánh giá theo nhu cầu/rủi ro cụ thể.

Trạng thái dùng trong báo cáo: Đã có / Một phần / Thiếu / Lỗi xác nhận / Chưa kiểm chứng / Không áp dụng. Bằng chứng mã nguồn và kiểm thử trình duyệt được ghi riêng.

## Kết luận

Trang admin đã có khung phân quyền và những thao tác nghiệp vụ chính, nhưng **chưa sẵn sàng để coi là bộ công cụ quản trị hoàn thiện**. Vấn đề không chỉ là thiếu bộ lọc: đã xác nhận lỗi tìm kiếm người dùng, lỗi ánh xạ đáp án nghe hiểu, mất dữ liệu chưa lưu, xử lý lỗi mạng chưa đầy đủ và cách trình bày danh sách không thống nhất.

Không đề nghị thay framework hoặc viết lại toàn bộ admin. Giữ HTML/CSS/JS và API hiện có; sửa lỗi chức năng trước, thống nhất danh sách/form, bổ sung truy vấn và vòng đời còn thiếu. Không cần ép mọi màn vào một viewport: danh sách và form dài có thể cuộn, nhưng cần tránh mở toàn bộ chi tiết trong từng dòng và phải giữ hành động chính dễ tìm.

## Phương pháp và giới hạn bằng chứng

- Đọc bốn màn admin, CSS dùng chung, luồng gọi API, controller/service, cấu hình phân quyền và các test liên quan.
- Kiểm tra Chrome ở 1366×768, 390×844; kiểm tra bổ sung reflow 320px trên form bài nghe và nghe hiểu.
- Chạy đúng tài nguyên giao diện hiện tại với H2 riêng tại localhost:18085: tài khoản thử, 24 học viên, 24 yêu cầu hỗ trợ và nội dung seed. Không dùng dữ liệu cá nhân thật; không sửa production. Tạo một bản nháp nghe hiểu thử từ seed để kiểm tra editor.
- So sánh SHA-256 tài nguyên production với workspace: `account.js`, `listening.js`, `app.js`, `admin.css`, `app.css` đều trùng. Tài nguyên local `target/classes` cũng trùng nguồn. Điều này xác nhận mã frontend đã kiểm tra đang được phục vụ ở VPS; không thay thế kiểm thử dữ liệu/backend production.
- Dùng axe-core 4.12.1 cho màn người dùng desktop và tạo bài nghe mobile; không kết luận toàn bộ website đạt WCAG từ kiểm tra tự động.
- Lỗi mạng được mô phỏng bằng chặn request trong trình duyệt local. Luồng sai đáp án được tái hiện bằng script chỉ đọc hàm thật; chưa xuất bản bài sai trên production.
- Chưa kiểm chứng: Safari/Firefox, screen reader thật, iOS/Android thật, SMTP/ShopAIKey live, tải lớn, hai quản trị viên thao tác đồng thời, khôi phục backup, mọi đường ghi dữ liệu khi thu hồi quyền. Không chạy lại toàn bộ test vì không sửa mã ứng dụng.

## Nguồn đối chiếu

| Mã | Nguồn | Cách áp dụng |
|---|---|---|
| S1 | [WCAG 2.2 Quick Reference](https://www.w3.org/WAI/WCAG22/quickref/) | Mục tiêu A/AA: tương phản 1.4.3, reflow 1.4.10, bàn phím 2.1.1, focus 2.4.7/2.4.11, tên điều khiển 4.1.2, lỗi 3.3.1/3.3.2, trạng thái 4.1.3. Đây là tiêu chí tiếp cận, không quy định phải có một bộ lọc cụ thể. |
| S2 | [Carbon: Data table](https://carbondesignsystem.com/components/data-table/usage/) | Pattern cho danh sách dữ liệu: toolbar, hàng/cột nhất quán, tìm/lọc/sắp xếp, phân trang và mở chi tiết khi phù hợp. Không bắt buộc dùng thư viện Carbon hay có bulk/export. |
| S3 | [GOV.UK: Recover from validation errors](https://design-system.service.gov.uk/patterns/validation/) | Giữ dữ liệu đã nhập, thông báo lỗi giúp sửa đúng trường; phân biệt lỗi dữ liệu với lỗi quyền/hệ thống. Không áp dụng máy móc quy tắc riêng GOV.UK về tắt HTML5 validation. |
| S4 | [OWASP: Authorization](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html) | Kiểm tra quyền phía server trên từng request, quyền tối thiểu, log phù hợp và test phủ nhận quyền. Không tự suy ra DictaLab phải có RBAC nhiều cấp. |
| S5 | [Web Interface Guidelines](https://raw.githubusercontent.com/vercel-labs/web-interface-guidelines/main/command.md) | Pattern bổ sung: trạng thái tìm/lọc trong URL, dữ liệu chưa lưu, chờ/rỗng/lỗi, nội dung dài, xác nhận thao tác phá hủy. Đây là hướng dẫn thiết kế, không chứng nhận tuân thủ. |

Những bộ lọc và nghiệp vụ đề xuất dưới đây là suy luận từ tác vụ thực tế của DictaLab, có dùng S2/S5 làm khung; không phải danh sách bắt buộc do một tiêu chuẩn ban hành.

## Phát hiện cần xử lý

P1: sửa trước đợt sử dụng rộng tiếp theo; P2: hoàn thiện vận hành và trải nghiệm sau P1. Không xác nhận P0 trong phạm vi kiểm tra này.

### A01 — P1 — Nút tìm kiếm và áp dụng bộ lọc người dùng bị hỏng

**Lỗi xác nhận bằng trình duyệt.** Nhập `does-not-exist`, bấm Tìm: vẫn 20 dòng cũ. Console: `Cannot set properties of null (setting 'disabled')`. Hàm submit chỉ tìm `button[type='submit']`, trong khi nút Tìm không khai báo thuộc tính type. Trình duyệt vẫn xem nó là submit nhưng CSS selector không tìm thấy; lỗi xảy ra trước khối try/catch và trước API.

Bằng chứng: [account.js:10](C:/Users/HP/Desktop/English_dictition/src/main/resources/static/js/account.js:10), [account.js:151](C:/Users/HP/Desktop/English_dictition/src/main/resources/static/js/account.js:151), [log lỗi](C:/Users/HP/Desktop/English_dictition/docs/admin-audit-assets/browser-errors.json), [ảnh mobile sau tìm](C:/Users/HP/Desktop/English_dictition/docs/admin-audit-assets/users-mobile.png). Đối chiếu S3/S5.

Nghiệm thu: tìm tên/email, lọc role/state, Enter và click đều gửi đúng request; kết quả rỗng có thông báo; lỗi request không làm nút hỏng vĩnh viễn.

### A02 — P1 — Editor nghe hiểu có thể lưu lệch đáp án đúng

**Lỗi xác nhận bằng script chạy hàm thật.** Bỏ trống lựa chọn 1, điền lựa chọn 2–4 và chọn lựa chọn 2 là đáp án đúng. `readContent()` loại phần tử rỗng nhưng giữ nguyên chỉ số đáp án. Kết quả lưu có đáp án trỏ vào lựa chọn 3 ban đầu. Ba lựa chọn còn lại và chỉ số 1 vẫn hợp lệ về cấu trúc nên validation phía server không thể nhận ra ý định chọn ban đầu.

Bằng chứng: [listening.js:161](C:/Users/HP/Desktop/English_dictition/src/main/resources/static/js/listening.js:161), [validation server:653](C:/Users/HP/Desktop/English_dictition/src/main/java/com/dictalab/service/ListeningComprehensionService.java:653). Chạy `node docs/admin-audit-assets/reproduce-read-content.cjs`: hiện tại assertion thất bại, `savedCorrectAnswer` là `Wrong B`. [Script tái hiện](C:/Users/HP/Desktop/English_dictition/docs/admin-audit-assets/reproduce-read-content.cjs). Đây là lỗi đúng/sai nghiệp vụ, không chỉ lỗi UI; S3 hỗ trợ cách báo lỗi.

Nghiệm thu: đáp án được giữ theo lựa chọn thực tế hoặc chặn khoảng trống ở giữa bằng lỗi rõ ràng; kiểm thử bỏ lựa chọn trước/sau đáp án, 3/4 lựa chọn, đáp án bị xóa. Xem lại những bài từng chỉnh bằng tình huống này; chưa có bằng chứng bài production cụ thể bị sai.

### A03 — P1 — Mất phần đang soạn khi điều hướng hoặc tải lại vùng editor

**Xác nhận trên editor nghe hiểu:** đổi tên thành `UNSAVED-AUDIT`, chuyển Người dùng rồi Back; tên quay về giá trị cũ, không có cảnh báo khi chuyển trang bằng SPA. Không thấy cơ chế dirty-state cho editor admin.

**Bằng chứng thêm từ mã:** lưu một câu/upload audio Dictation gọi `refreshAdminLessons()` rồi ghi lại toàn bộ form, có thể ghi đè metadata hoặc transcript khác đang sửa. Lưu một ticket cũng dựng lại toàn bộ danh sách, mất phản hồi chưa lưu ở ticket khác.

Bằng chứng: [listening.js:178](C:/Users/HP/Desktop/English_dictition/src/main/resources/static/js/listening.js:178), [app.js:2373](C:/Users/HP/Desktop/English_dictition/src/main/resources/static/js/app.js:2373), [app.js:2487](C:/Users/HP/Desktop/English_dictition/src/main/resources/static/js/app.js:2487), [app.js:2689](C:/Users/HP/Desktop/English_dictition/src/main/resources/static/js/app.js:2689), [account.js:245](C:/Users/HP/Desktop/English_dictition/src/main/resources/static/js/account.js:245). S3/S5.

Nghiệm thu: cảnh báo trước khi rời vùng có dữ liệu bẩn; lỗi lưu giữ nguyên nội dung; lưu một phần không xóa phần khác; hết phiên phải có cách phục hồi bản đang soạn an toàn, tránh lưu dữ liệu nhạy cảm lâu dài trên thiết bị dùng chung.

### A04 — P1 — Lỗi tải bộ lọc/phân trang không có phản hồi và để lại dữ liệu sai ngữ cảnh

**Xác nhận với request bị chặn:** đang xem OPEN, đổi sang RESOLVED; select hiện RESOLVED nhưng 20 ticket OPEN vẫn còn và không có thông báo. Promise từ handler đổi lọc/phân trang không được catch. `show()` có catch lần tải đầu, không bao phủ sự kiện phát sinh sau đó. Request nhanh liên tiếp cũng chưa có kiểm soát response cũ; tình huống đảo thứ tự chưa tái hiện.

Bằng chứng: [account.js:218](C:/Users/HP/Desktop/English_dictition/src/main/resources/static/js/account.js:218), [account.js:238](C:/Users/HP/Desktop/English_dictition/src/main/resources/static/js/account.js:238), [log lỗi](C:/Users/HP/Desktop/English_dictition/docs/admin-audit-assets/browser-errors.json). S3/S5.

Nghiệm thu: loading, error + retry đúng bộ lọc; không để dữ liệu cũ mang nhãn mới; chỉ request mới nhất được hiển thị; giữ focus phù hợp sau đổi trang.

### A05 — P2 — Bộ lọc, sắp xếp và trạng thái danh sách chưa đủ

**Thiếu xác nhận qua UI/API.** Dictation chỉ tìm chuỗi; Listening không có search/filter; ticket chỉ có status; users có q/role/enabled nhưng vướng A01. Không có điều khiển sắp xếp ở bốn màn. Users/support giữ điều kiện trong tham số hàm, mất khi reload/đi lại; Dictation tìm kiếm chưa lưu URL. Hai danh sách nội dung đã có page trong URL, không nên ghi nhận nhầm là chưa có phân trang.

Bằng chứng: [app.js:2399](C:/Users/HP/Desktop/English_dictition/src/main/resources/static/js/app.js:2399), [listening.js:178](C:/Users/HP/Desktop/English_dictition/src/main/resources/static/js/listening.js:178), [account.js:147](C:/Users/HP/Desktop/English_dictition/src/main/resources/static/js/account.js:147), [SupportController.java:97](C:/Users/HP/Desktop/English_dictition/src/main/java/com/dictalab/web/SupportController.java:97). S2/S5; bộ lọc cụ thể ở bảng bên dưới.

### A06 — P2 — Bố cục danh sách và form chưa thống nhất

**Xác nhận bằng ảnh.** Nghe hiểu là `<ul><li>` với tên, trạng thái và nút nối tiếp trên dòng; hỗ trợ dùng card chứa toàn bộ mô tả và form; người dùng dùng card với nhiều nút cùng trọng số. Dropdown trạng thái hỗ trợ chưa dùng kích thước/phông chung. Ở 390px, danh sách 20 người dùng cao khoảng 6.593px. Editor nghe hiểu bắt đầu dưới màn đầu ở desktop (top khoảng 1.046px sau mở bản sửa), năm nhóm câu hỏi mặc định mở.

Bằng chứng: [ảnh nghe hiểu](C:/Users/HP/Desktop/English_dictition/docs/admin-audit-assets/listening-desktop.png), [ảnh hỗ trợ](C:/Users/HP/Desktop/English_dictition/docs/admin-audit-assets/support-desktop.png), [ảnh người dùng](C:/Users/HP/Desktop/English_dictition/docs/admin-audit-assets/users-desktop.png), [listening.js:198](C:/Users/HP/Desktop/English_dictition/src/main/resources/static/js/listening.js:198), [app.css:4374](C:/Users/HP/Desktop/English_dictition/src/main/resources/static/css/app.css:4374). S2/S5.

Đề xuất: desktop có cột rõ ràng, mobile thành card có nhãn; một toolbar; menu thao tác phụ; detail riêng cho ticket và editor nghe hiểu; mô tả dài chỉ mở khi xem chi tiết. Giữ cuộn tự nhiên khi cần, không đặt chiều cao cố định làm cắt dữ liệu.

### A07 — P2 — Khả năng tiếp cận chưa đạt ở các vị trí đã đo

**Axe xác nhận tương phản chữ nhỏ:** nhãn section màu `#c9442d` trên nền `#f4f0e6` khoảng 4,24:1, dưới 4,5:1; nhãn thư viện khoảng 4,21:1. Axe còn báo link tài khoản mobile thiếu tên truy cập; CSS ẩn strong/small và thay nhãn bằng pseudo-element. Cần xác minh tên link bằng screen reader sau khi sửa, không dựa riêng vào chữ nhìn thấy.

Bằng chứng: [axe mobile](C:/Users/HP/Desktop/English_dictition/docs/admin-audit-assets/axe-mobile.json), [app.css:3264](C:/Users/HP/Desktop/English_dictition/src/main/resources/static/css/app.css:3264), [admin.html:36](C:/Users/HP/Desktop/English_dictition/src/main/resources/static/admin.html:36). S1. Skip link, focus style, aria-current và tiêu đề theo route đã có; không xóa các cơ chế này để làm giao diện gọn.

### A08 — P2 — Chưa có trạng thái rỗng và thông tin phân trang thống nhất

Support không có dòng giải thích khi rỗng, không hiện số trang hiện tại/tổng kết quả; users khi rỗng chỉ còn tổng và danh sách trống. Listening cũng không có thông báo rỗng riêng. Users/support chỉ có trước/sau, trong khi nội dung dùng paginator khác. Phân trang đơn giản là hợp lệ, nhưng phải cho biết đang ở đâu; không bắt buộc nút nhảy mọi trang.

Bằng chứng: [ảnh hỗ trợ rỗng](C:/Users/HP/Desktop/English_dictition/docs/admin-audit-assets/support-empty.png), [account.js:167](C:/Users/HP/Desktop/English_dictition/src/main/resources/static/js/account.js:167), [account.js:222](C:/Users/HP/Desktop/English_dictition/src/main/resources/static/js/account.js:222). S2/S5.

### A09 — P2 — Quy trình duyệt nội dung nghe hiểu thiếu bước xem và nghe kiểm tra

Editor chỉ in `audio_url`, không có audio player/transcript đồng bộ; bài published chỉ có tạo bản sửa/ngừng phát hành, không có xem chỉ đọc ngay trong admin. Bộ chọn source có những nhãn giống hệt nhau vì chỉ ghi level/tên/số câu, dù audio khác nhau. Nút “Danh sách bài nghe” đưa sang trang học viên, dễ hiểu nhầm là quay về admin. Khi xuất bản bản mới, server tự ngừng những bản cùng source nhưng UI chưa trình bày rõ ảnh hưởng.

Bằng chứng: [listening.js:184](C:/Users/HP/Desktop/English_dictition/src/main/resources/static/js/listening.js:184), [listening.js:203](C:/Users/HP/Desktop/English_dictition/src/main/resources/static/js/listening.js:203), [ListeningComprehensionService.java:542](C:/Users/HP/Desktop/English_dictition/src/main/java/com/dictalab/service/ListeningComprehensionService.java:542), [ảnh editor](C:/Users/HP/Desktop/English_dictition/docs/admin-audit-assets/listening-editor.png). S5 + yêu cầu chất lượng nội dung của sản phẩm.

Nghiệm thu: nguồn có ID/tên audio phân biệt, nghe trước, đối chiếu transcript/facts/câu hỏi, xem bản published không cần tạo bản sao; trước xuất bản hiển thị bản bị thay thế và trạng thái kiểm tra.

### A10 — P2 — Nhật ký và chi tiết xử lý hỗ trợ còn thiếu

Users đã có nhật ký nhưng UI bỏ thời điểm dù API trả `created_at`; API chỉ lấy 100 bản gần nhất. Ticket không hiện ngày tạo/cập nhật dù API có dữ liệu, không có link đi tới tài khoản/bài học liên quan cho AUDIO/TRANSCRIPT. `admin_note` là một trường bị ghi đè khi trả lời; audit ticket chỉ lưu ID + status, không tạo lịch sử phản hồi đọc lại trong UI. Đây là thiếu khả năng truy vết công việc, không phải yêu cầu làm hệ thống helpdesk lớn.

Bằng chứng: [account.js:205](C:/Users/HP/Desktop/English_dictition/src/main/resources/static/js/account.js:205), [account.js:223](C:/Users/HP/Desktop/English_dictition/src/main/resources/static/js/account.js:223), [AccountManagementService.java:200](C:/Users/HP/Desktop/English_dictition/src/main/java/com/dictalab/service/AccountManagementService.java:200), [SupportController.java:128](C:/Users/HP/Desktop/English_dictition/src/main/java/com/dictalab/web/SupportController.java:128). S4/S5.

Nghiệm thu: chi tiết ticket có người gửi, ngày giờ, nguồn liên quan, trạng thái và lịch sử ai xử lý; phản hồi cho học viên phân biệt rõ với ghi chú nội bộ nếu có. Màn chi tiết người dùng tập hợp thông tin hiện có, lý do khóa và hỗ trợ liên quan; không cần quyền xem mật khẩu hoặc sửa tùy tiện mọi dữ liệu cá nhân.

### A11 — P2 — Thiếu bảo vệ ghi đè khi hai người cùng chỉnh và chống gửi lặp chưa đồng đều

Listening update/ticket update không nhận phiên bản bản ghi từ client. Metadata Dictation cũng không có version trong DTO để phát hiện một form mở từ trước. Khóa transaction không tự phát hiện form đã cũ. Một số hành động có disabled khi chờ nhưng lưu metadata, thêm câu, tạo thủ công/copy/lưu/xuất bản nghe hiểu chưa đồng đều.

Bằng chứng: [ListeningComprehensionService.java:529](C:/Users/HP/Desktop/English_dictition/src/main/java/com/dictalab/service/ListeningComprehensionService.java:529), [SupportController.java:128](C:/Users/HP/Desktop/English_dictition/src/main/java/com/dictalab/web/SupportController.java:128), [app.js:2585](C:/Users/HP/Desktop/English_dictition/src/main/resources/static/js/app.js:2585), [listening.js:225](C:/Users/HP/Desktop/English_dictition/src/main/resources/static/js/listening.js:225). **Thiếu từ mã; chưa chạy tình huống đồng thời.** S3/S5 và tính toàn vẹn dữ liệu.

Nghiệm thu: form cũ nhận thông báo xung đột thay vì ghi đè im lặng; khóa nút theo request; thử double click/timeout rồi retry. Không cần áp dụng idempotency framework cho mọi API.

### A12 — P2 — Phân trang nội dung chỉ giảm số dòng hiển thị

API bài nghe tải toàn bộ lesson; mỗi lesson gọi thêm kiểm tra lịch sử và đếm câu (mẫu truy vấn 1 + 2N). Listening tải toàn bộ units gồm `content_json` rồi mới phân trang phía client. Dữ liệu nhỏ vẫn dùng được; chưa đo tải production nên không kết luận hiện đang chậm.

Bằng chứng: [AdminContentService.java:49](C:/Users/HP/Desktop/English_dictition/src/main/java/com/dictalab/service/AdminContentService.java:49), [ListeningComprehensionService.java:522](C:/Users/HP/Desktop/English_dictition/src/main/java/com/dictalab/service/ListeningComprehensionService.java:522), [listening.js:211](C:/Users/HP/Desktop/English_dictition/src/main/resources/static/js/listening.js:211). C12, nhu cầu tăng dữ liệu.

Nghiệm thu: lọc/sort/page ở server với thứ tự ổn định, query tổng hợp metadata; chỉ lấy JSON chi tiết khi mở editor. Đo số query/payload trước và sau bằng dữ liệu đại diện.

## Bộ lọc phù hợp từng màn

| Màn | Hiện có | Cần trong đợt chuẩn hóa | Bổ sung sau nếu có nhu cầu |
|---|---|---|---|
| Bài nghe | Tìm title/slug/level/topic; 10 dòng/trang client | Level; trạng thái xuất bản; chủ đề; nguồn; sort cập nhật/tên; tìm kiếm + xóa lọc + URL | Thiếu audio/đã khóa nội dung; khoảng ngày khi có khối lượng lớn |
| Nghe hiểu | 10 dòng/trang client | Tìm tên/ID; level; nháp/xuất bản/ngừng; bài nguồn; sort mới nhất/tên; URL | Người tạo/ngày cập nhật sau khi có metadata truy vết |
| Người dùng | q, role, enabled; 20 dòng/trang server; hiện lỗi A01 | Sửa A01; email đã/chưa xác minh; sort ngày tạo/lần đăng nhập; xóa lọc và URL | Khoảng ngày đăng ký/đăng nhập; không gom thêm dữ liệu cá nhân chỉ để lọc |
| Hỗ trợ | OPEN/IN_PROGRESS/RESOLVED; 20 dòng/trang server | Tìm ID/tiêu đề/email; loại vấn đề; tất cả trạng thái; loại bài liên quan; cũ/mới trước; ngày tạo; URL | Người phụ trách/ưu tiên khi thực sự có nhiều người vận hành |

Quy tắc chung: lọc kết hợp bằng AND, đổi điều kiện về trang đầu, phân trang trên kết quả đã lọc, reset một lần về mặc định, nhãn Việt cho trạng thái, thứ tự có ID làm tie-breaker. Back/reload giữ q/filter/sort/page. Trang vượt giới hạn sau xử lý bản ghi phải được chuẩn hóa. Backend vẫn kiểm tra tham số; không chỉ lọc ở frontend.

## Chức năng nghiệp vụ còn cần bổ sung

| Chức năng | Hiện trạng | Ưu tiên/quyết định |
|---|---|---|
| Chi tiết người dùng + nhật ký có thời gian | Thông tin và action rải trong card, không có trang chi tiết | P2; gom dữ liệu đang có, thêm link ticket và thu hồi phiên có lý do nếu vận hành cần |
| Duyệt/xem trước bài nghe hiểu | Chỉ editor câu hỏi; thiếu audio/transcript để kiểm tra | P2, A09; cần cho chất lượng chấm điểm |
| Sửa nội dung Dictation đã có lịch sử | Server khóa đúng để bảo toàn kết quả; hướng dẫn tạo bài mới nhưng chưa có luồng sao chép thuận tiện | P2; thêm sao chép thành nháp, giữ nguyên bản cũ và lịch sử |
| Bỏ/lưu trữ nháp nghe hiểu không dùng | Một source chỉ cho một draft, UI không có bỏ nháp | P2; lưu trữ nháp rõ trạng thái, không xóa bản có lịch sử |
| Chi tiết hỗ trợ, lịch sử xử lý, link ngữ cảnh | Một phần; riêng SCORE/LISTENING đã có chấm tay | P2, A10; bổ sung ngữ cảnh cho các category còn lại |
| Theo dõi AI chấm lỗi/chờ lâu | Có worker và xử lý ticket, chưa có màn quản trị hàng đợi | P2 sau lỗi chính; danh sách pending/failed, thời điểm, số lần thử, thao tác thử lại giới hạn và log. Không hiển thị API key |
| Nhật ký sửa/xuất bản nội dung | Chưa thấy lịch sử hành động nội dung tương đương account audit trong flow đã đọc | P2; actor/time/action/object, tránh ghi dữ liệu nhạy cảm không cần thiết |
| Phản hồi validation cạnh trường | Chủ yếu thông báo chung/toast; native validation một phần | P2; ưu tiên editor dài, nêu rõ số câu/field lỗi, focus đến lỗi |
| MFA/đăng nhập lại cho thao tác nhạy cảm | Chưa kiểm chứng đầy đủ luồng xác thực nâng cao | Đánh giá rủi ro riêng trước public rộng; không gộp vào yêu cầu bố cục |
| Giao việc/ưu tiên ticket | Chưa có | Có điều kiện: cần khi nhiều admin cùng hỗ trợ, chưa bắt buộc cho một người vận hành |
| Dashboard biểu đồ, export CSV/Excel, bulk action | Chưa có trong admin hiện tại | Chưa cần trong đợt này; chỉ làm khi có bài toán vận hành cụ thể |
| Billing, đơn hàng, coupon, quản lý tenant | Không thuộc mô hình hiện tại | Không áp dụng |
| Cho admin xem mật khẩu người dùng | Không có | Không bổ sung; dùng gửi liên kết reset hiện có |

## Kết quả checklist và vai trò

| ID | Dictation | Listening | Users | Support |
|---|---|---|---|---|
| C01 | Có route/page/focus | Có route; back dễ nhầm, A09 | Có route; mất filter khi quay lại | Có route; mất filter khi quay lại |
| C02 | Một phần, A05 | Thiếu, A05 | Lỗi, A01 | Một phần, A05 |
| C03 | Có page, thiếu sort | Có page, thiếu sort | Có server page; thiếu sort | Có server page; thiếu chỉ báo/sort |
| C04 | Một phần | Lỗi trình bày, A06 | Một phần, A06 | Một phần, A06 |
| C05 | Không tràn ngang ở mẫu 390/320 | Không tràn ngang ở mẫu 320 | Không tràn ngang ở mẫu 390 | Đã xem mẫu 390; chưa đo đầy đủ |
| C06 | A07; chưa full keyboard/SR | Chưa full a11y | Tương phản A07 | Chưa full a11y |
| C07 | Có validation server; A03 | A02/A03; lỗi chung | A01; action có xác nhận | A03/A10 |
| C08 | Có một phần lỗi tải/lưu | Có toast; chưa đầy đủ retry | A01/A04 | A04/A08 |
| C09 | A03/A11 | A03/A11 | Có prompt/confirm; chưa test đồng thời | A03/A10/A11 |
| C10 | Có chặn learner/guest | Có chặn learner/guest | Có chặn learner/guest | Có chặn learner/guest |
| C11 | Khóa nội dung đã dùng; thiếu copy/audit | Có copy/retire; thiếu preview/bỏ nháp | Có khóa/role/reset/audit; thiếu detail | Có resolve/chấm tay/xóa theo yêu cầu; thiếu timeline |
| C12 | A12 | A12 | Page server có giới hạn | Page server có giới hạn; chưa đo index/tải |

| Vai trò/luồng | Bằng chứng | Kết luận giới hạn |
|---|---|---|
| Khách mở admin | Local chuyển `/login?next=%2Fadmin%2Fusers` | Đã xác nhận |
| Học viên mở admin/API | Local trang 403; GET users/lessons/listening units/support đều 403 | Đã xác nhận bốn đường đọc; chưa kiểm mọi mutation |
| Admin | Đã mở bốn màn, editor, phân trang/lọc nêu trên | Đã xác nhận trên local |
| Bị khóa/thu hồi quyền | AccountStateFilter kiểm tra enabled/authVersion/email/role mỗi request | Có trong mã; chưa tái hiện phiên đang mở trong lần audit này |
| Tự khóa/tự hạ quyền/admin cuối | Service có chặn, có test nguồn liên quan | Không gọi là thiếu; chưa test cạnh tranh hai admin đồng thời |
| CSRF và quyền server | SecurityConfig có CSRF + hasRole cho trang/API admin | Có trong mã; audit này không phải pentest đầy đủ |
| Xóa tài khoản | Server yêu cầu ticket ACCOUNT_DELETE + email đúng; chặn xóa admin/chính mình và khi đang trong phòng | Có trong mã; chưa chạy xóa dữ liệu thật |
| Chấm lại nhớ ý | Form chấm từng fact và API kiểm tra ticket/session | Có trong mã; chưa chấm live với ShopAIKey |

## Thứ tự sửa đề xuất và tiêu chí bàn giao

1. **Đợt 1 — Sửa tính đúng đắn:** A01–A04; không mất dữ liệu editor; giữ đúng đáp án; catch lỗi mọi handler danh sách. Test hồi quy phải mô phỏng DOM/form thật và thao tác người dùng, không chỉ kiểm chuỗi trong file. Giữ script A02 làm ca lỗi để chuyển thành test chính thức khi sửa.
2. **Đợt 2 — Chuẩn hóa bốn màn:** toolbar/filter/sort/page/URL, danh sách desktop/mobile, empty/loading/error, nhãn và hành động nhất quán; chi tiết/editor tách rõ; sửa tương phản/tên link và validation. Không cần cài thư viện UI mới chỉ để làm bảng.
3. **Đợt 3 — Hoàn thiện quản trị nội dung và vận hành:** preview + version lifecycle, chi tiết người dùng/ticket và audit, bảo vệ xung đột, server pagination và query tổng hợp, hàng đợi chấm lỗi nếu cần.

Điều kiện nghiệm thu chung: qua các vai trò ở bảng trên; lọc kết hợp/back/reload/page cuối; dữ liệu dài/rỗng; lỗi 400/401/403/404/409/429/5xx và mạng chậm; dữ liệu bẩn khi rời trang; double click; hai tab sửa cùng bản; keyboard-only; focus sau render; zoom/reflow 320px; axe có kiểm tra chạy thật và screen reader cho các điều khiển chính. Kiểm thử luồng learner/Dictation/Listening sau thay CSS/JS dùng chung. Đánh dấu từng ca chưa chạy, không gộp tất cả thành “đã đạt chuẩn”.

## Thay đổi trong lần audit

Chỉ thêm báo cáo, ảnh/log bằng chứng và script tái hiện chỉ đọc. Không sửa mã ứng dụng, không deploy, không thay tài khoản hoặc nội dung trên VPS. Preview H2 dùng riêng được đóng sau kiểm tra. Báo cáo tiếng Việt được kiểm tra UTF-8.
