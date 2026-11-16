# Kiểm chứng lại Harness V5.2 bằng AI thật

Mã nguồn `023c821953c14b2decf6a44252a78631b3eedbbc`, prompt hash `5ac2eb0f3d04190bdc8e4dd5ecabf05d4f6d44f3d35edd2a7a99941a4006672b`. Đã đối chiếu hash nguồn, catalog và backend AI đang chạy; Core và AI local báo sẵn sàng. Không dùng tài khoản cá nhân, tạo tài khoản hoặc ghi điểm học viên.

Các ca bài học dưới đây phát lại transcript, câu hỏi và bản dịch thực đã lưu; các bước review và sửa gọi provider thật. Đây không phải sinh bài toàn luồng. Các request mới có ID riêng; kết quả cũ và 503 được giữ lại. Kiểm tra nội dung do agent tự đối chiếu nguồn, không phải nhãn chuyên gia.

| Ca | Phát hiện và sửa | Review sau sửa và Core | Lỗi còn lại |
|---|---|---|---|
| `toeic-basic-boxes` | Áp lại patch AI đã tạo ở bản thử trước: xóa `a2/q2` và `a2/m2`, không thêm câu thay thế. Các phần đúng và nguồn giữ nguyên. | Hai lượt review mới trên hash chốt gặp 503. Validator nội dung Core nhận artifact 2/1/2; chưa có kết quả Harness hoàn tất để kiểm cổng job. | Chưa chứng minh AI review sau sửa trên hash chốt. Không tính là sửa thành công toàn luồng. |
| `cefr-b2-handover` | Lượt mới gặp 503 tại review câu hỏi; chưa có patch. | Validator nội dung Core nhận artifact đã lưu, nhưng không chứng minh nội dung ngữ nghĩa hay job hoàn tất. | Đối chiếu lại thấy `a2/q2` đổi hạn chót “by six” thành “vào lúc sáu giờ”. Các câu trực tiếp hợp lệ không cần nhãn suy luận, nhưng lỗi hạn chót thực sự vẫn cần sửa; không kết luận toàn bài hợp lệ. |
| `cefr-a2-pottery` | Review câu hỏi đánh dấu `a3/q2` PASS dù “studio” trong ngữ cảnh lớp gốm bị dịch thành “phòng thu”. Chưa có patch. | Review sau dịch gặp 503; không có kết quả Core hoàn tất. | Lỗi dịch trong đáp án còn nguyên; reviewer bỏ sót. |
| `cefr-a1-plants` | Review đầu đánh dấu `a2/q2` PASS dù đáp án nguyên nhân chỉ lặp “đủ”; chưa có patch. | Review tập trung tiếp theo gặp 503; không có kết quả Core hoàn tất. | Câu nguyên nhân vô ích còn nguyên; reviewer đầu bỏ sót. |
| `ielts-basic-artclub` | Nguồn cho thấy nhân viên đánh vần tên giảng viên, nhưng `a1/q2` gán cho giảng viên. Review nguồn gặp 503; chưa có patch. | Chưa review sau sửa hoặc nhận kết quả ở Core. | Sai người nói chưa được kiểm chứng sửa. |
| `ielts-standard-essay` | Review câu hỏi đánh dấu `a3/q2` PASS dù đáp án làm mất nghĩa hạn chót “by tomorrow morning”. Chưa có patch. | Review sau dịch gặp 503; không có kết quả Core hoàn tất. | Hạn chót còn sai; reviewer bỏ sót. |
| Khóa chấm thừa ý | **Mẫu kiểm soát**, không phải artifact lỗi thực: câu hỏi chỉ hỏi giờ mở cửa, khóa bị thêm giá bánh mì. AI đánh dấu phần thừa không được câu hỏi yêu cầu, sửa đúng question/meaning/parts, giữ nguyên câu hỏi; review lại và chấm `CAPTURED`. | Kiểm tra Java gọi cổng Core trên chính kết quả AI: xác minh chuỗi Harness, khóa sửa và kết quả chấm đều đạt. Đây là kiểm tra cổng Core, không lưu điểm trong cơ sở dữ liệu. | Không suy rộng một mẫu kiểm soát thành tỷ lệ thành công. |

Chưa có lượt AI hoàn tất để đánh giá riêng các nhóm bằng chứng, điều kiện và định lượng. Không tính từ chối, lỗi provider, test mock, validator cấu trúc hoặc PASS của reviewer là sửa thành công. Đặc biệt, kết luận trước đó rằng artifact handover được nhận không đủ để khẳng định nội dung của toàn bài đúng.

Bằng chứng riêng được giữ ngoài Git tại `C:/Users/HP/.dictalab/local/`: `v52-final-confirmation-20261116.json`, `v52-final-box-postpatch-review-20261117.json`, `v52-final-scope-cases-20261116.json` và `v52-final-grade-scope-20261116.json`. Lượt grade có request ID `154e1577-877e-47b8-ba3c-dd241612c879`. Các lần fallback và lỗi 503 được ghi trong manifest/log tương ứng. Không merge hoặc triển khai production.
