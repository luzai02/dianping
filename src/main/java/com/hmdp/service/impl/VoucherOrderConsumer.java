package com.hmdp.service.impl;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.utils.RedisIdWorker;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
@RocketMQMessageListener(topic = "seckill-order1",consumerGroup = "seckill-order")
public class VoucherOrderConsumer implements RocketMQListener<VoucherOrder> {
    @Resource
    private VoucherOrderServiceImpl voucherOrderService;

    @Override
    public void onMessage(VoucherOrder voucherOrder) {
        log.info("收到消息：{}",voucherOrder.getVoucherId());
        try{
            voucherOrderService.handleVoucherOrder(voucherOrder);
        } catch (Exception e) {
            log.error("处理订单异常：{}",e.getMessage());
            throw new RuntimeException(e);
        }
    }

}
