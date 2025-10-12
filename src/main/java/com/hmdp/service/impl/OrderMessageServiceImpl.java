package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.constant.MessageStatus;
import com.hmdp.entity.OrderMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.OrderMessageMapper;
import com.hmdp.service.IOrderMessageService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 继承 MyBatis-Plus 的 ServiceImpl<OrderMessageMapper, OrderMessage> 的作用：
 * 提供通用 CRUD 实现：直接用 save、getById、list、page、updateById、removeById、saveBatch、saveOrUpdate 等，无需手写基础代码。你在 creatOrderMessage 里能直接调用 save(orderMessage) 就是因为继承了它。
 * 自动注入 baseMapper：内置 baseMapper 即 OrderMessageMapper，可直接调用自定义的 Mapper 方法（如 baseMapper.customQuery(...)）。
 * 提供链式条件构造器：lambdaQuery()、lambdaUpdate()，类型安全且少写字段名字符串。
 * 泛型绑定更安全：<Mapper, Entity> 绑定后，IDE 有良好补全和类型检查。
 * 可拓展业务：继续 implements IOrderMessageService，在此基础上添加/重写业务方法即可。
 * MyBatis(MyBatis-Plus) 在运行时为 OrderMessageMapper 生成 Mapper
 * 代理实现；ServiceImpl<OrderMessageMapper, OrderMessage> 只是帮你注入这个代理为 baseMapper，
 * 并且把通用的 IService 方法（如 save/getById/list/page/updateById/removeById 等）用 baseMapper 封装好了。
 */
@Slf4j
@Service
public class OrderMessageServiceImpl extends ServiceImpl<OrderMessageMapper, OrderMessage> implements IOrderMessageService {

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Resource
    private ObjectMapper objectMapper;

    private static final String SECKILL_ORDER_TOPIC = "seckill-order1";


    @Override
    public OrderMessage creatOrderMessage(VoucherOrder voucherOrder, String transactionId) {
        try{
            String businessKey = voucherOrder.getVoucherId() + "_" + voucherOrder.getUserId();
            // 用 Jackson 的 ObjectMapper 将 Java 对象 VoucherOrder 序列化为 JSON 字符串，并赋值给变量 messageContent
            String messageContent = objectMapper.writeValueAsString(voucherOrder);

            OrderMessage orderMessage = new OrderMessage()
                    .setBusinessKey(businessKey)
                    .setOrderId(voucherOrder.getId())
                    .setUserId(voucherOrder.getUserId())
                    .setVoucherId(voucherOrder.getVoucherId())
                    .setMessageContent(messageContent)
                    .setStatus(MessageStatus.PENDING.getCode())
                    .setRetryCount(0)
                    .setMaxRetryCount(5)
                    .setTransactionId(transactionId)
                    .setCreateTime(LocalDateTime.now())
                    .setUpdateTime(LocalDateTime.now());

            save(orderMessage);
            return orderMessage;
        } catch (Exception e) {
            log.error("创建订单消息记录失败", e);
            throw new RuntimeException("创建订单消息记录失败", e);
        }
    }

    /**
     * 更新消息状态,包括错误信息???
     * @param businessKey
     * @param status
     * @param errorMessage
     */
    @Override
    public void updateMessageStatus(String businessKey, Integer status, String errorMessage) {
        /*
          首先，LambdaQueryWrapper<OrderMessage> 是 MyBatis-Plus 提供的一个条件构造器，用于构建查询条件。这里通过 eq 方法指定了查询条件：OrderMessage 的 businessKey 字段值必须等于传入的 businessKey 参数。
          eq 方法的第一个参数是字段的 Lambda 表达式（OrderMessage::getBusinessKey），第二个参数是字段的匹配值（businessKey）。这种写法避免了直接使用字符串字段名，提供了更高的类型安全性和可维护性。
          这一行只是构建了查询条件，相当于 SQL 的 where business_key = ?，本身不会执行查询。真正取数要看你后面调用的方法：
            获取单条（若多条会抛异常）：getOne(wrapper)
            获取所有匹配记录：list(wrapper)
            只统计数量：count(wrapper)
         */
        LambdaQueryWrapper<OrderMessage> wrapper = new LambdaQueryWrapper<OrderMessage>()
                .eq(OrderMessage::getBusinessKey, businessKey);

        // 据查询条件获取单条记录,果查询结果有多条记录，getOne 会抛出异常，因此通常在业务逻辑中确保查询条件是唯一的。
        OrderMessage orderMessage = getOne(wrapper);
        if(orderMessage != null){
            orderMessage.setErrorMsg(errorMessage);
        }

        // 如果发送失败，设置下一次重试时间（退避策略）
        if(MessageStatus.FAILED.getCode() == status){
            int retryCount = orderMessage.getRetryCount() + 1;
            orderMessage.setRetryCount(retryCount);
            // 指数退避算法计算下一次重试时间，2的指数次幂秒后重试
            long delaySeconds = (long) Math.pow(2, retryCount);
            orderMessage.setNextRetryTime(LocalDateTime.now().plusSeconds(delaySeconds));
        }else{
            // 超过最大重试次数，标记为死信消息
            orderMessage.setStatus(MessageStatus.DEAD_LETTER.getCode());
        }
    }

    @Override
    public List<OrderMessage> getRetryMessages() {
        LambdaQueryWrapper<OrderMessage> wrapper = new LambdaQueryWrapper<OrderMessage>()
                .eq(OrderMessage::getStatus, MessageStatus.FAILED.getCode())
                .le(OrderMessage::getNextRetryTime, LocalDateTime.now())
                .lt(OrderMessage::getRetryCount, 5); // 小于最大重试次数

        return list(wrapper);
    }

    @Override
    public void retryMessage(OrderMessage orderMessage) {
        try{
            // 更新消息状态为发送中
            updateMessageStatus(orderMessage.getBusinessKey(), MessageStatus.SENDING.getCode(), null);

            // 重新发送消息
            VoucherOrder voucherOrder = objectMapper.readValue(orderMessage.getMessageContent(), VoucherOrder.class);
            rocketMQTemplate.convertAndSend(SECKILL_ORDER_TOPIC, voucherOrder);

            // 更新消息状态为发送成功
            updateMessageStatus(orderMessage.getBusinessKey(), MessageStatus.SUCCESS.getCode(), null);
            log.info("重试发送消息成功, businessKey: {}", orderMessage.getBusinessKey());
        } catch (Exception e) {
            log.error("重试发送消息失败, businessKey: {}", orderMessage.getBusinessKey(), e);
            updateMessageStatus(orderMessage.getBusinessKey(), MessageStatus.FAILED.getCode(), e.getMessage());
        }
    }

    @Override
    public void markAsDeadLetter(OrderMessage orderMessage) {
        updateMessageStatus(orderMessage.getBusinessKey(), MessageStatus.DEAD_LETTER.getCode(), "超过最大重试次数，标记为死信消息");
        log.warn("消息已标记为死信消息, businessKey: {}", orderMessage.getBusinessKey());
    }

    @Override
    public boolean messageExists(Long userId, Long voucherId) {
        String businessKey = voucherId + "_" + userId;
        // 查询是否存在状态为 SUCCESS 或 COMMIT 的消息
        LambdaQueryWrapper<OrderMessage> wrapper = new LambdaQueryWrapper<OrderMessage>()
                .eq(OrderMessage::getBusinessKey, businessKey)
                .in(OrderMessage::getStatus,
                        MessageStatus.SUCCESS.getCode(),
                        MessageStatus.COMMIT.getCode(),
                        MessageStatus.CONSUME_SUCCESS.getCode());
        return count(wrapper) > 0;
    }
}
