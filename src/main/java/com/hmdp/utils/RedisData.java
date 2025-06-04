package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

// todo 这里可以继承父类属性
@Data
public class RedisData {
    private LocalDateTime expireTime;
    // 这里用Object是因为不知道是什么数据，提高代码复用性
    private Object data;
}
