package com.hmdp.utils;

import com.hmdp.constant.MessageStatus;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IOrderMessageService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Slf4j
@Component
// 事务消息监听器
@RocketMQTransactionListener(txProducerGroup = "dianping-seckill-voucher-tx-producer")
public class OrderTransactionalListener implements RocketMQLocalTransactionListener {
    @Resource
    private IOrderMessageService orderMessageService;

    /**
     * 执行本地事务
     * @param message
     * @param o
     * @return
     */
    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message message, Object o) {
        try{
            VoucherOrder voucherOrder = (VoucherOrder) o;
            String businessKey = voucherOrder.getVoucherId() + "_" + voucherOrder.getUserId();

            log.info("执行本地事务，业务key：{}",businessKey);
            // 执行幂等性检查
            if(orderMessageService.messageExists(voucherOrder.getUserId(),voucherOrder.getVoucherId())){
                log.warn("订单已存在，事务回滚，业务key：{}",businessKey);
                orderMessageService.updateMessageStatus(businessKey, MessageStatus.ROLLBACK.getCode(), "订单已存在");
                return RocketMQLocalTransactionState.ROLLBACK;
            }

            // todo 这里可以执行其他本地事务逻辑，比如预扣库存、预创建订单
            // 将状态更新为已提交
            orderMessageService.updateMessageStatus(businessKey, MessageStatus.COMMIT.getCode(), null);
            return RocketMQLocalTransactionState.COMMIT;
        }catch (Exception e){
            log.error("执行本地事务异常",e);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    /**
     * 检查本地事务状态
     * @param message
     * @return
     */
    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message message) {
        try{
            String businessKey = new String((byte[]) message.getHeaders().get("businessKey"));
            log.info("检查本地事务状态，业务key：{}",businessKey);

            // todo 查询消息状态，这里可以根据业务需要实现具体逻辑
            return RocketMQLocalTransactionState.COMMIT;
        }catch (Exception e){
            log.error("检查本地事务状态异常",e);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }
}
