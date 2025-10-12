package com.hmdp.service;

import com.hmdp.entity.OrderMessage;
import com.hmdp.entity.VoucherOrder;

import java.util.List;

public interface IOrderMessageService {
    OrderMessage creatOrderMessage(VoucherOrder voucherOrder, String transactionId);

    void updateMessageStatus(String businessKey, Integer status, String errorMessage);

    List<OrderMessage> getRetryMessages();

    void retryMessage(OrderMessage orderMessage);

    void markAsDeadLetter(OrderMessage orderMessage);

    /**
     * 检查消息是否存在(幂等性检查)
     */
    boolean messageExists(Long userId, Long voucherId);
}
