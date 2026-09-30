package com.dictalab.room;

public enum RoomStatus {
    /** Phòng đã tạo, chờ guest vào */
    WAITING_GUEST,
    /** Guest đã gửi yêu cầu, đang chờ host duyệt */
    PENDING_APPROVAL,
    /** Cả hai đã được duyệt, có thể thiết lập cuộc gọi */
    ACTIVE,
    /** Phòng đã đóng */
    CLOSED
}
