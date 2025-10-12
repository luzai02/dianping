package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.OrderMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * 订单消息Mapper
 */
@Mapper
public interface OrderMessageMapper extends BaseMapper<OrderMessage> {
}