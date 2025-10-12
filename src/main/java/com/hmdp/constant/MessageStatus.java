package com.hmdp.constant;

/**
 * 消息状态枚举
 */
public enum MessageStatus {
    PENDING(0, "待发送"),
    COMMIT(1, "已提交"),
    ROLLBACK(2, "已回滚"),
    SENDING(3, "发送中"),
    SUCCESS(4, "发送成功"),
    FAILED(5, "发送失败"),
    DEAD_LETTER(6, "死信消息"),
    CONSUME_SUCCESS(7,"消费成功");

    private final int code;
    private final String desc;

    MessageStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static MessageStatus getByCode(int code) {
        for (MessageStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return null;
    }
}