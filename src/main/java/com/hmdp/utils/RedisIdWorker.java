package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    private static final long BEGIN_TIMESTAMP = 1735689600L;  //2025.1.1

    private final StringRedisTemplate  stringRedisTemplate;
    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // 设置全局唯一的自增id
    // 64位，第一位用来作符号位，中间31位用来做时间戳（增加复杂性），最后32位用来做序列号
    /*
    原子性：Redis单线程模型保证INCR操作的线程安全   ，incr 操作通常指 原子性自增操作，常见于键值存储系统（如 Redis）或并发编程场景
    分布式唯一性：时间戳+业务前缀+日期维度共同保障
    空间效率：使用数值存储比字符串更节省内存
     */
    public long nextId(String key) {

        // 获取当前时间
        LocalDateTime now = LocalDateTime.now();
        // 将其转换为UTC时区对应的UNIX时间戳（1970年以来的秒数）(UTC是零时区（基准时区）)
        // todo 要保证时区一致，所有否则获取的时间戳会因为时区不同而产生偏差
            /*这段代码的功能是获取当前时间的Unix时间戳（秒级），使用UTC时区作为基准。具体解释如下：
            now.toEpochSecond(ZoneOffset.UTC) 将LocalDateTime对象转换为自1970-01-01 00:00:00 UTC（零时区）起计算的秒数
            使用ZoneOffset.UTC确保时区统一，避免因服务器时区差异导致时间戳偏差
            计算结果nowSeconds是当前时间与UTC基准时刻的时间差值（单位：秒）
            例如：若当前UTC时间为2025-01-01 00:00:00，则返回1735689600（即2025年元旦的Unix时间戳）*/
        long nowSeconds = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSeconds - BEGIN_TIMESTAMP;

        // 生成序列号，自增长存入Redis
        // 用日期来设置key，超id序列号（32bit）
        // 这里存储数值类型，占用空间少，提高数据库性能
        String data = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 默认每次  自增1
        long count = stringRedisTemplate.opsForValue().increment("icr:" + key + ":" + data);   // 序列号

        return timestamp << 32 | count;  // 用 位移  和  或  来拼接
    }

//    public static void main(String [] args){
//        // 创建表示2025年1月1日0点的LocalDateTime对象
//        // 将其转换为UTC时区对应的UNIX时间戳（1970年以来的秒数）
//        LocalDateTime time = LocalDateTime.of(2025, 1, 1, 0, 0, 0);
//        Long timestamp = time.toEpochSecond(ZoneOffset.UTC);  //
//        System.out.println(timestamp);
//    }
}
