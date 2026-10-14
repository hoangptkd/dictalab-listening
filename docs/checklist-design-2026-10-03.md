# DictaLab — Audit theo Checklist Design

> Trạng thái sau sửa và kiểm chứng bổ sung nằm trong [báo cáo sửa đổi](checklist-design-fixes-2026-10-03.md). Các kết luận bên dưới ghi nhận bản production tại thời điểm audit, không mô tả bản sửa local.

Ngày kiểm tra: **03/10/2026**, múi giờ Asia/Bangkok.
Website: [DictaLab](https://160.191.244.27:18443/).
Skill đã cài: `checklist-design`, phiên bản 3.2.4. Chế độ: **audit**.

## Kết luận

**Web có nền giao diện tốt, nhưng chưa đáp ứng đầy đủ các checklist đã kiểm tra.** Phong cách màu/chữ rõ, biểu mẫu có nhãn và các nhóm màn hình có điều hướng phù hợp. Những điểm cần bổ sung tập trung vào khả năng tự kiểm tra mật khẩu, ngữ cảnh số liệu, vị trí hành động trên điện thoại và hướng dẫn hỗ trợ.

Đây là đánh giá những màn và trạng thái được nêu dưới đây, không phải kết luận về mọi trạng thái của sản phẩm hay chứng nhận khả năng tiếp cận.

### Ưu tiên cải thiện

- **Biểu mẫu mật khẩu:** bổ sung hiện/ẩn mật khẩu; bỏ “Ít nhất 8 ký tự” khỏi placeholder đăng nhập vì tài khoản hiện có không nhất thiết theo quy tắc đăng ký mới. Bằng chứng: [đăng nhập](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/login-desktop.png) và [đăng ký](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/register-desktop.png).
- **Tiến bộ trên điện thoại:** đưa “Luyện bài này” lên gần phần tổng quan hoặc cho hành động ngắn ở đầu trang. Nút hiện bắt đầu tại khoảng 2.801 px trên viewport 390×844. Bằng chứng: [Tiến bộ toàn trang](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/dashboard-mobile-full.png).
- **Ngữ cảnh số liệu:** ghi rõ kỳ đo cho chỉ số tổng quan; bổ sung ngày/buổi trên biểu đồ để đọc được xu hướng. Bằng chứng: [Tiến bộ máy tính](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/dashboard-desktop-full.png).
- **Hỗ trợ và xóa tài khoản:** nói rõ thời gian phản hồi, nơi nhận trả lời; khi mở từ “Yêu cầu xóa tài khoản hoặc dữ liệu”, chọn sẵn đúng loại yêu cầu. Bằng chứng: [Tài khoản](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/account-desktop.png) và [Hỗ trợ](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/support-desktop.png).
- **Lịch sử rỗng theo bộ lọc:** dùng “Chưa có buổi luyện A1” thay câu dễ hiểu thành chưa học lần nào; mời chuyển về Tất cả. Bằng chứng: [Lịch sử lọc A1](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/history-empty-mobile.png).

## Phạm vi và cách kiểm tra

- Mở bản HTTPS đang triển khai, đăng nhập bằng tài khoản được người dùng cung cấp, đọc nội dung hiển thị, chụp ảnh và đo bố cục.
- Kích thước chính: máy tính 1440×1000 và điện thoại 390×844; kiểm tra thêm bố cục Tiến bộ ở 320×740.
- Những màn có bảng checklist bên dưới được đối chiếu từng mục. Hội thoại, Phòng và kết quả học đã được khảo sát bố cục nhưng chưa kiểm tra trọn luồng tạo/làm bài/cuộc gọi.
- Không tạo tài khoản, gửi yêu cầu hỗ trợ, gửi email khôi phục, đổi thông tin tài khoản, bắt đầu bài học mới hoặc thực hiện thao tác quản trị ghi dữ liệu. Đăng nhập là thao tác được người dùng cho phép. Hồ sơ mật khẩu tạm của công cụ đã được xóa.
- Thử điều khiển menu bằng công cụ đầu tiên cho kết quả sai: không phát sinh sự kiện nhấp thật. Đã loại kết luận “menu hỏng” và kiểm tra lại bằng trình duyệt độc lập: nhấp chuột và Enter đều mở menu, trạng thái expanded=true, các liên kết xuất hiện.

| Màn / trạng thái | Máy tính | Điện thoại | Mức kiểm tra |
|---|---|---|---|
| Đăng nhập, đăng ký | Ảnh | Ảnh | Checklist, đăng nhập thành công |
| Khôi phục mật khẩu | Mã nguồn đối chiếu | Ảnh trang nhập email | Checklist một phần, chưa gửi email |
| Danh mục A1 | Ảnh | Ảnh và đo chiều rộng | Checklist thẻ và lọc |
| Tiến bộ có dữ liệu | Ảnh toàn trang | Ảnh, đo chiều rộng và vị trí nút | Checklist Dashboard |
| Tài khoản | Ảnh | Ảnh toàn trang | Checklist, chưa lưu biểu mẫu |
| Hỗ trợ chưa có yêu cầu | Ảnh | Ảnh toàn trang | Checklist hỗ trợ và trạng thái rỗng |
| Lịch sử và bộ lọc A1 rỗng | Ảnh danh sách | Ảnh danh sách, trạng thái rỗng | Checklist lọc và trạng thái rỗng |
| Nghe hiểu — danh mục | Ảnh | Ảnh toàn trang | Checklist thẻ và lọc |
| Hội thoại AI — chọn tình huống | Ảnh | Ảnh toàn trang | Khảo sát bố cục, chưa bắt đầu phiên |
| Luyện cùng bạn — tạo/nhập lời mời | Ảnh | Ảnh toàn trang | Khảo sát bố cục, chưa tạo phòng |
| Kết quả của một buổi học có sẵn | Ảnh toàn trang | Ảnh toàn trang | Khảo sát, chưa thêm Error Bank/luyện lại |
| Quản trị bài nghe | Ảnh | Ảnh toàn trang | Checklist Admin, chưa sửa bài |
| Quản trị người dùng | Chi tiết tài khoản đang dùng | Ảnh danh sách | Checklist Admin, chưa đổi quyền |
| Quản trị Nghe hiểu và Hỗ trợ | Ảnh | Chưa kiểm tra | Khảo sát danh sách |
| Khách mở /admin | Ảnh chuyển về đăng nhập | Chưa kiểm tra | Một nhánh của phân quyền |
| Menu Thêm | Không áp dụng | Ảnh mở menu, nhấp và Enter | Checklist Dropdown Menu |

Đo chiều rộng tài liệu ở 390 px trên Catalog, Tiến bộ, Tài khoản, Hỗ trợ, Nghe hiểu, Hội thoại, Phòng và Lịch sử đều cho kết quả 390 px. Điều này không thay thế kiểm tra mọi trạng thái, nội dung dài hoặc bàn phím ảo.

### Quy ước

🟢 Có · 🟡 Có một phần · 🔴 Thiếu và cần bổ sung · ⚪ Không cần trong ngữ cảnh này · ❔ Chưa xác định.

Tên và mô tả từng mục dưới đây giữ nguyên nội dung checklist; nhận xét viết bằng tiếng Việt. Không chấm điểm hoặc phần trăm đạt chuẩn.

## Đăng nhập

Đối chiếu [Login — Web app](https://www.checklist.design/web-app/login).

Bằng chứng: [login-desktop](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/login-desktop.png), [login-mobile](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/login-mobile.png).

| | Mục checklist | Nhận xét |
|---|---|---|
| 🟡 | **Email and password fields** — The two standard authentication inputs: an email address field and a password field. | Có Email và Mật khẩu với nhãn cố định; placeholder “Ít nhất 8 ký tự” đưa quy tắc đăng ký vào đăng nhập dù tài khoản hiện có vẫn đăng nhập được với mật khẩu ngắn hơn. |
| 🔴 | **Show/hide password toggle** — A button alongside the password field that reveals or conceals what the user has typed | Không có nút hiện/ẩn mật khẩu; người dùng khó kiểm tra lỗi gõ, nhất là trên điện thoại. |
| 🟢 | **Forgot password** — A link that begins the password reset flow for users who cannot remember their credentials | Có “Quên mật khẩu?” dẫn đến trang nhập email khôi phục. |
| ⚪ | **Remember me** — A checkbox that persists the user's session across browser closures | Có thể chọn yêu cầu đăng nhập lại khi đóng trình duyệt; chưa có yêu cầu sản phẩm về lưu phiên dài hạn trên thiết bị cá nhân. |
| ⚪ | **SSO or social login** — Alternative authentication via a third-party identity provider (Google, Microsoft, GitHub) that bypasses the email/password form. | Luồng email và mật khẩu đủ cho sản phẩm hiện tại; đăng nhập qua nhà cung cấp khác là lựa chọn sản phẩm. |
| 🟢 | **Sign up link** — A link to the account creation screen for users who do not yet have an account | Tab “Tạo tài khoản” hiển thị ngay trong màn đăng nhập. |
| ❔ | **Error messages** — Feedback shown when authentication fails, indicating what the user should try next | Đăng nhập thành công đã xác minh; chưa tạo lỗi đăng nhập hoặc chụp thông báo thất bại, nên chưa đánh giá được cách hướng dẫn sửa lỗi. |

## Đăng ký

Đối chiếu [Sign up — Website](https://www.checklist.design/website/sign-up).

Bằng chứng: [register-desktop](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/register-desktop.png), [register-mobile](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/register-mobile.png).

| | Mục checklist | Nhận xét |
|---|---|---|
| 🟢 | **Logo** — Either your complete logo or a symbol mark | Logo DictaLab xuất hiện rõ trên cả hai kích thước. |
| 🟢 | **Title** — Tell the user this is where they create an account | Tab “Tạo tài khoản” cùng tiêu đề “Bắt đầu nhật ký nghe.” làm rõ mục đích màn hình. |
| 🟢 | **Description** — Describe the basic features of creating an account and set their expectations as to what they can achieve | Mô tả nói rõ kết quả luyện tập sẽ được lưu và theo dõi theo thời gian. |
| 🟢 | **Account identification** — A unique identifier for the user to log in with | Có trường Email làm định danh đăng nhập, với nhãn cố định. |
| 🟡 | **Setting a password** — A secure, private code that allows them to access their account in the future | Có mật khẩu và quy tắc chữ, số, độ dài; thiếu hiện/ẩn mật khẩu để người dùng tự kiểm tra nội dung đã gõ. |
| 🟢 | **Link to login** — A user may already have an account and landed on this page anyway | Có tab “Đăng nhập” để quay lại luồng tài khoản hiện có. |
| ⚪ | **Sign up via third party** — Reduce a few clicks to one, by allowing users to sign up via Facebook or Google | Đăng ký email đã đáp ứng luồng hiện tại; không cần ép thêm Google hoặc Facebook. |
| ⚪ | **Current active customer count** — Showcasing how many people are using and enjoying your product can evoke a fear of missing out for potential users | Đây là cửa vào ứng dụng học tập; không cần số người dùng để hoàn tất đăng ký, và không nên thêm số liệu chưa có căn cứ. |
| ⚪ | **Testimonial or social proof** — Reinforcing the brand and product never goes out of style | Social proof là nội dung marketing tùy chọn, không phải điều kiện để biểu mẫu đăng ký hoạt động. |
| ⚪ | **Blog post** — An opportunity to show off some amazing content to convert an unsure lead | Blog không cần cho việc tạo tài khoản học tập. |
| ⚪ | **Billing plans and trial period (if payment is involved)** — Clearly define the length of the trial period and when they will be charged | Màn đăng ký được quan sát không yêu cầu thanh toán hoặc chọn gói dùng thử. |

## Khôi phục mật khẩu

Đối chiếu [Resetting password — Flows](https://www.checklist.design/flows/resetting-password).

Bằng chứng: [login-desktop](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/login-desktop.png), [reset-mobile](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/reset-mobile.png).

| | Mục checklist | Nhận xét |
|---|---|---|
| 🟡 | **Place reset link close to password field** — Style it as a link to show it is clickable | Liên kết có mặt nhưng nằm dưới nút đăng nhập; đặt ngay cạnh nhãn Mật khẩu sẽ dễ nhận ra trước khi thử đăng nhập. |
| 🟢 | **Ask for account details to verify** — In this case it's usually the email address that's requested, because it can recognise your account and be the channel the link is securely sent to. Note: If the user already entered their email address on the previous login page, that can be prefill this field and speed up the flow! | Trang khôi phục yêu cầu Email tài khoản; chưa thấy cơ chế điền lại email từ màn đăng nhập. |
| ❔ | **Show information has been sent** — Based on the account information provided in Step 2, explain how they can continue. If an email was provided, send an email for the next step. If a mobile number was provided, send a code or link to open. | Chưa gửi yêu cầu khôi phục, nên chưa xác minh nội dung xác nhận gửi và trạng thái email thực tế. |
| ❔ | **The message sent explains next steps** — This could be a link to a page that allows the user to reset their password. It could also be a code for the user to provide on a page to verify their account, to then reset their password. | Chưa nhận hoặc đọc email khôi phục; cần kiểm tra email thật để xác minh hướng dẫn, thời hạn và bước tiếp theo. |
| 🟡 | **Reset the password!** — Whether it's a verification code or a link to click behind an email address, the next page should be a clear text field to enter a new password. You can provide guidelines if you have requirements for the password to pass a threshold of strength to be accepted. | Mã account.js khớp bản triển khai có trường Mật khẩu mới và giới hạn độ dài, nhưng không có dòng hướng dẫn chữ cái/chữ số như màn đăng ký; chưa quan sát giao diện mở bằng token thật. |
| ❔ | **Password successfully reset** — After the password has been reset, indicate the successful and push their momentum to their initial intent: logging in. | Chưa đổi mật khẩu tài khoản, nên chưa xác minh phản hồi thành công và bước quay về đăng nhập sau đổi. |

## Tiến bộ

Đối chiếu [Dashboard — Web app](https://www.checklist.design/web-app/dashboard).

Bằng chứng: [dashboard-desktop-full](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/dashboard-desktop-full.png), [dashboard-mobile-full](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/dashboard-mobile-full.png), [dashboard-mobile-top](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/dashboard-mobile-top.png).

| | Mục checklist | Nhận xét |
|---|---|---|
| 🟡 | **Welcome state** — A brief summary of what's happened since the last visit immediately re-orients returning users | Trang có tổng quan năng lực nhưng chưa tóm tắt điều gì thay đổi từ lần học hoặc lần truy cập gần nhất. |
| 🟡 | **Key metrics** — The numbers most relevant to the user's goals, readable without leaving the home screen | Các chỉ số buổi, phút, độ đúng và điểm đều có; chưa ghi rõ kỳ đo là toàn bộ thời gian hay khoảng thời gian cụ thể. |
| 🟡 | **Recent activity** — The most recently created or updated items, enabling users to resume work without hunting | Có biểu đồ các buổi gần đây, nhưng không có danh sách buổi gần nhất hoặc liên kết mở buổi đó ngay trong trang. |
| ⚪ | **Needs attention** — Time-sensitive items requiring a decision, surfaced here instead of buried in another view | Không thấy nhiệm vụ có hạn chót cần quyết định ngay; thẻ bài tiếp theo đã đáp ứng nhu cầu đề xuất luyện tập. |
| 🟡 | **Quick actions** — Shortcuts to the most common tasks, accessible without navigating away. | Có “Luyện bài này”, nhưng ở viewport 390×844 nút bắt đầu tại y≈2801 px; cần cuộn nhiều mới tới hành động chính. |
| ❔ | **Empty state** — The state shown when there is nothing to show yet, with a clear first action | Tài khoản đang có dữ liệu; cần ảnh của người học chưa có buổi nào để xác minh tổng quan rỗng và lời mời bắt đầu. |
| ⚪ | **Layout control (if applicable)** — Controls to show, hide, or reorder widgets so the home screen matches how the user works | Dashboard nhỏ có mục đích cố định; chưa cần chức năng sắp xếp lại widget. |

## Tài khoản

Đối chiếu [Account — Web app](https://www.checklist.design/web-app/account).

Bằng chứng: [account-desktop](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/account-desktop.png), [account-mobile](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/account-mobile.png).

| | Mục checklist | Nhận xét |
|---|---|---|
| ⚪ | **Profile photo** — A way for users to upload or change their profile image | Tài khoản học tập cá nhân không cần ảnh đại diện để định danh; tên hiển thị đã phục vụ nhu cầu hiện tại. |
| 🟢 | **Display name** — The name shown to other users or across the product interface e.g. username, email address, first and last name | Tên hiển thị có nhãn riêng, giá trị hiện tại và nút Lưu tên. |
| 🟢 | **Account details** — Fields for email address, phone number, job title, or other relevant identifying information based on the product and information collected | Có email, trạng thái xác minh, đổi email và đổi mật khẩu trong các khu vực tách biệt. |
| ⚪ | **Linked accounts (if applicable)** — A view of which third-party accounts are connected for sign-in or data access, with ability to disconnect | Chưa có đăng nhập bên thứ ba trong luồng đang dùng, nên không cần danh sách tài khoản liên kết. |
| 🟡 | **Save confirmation** — Clear feedback that changes have been saved, either inline or as a toast | account.js khớp bản triển khai có phản hồi lưu; nút “Lưu tên” vẫn bật khi chưa thay đổi giá trị, và chưa gửi biểu mẫu để kiểm chứng phản hồi thực tế. |
| 🟡 | **Delete or deactivate account** — Options to deactivate or permanently delete the account, clearly separated from other settings | Có đường dẫn “Yêu cầu xóa tài khoản hoặc dữ liệu”, nhưng sang trang hỗ trợ mặc định vẫn chọn lỗi Audio; nên chọn sẵn loại yêu cầu xóa và giải thích bước xử lý. |

## Hỗ trợ

Đối chiếu [Contacting support — Flows](https://www.checklist.design/flows/contacting-support).

Bằng chứng: [support-desktop](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/support-desktop.png), [support-mobile](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/support-mobile.png).

| | Mục checklist | Nhận xét |
|---|---|---|
| 🟢 | **Show a link to contact support** — This can be placed in a number of areas, such as: ‍ • footer in a website • settings in a mobile app • a page experiencing an error Use clear copy and easy to recognise visuals to highlight the link. | Có mục Hỗ trợ trong điều hướng máy tính và menu Thêm trên điện thoại; menu đã được xác minh bằng trình duyệt độc lập. |
| 🟢 | **Show methods of contact** — If you have one option, showcase it! If you have multiple options (chat, call, FAQ), list them out and represent each of their benefits. Chat is great for a specific, complicated issue that needs an instant response. On the other hand, FAQ is great for standard questions that are easy to explain. Also consider the order of the methods. A FAQ as the first option is great because it lets a user find their own answer without waiting on you to respond. But a direct chat can be seen as more convenient as it's less direct troubleshooting for the user. | Có một phương thức cụ thể: biểu mẫu gửi yêu cầu, kèm khu vực theo dõi yêu cầu đã gửi. |
| 🟡 | **Outline how to communicate and what is expected** — Once a method is chosen, make it clear on how the method will work. Illustrate the response time, and what the user will need to provide to receive support. | Có loại vấn đề, tiêu đề, mô tả, nhưng chưa nêu thời gian phản hồi và nơi người dùng sẽ nhận câu trả lời. |

## Lọc danh sách bài và lịch sử

Đối chiếu [Filtering items — Flows](https://www.checklist.design/flows/filtering-items).

Bằng chứng: [catalog-desktop](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/catalog-desktop.png), [history-desktop](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/history-desktop.png), [history-empty-mobile](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/history-empty-mobile.png), [listening-desktop](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/listening-desktop.png).

| | Mục checklist | Nhận xét |
|---|---|---|
| 🟢 | **Show action near item collection** — Place above or beside the collection it affects, using a recognizable icons and/or label. | Bộ lọc level nằm ngay trên danh sách ở Catalog, Lịch sử và Nghe hiểu. |
| 🟢 | **Show available filter options** — When the action is triggered, filter options can either be shown on the same page for immediate feedback, or on another page. This should be dependent on the amount of filtering that is possible, and whether you think the user is likelier to tweak filters ongoing, or apply several and then view results. It's also worthwhile considering a filtering priority order, with the most common options filtered sitting at the front. | Catalog/Lịch sử dùng tab level, Nghe hiểu dùng danh sách chọn có số lượng. |
| 🟢 | **Consider different filter types** — The standard filter is a multi-select option picker. But certain properties can benefit from a different way of managing the filtering. There's sliders, checkboxes, dropdowns and others to consider. Each property should be considered, asking yourself what feels like the easiest way to change this value. | Tab đơn chọn phù hợp việc chọn một level; danh sách chọn phù hợp nhiều mục trong trang Nghe hiểu. |
| 🟢 | **Show active filters clearly when applied** — On the item collection page, display which filters are currently applied. You can also choose to have a high level active state applied to the filter action to imply it is in use, if you find showing all applied filters is too cluttered. | Tab đang chọn có nền khác và trạng thái chọn; Nghe hiểu hiển thị giá trị level hiện tại. |
| 🟢 | **Provide easy filter removal** — Let users clear individual filters or all filters at once to allow easier refining of their results. | Lịch sử có “Tất cả”, Nghe hiểu có “Tất cả”; Catalog yêu cầu chọn một level vì đây là bước chọn độ khó. |
| 🟢 | **Show result count** — Not a must have, but this can be handy where it is meaningful for users to see what the total results value has reduced to. It will help users feel if their result is too narrow or broad, directing them to either add or remove filters. | Catalog có “bài phù hợp”, Lịch sử có tổng và phân trang, Nghe hiểu có số lượng trong bộ lọc và phân trang. |
| 🟡 | **Empty state** — It's possible that a combination of filters can product no results. Explain this clearly and suggest adjusting or clearing filters to see results. | Lọc Lịch sử A1 trả về “Chưa có buổi luyện nào.” dù tài khoản có lịch sử B1; cần nêu rõ “Chưa có buổi luyện A1” và mời chọn Tất cả. |

## Thẻ bài học và thông tin

Đối chiếu [Card — Design system](https://www.checklist.design/design-system/card).

Bằng chứng: [catalog-desktop](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/catalog-desktop.png), [listening-desktop](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/listening-desktop.png), [account-desktop](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/account-desktop.png), [catalog-mobile](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/catalog-mobile.png).

| | Mục checklist | Nhận xét |
|---|---|---|
| 🟢 | **Style** — Consider default background, border, shadow | Nền, viền, bo góc và bóng của các nhóm thẻ phân biệt rõ với nền trang. |
| 🟢 | **Consistency** — Ensure you have one base style for all cards | Các thẻ trong từng nhóm Catalog, Nghe hiểu và Tài khoản dùng bố cục cùng loại; biến thể vẫn giữ bảng màu chung. |
| ❔ | **Spacing** — A framework to sort your padding levels by | Khoảng cách quan sát được đủ thoáng, nhưng chưa thấy một hệ quy tắc spacing chung được công bố; cần chuẩn thiết kế để xác nhận mục này. |
| 🟢 | **Responsiveness** — Consider the structure of the content in all various screen sizes | Thẻ chuyển thành một cột ở điện thoại; những trang danh sách đã đo không có chiều rộng tài liệu vượt viewport 390 px. |
| 🟢 | **Content hierarchy** — The primary action/s you want users to perform | Nhãn/tiêu đề và thông tin bài đứng trước hành động; nút bắt đầu tách rõ khỏi các nút nghe preview. |

## Trạng thái không có dữ liệu ở Lịch sử và Hỗ trợ

Đối chiếu [Empty State — Web app](https://www.checklist.design/web-app/empty-state).

Bằng chứng: [history-empty-mobile](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/history-empty-mobile.png), [support-desktop](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/support-desktop.png).

| | Mục checklist | Nhận xét |
|---|---|---|
| ⚪ | **Illustration or icon** — A visual that signals the empty state and gives the screen some personality, rather than feeling broken | Một trạng thái rỗng ngắn trong ứng dụng này có thể dùng chữ và hành động rõ mà không cần thêm minh họa trang trí. |
| 🟡 | **Clear heading** — A short, plain-language title naming what's missing | Có “Chưa có buổi luyện nào.” và “Chưa có yêu cầu nào ở trang này.”; câu ở lịch sử lọc chưa chỉ rõ dữ liệu rỗng theo level. |
| 🟡 | **Supporting description** — A brief explanation of what belongs in this space, most useful for first-time users | Có thông báo ngắn nhưng thiếu hướng dẫn chọn Tất cả ở lịch sử và hướng dẫn gửi biểu mẫu ở danh sách hỗ trợ rỗng. |
| 🟢 | **Primary action** — A CTA pointing toward the next step: creating, importing, connecting etc | Lịch sử có “Bắt đầu luyện nghe”; Hỗ trợ có biểu mẫu tạo yêu cầu ngay bên cạnh. |
| 🟡 | **Zero state vs. no-results state** — A distinction between a screen that is empty because nothing has been created versus one that returned no search or filter results | Lịch sử đã có dữ liệu B1 nhưng trạng thái lọc A1 dùng cùng câu chữ như chưa từng học; cần phân biệt hai tình huống. |
| ❔ | **Error state variant** — A separate variant for when content failed to load, as opposed to genuinely being empty | Chưa quan sát lỗi tải dữ liệu thật; cần kiểm tra trạng thái lỗi trên môi trường thử để phân biệt mất kết nối với danh sách rỗng. |

## Menu Thêm trên điện thoại

Đối chiếu [Dropdown Menu — Design system](https://www.checklist.design/design-system/dropdown-menu).

Bằng chứng: [mobile-menu-native-click](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/mobile-menu-native-click.png).

| | Mục checklist | Nhận xét |
|---|---|---|
| 🟢 | **Trigger affordance** — The element that opens the menu: an overflow icon, chevron button, or right-click target | Nút “Thêm” có thể mở menu; đã kiểm chứng bằng nhấp chuột và Enter trong trình duyệt độc lập. |
| 🟢 | **Menu item anatomy** — The structure of each action: label, optional leading icon, optional trailing shortcut, optional destructive styling | Có ba nhãn rõ: Lịch sử, Luyện cùng bạn, Hỗ trợ; các mục đều là liên kết điều hướng. |
| ⚪ | **Section dividers and groups** — Dividers and/or labels that break related actions into sections once the menu gets large | Menu chỉ có các mục điều hướng cùng nhóm, nên không cần thêm đường chia nhóm. |
| ⚪ | **Destructive item styling** — Visual differentiation — typically the danger colour — for actions that cannot be undone | Menu không có hành động xóa hoặc không thể hoàn tác. |
| ⚪ | **Nested submenu** — A secondary menu triggered by a parent item, used for grouping related but distinct actions | Các mục đều là điều hướng trực tiếp, không cần submenu. |
| ⚪ | **Disabled items** — Menu items that are visible but cannot be triggered, indicating an unavailable action | Các đường dẫn đang có sẵn; không thấy lý do cần mục vô hiệu hóa. |
| 🟢 | **Positioning and overflow** — The menu's placement relative to its trigger, repositioning automatically to stay within the viewport | Ảnh menu mở cho thấy menu nằm phía trên nút và bên trong viewport điện thoại. |
| ⚪ | **Keyboard shortcut display** — Shortcut hints aligned to the trailing edge of the item label | Điều hướng tới các trang này chưa có bộ phím tắt sản phẩm; không cần gợi ý phím tắt tùy ý. |

## Quản trị

Đối chiếu [Admin Panel — Web app](https://www.checklist.design/web-app/admin-panel).

Bằng chứng: [admin-desktop](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/admin-desktop.png), [admin-guest-redirect](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/admin-guest-redirect.png), [admin-own-account-desktop](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/admin-own-account-desktop.png), [admin-listening-desktop](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/admin-listening-desktop.png).

| | Mục checklist | Nhận xét |
|---|---|---|
| 🟡 | **Role-based access** — The admin panel visible and accessible only to users with the appropriate permissions | Tài khoản admin mở được trang quản trị, khách bị chuyển về đăng nhập; chưa kiểm tra tài khoản USER nên chưa kết luận toàn bộ phân quyền. |
| 🟡 | **User management** — A view of all users in the organisation with the ability to invite, edit roles, and remove members | Có danh sách người dùng, bộ lọc và màn quản lý với nút khóa/đổi quyền; lời mời và loại bỏ thành viên chưa được kiểm chứng trong phạm vi này. |
| ⚪ | **Organisation settings** — Controls for configuring the product at an account level — name, logo, SSO, domains | Đây là ứng dụng học tập đơn lẻ, chưa có workspace/tổ chức với SSO hoặc domain riêng cần quản lý. |
| 🟡 | **Usage overview** — High-level metrics on how the product is being used across the organisation, with ability to export information for reporting | Có tổng bài và người dùng theo từng danh sách; chưa thấy tổng quan sử dụng toàn hệ thống hoặc xuất báo cáo ở các màn đã mở. |
| ⚪ | **Billing and plan management** — Access to subscription details, seat counts, and invoices at the account level | Các màn quản trị đang có không quản lý seat, hóa đơn hoặc gói thuê bao. |
| 🟢 | **Audit log** — A record of account related actions taken by users e.g. logins, permission changes, deletions | Màn quản lý tài khoản có khu vực “Nhật ký tài khoản”; chưa đánh giá tính đầy đủ của mọi loại sự kiện. |
| ⚪ | **Danger zone** — Destructive account-level actions e.g. deleting the workspace, transferring ownership | Không có thao tác xóa workspace hoặc chuyển sở hữu tổ chức trong sản phẩm đang quan sát; các thao tác xóa dữ liệu cụ thể cần kiểm tra riêng mà không thực thi. |

## Quan sát ngoài checklist

- **Biểu đồ Tiến bộ:** cột chỉ có nhãn B1, chưa có ngày/buổi hiển thị; phần giới thiệu gọi là “đường màu cam/xanh” trong khi hình hiện tại là cột. Nên làm lời mô tả và trục biểu đồ thống nhất. [Ảnh bằng chứng](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets/dashboard-desktop-full.png).
- **Tên bài Nghe hiểu trung tính là lựa chọn có chủ ý**, không phải lỗi đặt tên: tài liệu dự án nêu việc giảm lộ chủ đề trước khi hoàn thành đánh giá. Không đề xuất đưa tên giàu gợi ý vào trước bài đánh giá chỉ để danh sách hấp dẫn hơn.

## Những phần chưa thể kết luận

- Lỗi/success của đăng ký, gửi yêu cầu hỗ trợ, email khôi phục và đổi mật khẩu thực tế.
- Người học mới chưa có dữ liệu trên Tiến bộ và trường hợp USER truy cập quản trị.
- Nhập đáp án, phát/thu âm, cuộc gọi thực tế, phản hồi AI và tương tác bàn phím ảo trên điện thoại thật.
- Độ tương phản được đo đầy đủ, trình đọc màn hình, mọi thứ tự focus, browser khác, hover và các trạng thái tải/lỗi. Có focus và nhãn trong nguồn không đồng nghĩa mọi trạng thái đã được kiểm chứng.

Cần ảnh hoặc một lần kiểm tra riêng trên môi trường thử với tài khoản/dữ liệu thử để xác minh các mục ❔. Không suy từ một trang đang hoạt động thành toàn bộ luồng đã đạt.

## Nguồn và đối chiếu phiên bản

- Skill đã cài tại [SKILL.md](C:/Users/HP/.codex/skills/checklist-design/SKILL.md); các bảng dùng trực tiếp checklist đóng gói của phiên bản 3.2.4, giữ thứ tự và mô tả từng mục.
- HTML, CSS và account.js được so SHA-256 với bản đang phục vụ tại website và khớp mã nguồn workspace. [index.html](C:/Users/HP/Desktop/English_dictition/src/main/resources/static/index.html:42), [account.js](C:/Users/HP/Desktop/English_dictition/src/main/resources/static/js/account.js:40).
- app.js trên website khác app.js đang sửa trong workspace. Vì vậy kết luận về trang Tiến bộ, lịch sử và menu dựa trên ảnh/DOM của bản đang chạy, không coi mã app.js local là bằng chứng về phiên bản triển khai.
- Lựa chọn tên bài Nghe hiểu có mô tả tại [Tài liệu hệ thống](C:/Users/HP/Desktop/English_dictition/docs/current-system-reference.md:280).
- Toàn bộ ảnh lưu trong [thư mục bằng chứng](C:/Users/HP/Desktop/English_dictition/docs/checklist-design-2026-10-03-assets). Báo cáo không chứa mật khẩu.
