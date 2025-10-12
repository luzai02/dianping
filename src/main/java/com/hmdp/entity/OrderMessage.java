package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 订单消息记录表
 */
@Data
@Accessors(chain = true)
@TableName("tb_order_message")
public class OrderMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 业务唯一键 (userId + voucherId)
     */
    private String businessKey;

    /**
     * 订单ID
     */
    private Long orderId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 优惠券ID
     */
    private Long voucherId;

    /**
     * 消息内容(JSON格式)
     */
    private String messageContent;

    /**
     * 消息状态 0:待发送 1:已提交 2:已回滚 3:发送中 4:发送成功 5:发送失败 6:死信消息
     */
    private Integer status;

    /**
     * 重试次数
     */
    private Integer retryCount;

    /**
     * 最大重试次数
     */
    private Integer maxRetryCount;

    /**
     * RocketMQ事务ID
     */
    private String transactionId;

    /**
     * 记录错误信息（原因），便于排查问题
     */
    private String errorMsg;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 下次重试时间
     */
    private LocalDateTime nextRetryTime;
}