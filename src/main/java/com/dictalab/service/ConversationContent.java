package com.dictalab.service;

import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Curated opening tasks: a lesson can start without waiting for text generation. */
final class ConversationContent {
    record Scene(String id, String label, String groupId, String groupVi, String questionVi,
                 String english, String vietnamese, String firstEn, String firstVi, String secondEn, String secondVi,
                 String reasonEn, String reasonVi) {}
    static final List<Scene> SCENES = List.of(
        new Scene("cafe", "Gọi món ở quán", "food", "Ăn uống", "Khách gọi đồ uống gì và món ăn gì?", "I would like a tea and a cheese sandwich, please.", "Tôi muốn một trà và một bánh mì kẹp phô mai.", "a tea", "Trà", "a cheese sandwich", "Bánh mì kẹp phô mai", "I have not had lunch yet", "Khách chưa ăn trưa"),
        new Scene("restaurant", "Đặt bàn", "food", "Ăn uống", "Khách đặt bàn cho mấy người và vào ngày nào?", "I need a table for four people on Friday, please.", "Tôi cần một bàn cho bốn người vào thứ Sáu.", "four people", "Bốn người", "on Friday", "Thứ Sáu", "my family is visiting", "Gia đình đến thăm"),
        new Scene("wrong-order", "Báo món chưa đúng", "food", "Ăn uống", "Khách nhận nhầm món gì và muốn đổi sang món gì?", "This is tomato soup. I ordered chicken soup. Please bring me chicken soup instead.", "Đây là súp cà chua. Tôi đã gọi súp gà. Xin mang súp gà thay thế.", "This is tomato soup", "Nhận nhầm súp cà chua", "bring me chicken soup instead", "Đổi sang súp gà", "I do not eat tomatoes", "Khách không ăn cà chua"),
        new Scene("ingredients", "Hỏi thành phần", "food", "Ăn uống", "Món ăn có nguyên liệu gì và được ăn kèm với gì?", "This salad has eggs. We serve it with bread.", "Món salad này có trứng. Chúng tôi phục vụ kèm bánh mì.", "has eggs", "Trứng", "with bread", "Bánh mì", "it is our lunch special", "Đây là món đặc biệt buổi trưa"),
        new Scene("directions", "Hỏi đường", "transport", "Di chuyển", "Người nghe cần đi đâu và bằng phương tiện gì?", "Please go to the museum. Take the bus from here.", "Hãy đến bảo tàng. Đi xe buýt từ đây.", "to the museum", "Bảo tàng", "the bus", "Xe buýt", "the road is too busy for walking", "Đường quá đông để đi bộ"),
        new Scene("tickets", "Mua vé", "transport", "Di chuyển", "Khách muốn mua mấy vé và đi đâu?", "I would like two tickets to London, please.", "Tôi muốn mua hai vé đi London.", "two tickets", "Hai vé", "to London", "London", "my friend is travelling with me", "Bạn đi cùng"),
        new Scene("change-trip", "Đổi chuyến", "transport", "Di chuyển", "Khách muốn chuyển sang chuyến mấy giờ và vào ngày nào?", "Please move my trip to nine on Monday.", "Xin chuyển chuyến của tôi sang chín giờ thứ Hai.", "to nine", "Chín giờ", "on Monday", "Thứ Hai", "I have an appointment before the trip", "Có cuộc hẹn trước chuyến đi"),
        new Scene("taxi", "Đón xe", "transport", "Di chuyển", "Xe sẽ đón ở đâu và xe màu gì?", "Meet me outside the station. My car is blue.", "Gặp tôi bên ngoài nhà ga. Xe tôi màu xanh dương.", "outside the station", "Bên ngoài nhà ga", "blue", "Xanh dương", "cars cannot stop at the main door", "Xe không được dừng ở cửa chính"),
        new Scene("product", "Hỏi sản phẩm", "shopping", "Mua sắm", "Sản phẩm ở tầng nào và có màu gì?", "The bags are on the second floor. They are black.", "Túi ở tầng hai. Chúng có màu đen.", "second floor", "Tầng hai", "black", "Đen", "we keep travel items upstairs", "Đồ du lịch được để trên lầu"),
        new Scene("clothes", "Chọn kích cỡ", "shopping", "Mua sắm", "Khách cần cỡ nào và màu gì?", "I need a small shirt in green, please.", "Tôi cần áo cỡ nhỏ màu xanh lá.", "small", "Cỡ nhỏ", "green", "Xanh lá", "this is a gift for my sister", "Đây là quà cho chị hoặc em gái"),
        new Scene("returns", "Đổi trả hàng", "shopping", "Mua sắm", "Khách trả sản phẩm gì và muốn nhận lại gì?", "I want to return these shoes and get my money back.", "Tôi muốn trả đôi giày này và nhận lại tiền.", "these shoes", "Giày", "my money back", "Hoàn tiền", "they are too small for me", "Giày quá nhỏ"),
        new Scene("delivery", "Nhận hàng", "shopping", "Mua sắm", "Hàng được để ở đâu và ai sẽ ký nhận?", "Leave the box at reception. My brother will sign for it.", "Để hộp hàng ở quầy lễ tân. Anh hoặc em trai tôi sẽ ký nhận.", "at reception", "Quầy lễ tân", "My brother", "Anh hoặc em trai", "I will be at work all day", "Người nhận đi làm cả ngày"),
        new Scene("assignment", "Nhận nhiệm vụ", "workstudy", "Công việc và học tập", "Cần làm việc gì và gửi vào ngày nào?", "Please write the report and send it on Tuesday.", "Hãy viết báo cáo và gửi vào thứ Ba.", "write the report", "Viết báo cáo", "on Tuesday", "Thứ Ba", "our manager needs it for a meeting", "Quản lý cần báo cáo cho cuộc họp"),
        new Scene("meeting", "Đổi lịch họp", "workstudy", "Công việc và học tập", "Cuộc họp ở phòng nào và bắt đầu mấy giờ?", "Our meeting is in room six. It starts at ten.", "Cuộc họp ở phòng sáu. Bắt đầu lúc mười giờ.", "room six", "Phòng sáu", "at ten", "Mười giờ", "the larger room is being repaired", "Phòng lớn hơn đang sửa"),
        new Scene("requirements", "Hỏi yêu cầu", "workstudy", "Công việc và học tập", "Cần mang theo gì và gặp ai?", "Bring your notebook and meet Ms Green at the office.", "Mang theo sổ và gặp cô Green ở văn phòng.", "your notebook", "Sổ ghi chép", "Ms Green", "Cô Green", "she will explain your first task", "Cô ấy sẽ giải thích nhiệm vụ đầu tiên"),
        new Scene("group-project", "Chia việc nhóm", "workstudy", "Công việc và học tập", "Người nói phụ trách việc gì và người nghe làm việc gì?", "I will find the pictures. You can write the introduction.", "Tôi sẽ tìm ảnh. Bạn có thể viết phần giới thiệu.", "find the pictures", "Người nói tìm ảnh", "write the introduction", "Người nghe viết giới thiệu", "you already know the topic well", "Người nghe đã hiểu rõ chủ đề"),
        new Scene("hotel", "Nhận phòng", "services", "Sinh hoạt và dịch vụ", "Phòng số mấy và ăn sáng ở đâu?", "Your room is number eight. Breakfast is in the garden.", "Phòng của bạn số tám. Bữa sáng ở ngoài vườn.", "number eight", "Phòng tám", "in the garden", "Ngoài vườn", "the dining room is closed today", "Phòng ăn hôm nay đóng cửa"),
        new Scene("appointment", "Đặt lịch dịch vụ", "services", "Sinh hoạt và dịch vụ", "Lịch hẹn vào ngày nào và lúc mấy giờ?", "Your appointment is on Thursday at three.", "Lịch hẹn của bạn vào thứ Năm lúc ba giờ.", "on Thursday", "Thứ Năm", "at three", "Ba giờ", "this is our first free time this week", "Đó là thời gian trống đầu tiên trong tuần"),
        new Scene("repair", "Báo đồ hỏng", "services", "Sinh hoạt và dịch vụ", "Thiết bị nào hỏng và đang ở phòng nào?", "The lamp is broken. It is in the bedroom.", "Đèn bị hỏng. Nó ở phòng ngủ.", "lamp", "Đèn", "in the bedroom", "Phòng ngủ", "the light stopped working last night", "Đèn ngừng hoạt động tối qua"),
        new Scene("opening-hours", "Hỏi giờ hoạt động", "services", "Sinh hoạt và dịch vụ", "Thư viện mở cửa mấy giờ và đóng cửa vào ngày nào?", "The library opens at eight. It is closed on Sunday.", "Thư viện mở lúc tám giờ. Đóng cửa vào Chủ nhật.", "at eight", "Tám giờ", "on Sunday", "Chủ nhật", "staff have a training day", "Nhân viên có ngày tập huấn"),
        new Scene("introductions", "Làm quen", "social", "Giao tiếp xã hội", "Người nói tên gì và đến từ đâu?", "My name is Anna. I am from Canada.", "Tôi tên Anna. Tôi đến từ Canada.", "Anna", "Anna", "Canada", "Canada", "I have just joined this class", "Người nói vừa tham gia lớp học"),
        new Scene("plans", "Đổi lịch hẹn", "social", "Giao tiếp xã hội", "Hai người sẽ gặp vào ngày nào và ở đâu?", "Let us meet on Saturday at the park.", "Chúng ta gặp nhau thứ Bảy tại công viên.", "on Saturday", "Thứ Bảy", "at the park", "Công viên", "the cafe will be closed", "Quán cà phê sẽ đóng cửa"),
        new Scene("invitation", "Mời và từ chối", "social", "Giao tiếp xã hội", "Người nói mời làm gì và vào ngày nào?", "Please come to my birthday party on Sunday.", "Hãy đến tiệc sinh nhật của tôi vào Chủ nhật.", "birthday party", "Tiệc sinh nhật", "on Sunday", "Chủ nhật", "my friends are free that day", "Bạn bè rảnh vào ngày đó"),
        new Scene("weekend", "Thống nhất kế hoạch", "social", "Giao tiếp xã hội", "Hai người sẽ làm gì và gặp lúc mấy giờ?", "Let us play tennis. Meet me at four.", "Chúng ta chơi quần vợt nhé. Gặp tôi lúc bốn giờ.", "play tennis", "Chơi quần vợt", "at four", "Bốn giờ", "the court is free then", "Sân trống lúc đó")
    );
    static final Map<String,String> SKILLS = Map.of("details", "Nắm thông tin cụ thể", "gist", "Nắm ý chính",
            "reason", "Hiểu lý do và mục đích", "change", "Theo dõi thay đổi", "instructions", "Theo dõi hướng dẫn", "condition", "Hiểu điều kiện");
    static Map<String,String> labels() {
        var result = new LinkedHashMap<String,String>();
        SCENES.forEach(s -> result.put(s.id(), s.label()));
        return Collections.unmodifiableMap(result);
    }
    static ObjectNode starter(ObjectMapper json, String scenario, String level) {
        Scene s = SCENES.stream().filter(v -> v.id().equals(scenario)).findFirst().orElseThrow();
        String suffix = level.equals("A1") ? " Thank you." : " This is because " + s.reasonEn() + ".";
        String english = "Hello. " + s.english() + suffix;
        String question = s.questionVi() + (level.equals("A1") ? "" : " Lý do là gì?");
        var task = json.createObjectNode().put("taskVi", level.equals("A1") ? "Nghe hai thông tin trực tiếp." : "Nghe thông tin và lý do của người nói.")
                .put("questionVi", question).put("skill", SKILLS.get(level.equals("A1") ? "details" : "reason"))
                .put("utteranceEn", english).put("translationVi", "Xin chào. " + s.vietnamese()
                        + (level.equals("A1") ? " Cảm ơn." : " Lý do: " + s.reasonVi() + "."));
        var facts = task.putArray("facts");
        facts.addObject().put("id", "f1").put("textVi", s.firstVi()).put("evidenceEn", s.firstEn());
        facts.addObject().put("id", "f2").put("textVi", s.secondVi()).put("evidenceEn", s.secondEn());
        if (!level.equals("A1")) facts.addObject().put("id", "f3").put("textVi", s.reasonVi()).put("evidenceEn", s.reasonEn());
        return task;
    }
    static String skillId(String label) {
        return SKILLS.entrySet().stream().filter(e -> e.getValue().equals(label)).map(Map.Entry::getKey).findFirst().orElse("details");
    }
}
