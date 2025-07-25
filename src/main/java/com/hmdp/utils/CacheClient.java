package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.constant.RedisConstant;
import com.hmdp.entity.Shop;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
public class CacheClient {
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    private final StringRedisTemplate stringRedisTemplate;

    // 构造注入，autowired可以省略(只有一个构造函数时)
    public  CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }


    // 将任意java对象序列化为json并存储到String类型的key中，并设置TTL过期时间
    public void set(String key, Object value, Long expireTime, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), expireTime, unit);  // 这里不需要转时间单位，因为是自由设置过期时间
    }

    // 逻辑过期
    public void setWithLoginExpired(String key, Object value, Long expireTime, TimeUnit unit){
        // 要封装为RedisData对象
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(expireTime))); // 这里不能确定是什么时间单位，所以需要转成秒
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData), expireTime, unit);
    }

    // 根据指定的key查询缓存，并且反序列化为指定类型，利用  缓存空值  的方式解决缓存穿透问题
    public <T, ID> T queryWithNullPassThrough(ID id, String keyPrefix, Long expireTime, TimeUnit unit,
                                            Class<T> type,
                                            Function<ID, T> dbfallback){
        String key = keyPrefix+id;
        // 从缓存中查找
        String Json = stringRedisTemplate.opsForValue().get(key); //   redis中存储的数据是json字符串

        // 如果存在
        if(StrUtil.isNotBlank(Json)){   // 只有是有效（非空非空白）字符 时才为true
            // 缓存中存在，返回
            return JSONUtil.toBean(Json, type); // json字符串转对象
        }
        // 如果不存在
        if(Json != null){   // 也就是为 “” 时（不是null），如果为null，可能是第一次查询而redis还没有缓存数据
            return null;
        }

        // 查询数据库
        // 因为不知道改查询那个类型的数据，也就不知道调用哪个方法
        // 所以让调用者传递逻辑进来
        // 函数式编程
        T t = dbfallback.apply(id);  // 接收类型ID的参数，返回T类型结果
        // 如果不存在，向redis中写入空值，解决缓存穿透问题
        if(t == null){
            // 存入空值，代表缓存中不存在该数据，防止缓存穿透
            // todo 用布隆过滤器会更好  !!!  要优化
            // todo 缓存雪崩
            stringRedisTemplate.opsForValue().set(key, "", RedisConstant.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 写入缓存，将shop对象转为json字符串
        // 设置过期时间
        stringRedisTemplate.opsForValue().set(
                key,
                JSONUtil.toJsonStr(t),
                expireTime,
                unit
        );
        return t;
    }


    // 根据指定的key查询缓存，并且反序列化为指定类型，利用  逻辑过期  的方式解决缓存击穿问题
    public <T, ID> T queryWithLoginExpired(String keyPrefix, ID id, Long expireTime, TimeUnit unit,
                                           Function<ID, T> dbfallback,
                                           Class<T> type){
        String key = keyPrefix + id;
        // 从缓存中查找
        String Json = stringRedisTemplate.opsForValue().get(key); // redis中存储的数据是json字符串

        // 如果为空，直接返回null
        // 理论上是不会存在未命中的情况的，因为redis的数据是一直存在的，只是有过期时间，这里是为了代码健壮性
        if(StrUtil.isBlank(Json)){
            return null;
        }

        // 将当前的json字符串转对象
        /*
        redisData.getData()返回的是Object类型，实际存储的是JSONObject结构（通过(JSONObject)强制转换可以看出）。
        直接强制转换为Shop会导致ClassCastException，因为JSONObject和Shop没有继承关系。
        JSONUtil.toBean()方法需要明确的目标类型参数才能正确反序列化。
         */
        RedisData redisData = JSONUtil.toBean(Json, RedisData.class);  // 先将Json转为RedisData对象
        T t  = JSONUtil.toBean((JSONObject) redisData.getData(), type);

        // 判断是否过期
        LocalDateTime time = redisData.getExpireTime();
        if(time.isAfter(LocalDateTime.now())){
            // 未过期，直接返回
            return t;
        }

        String lockKey = RedisConstant.LOCK_SHOP_KEY+id;
        // 利用互斥锁，开启新线程
        try {
            boolean isLock = trylock(lockKey);
            // doubleCheck
            Json = stringRedisTemplate.opsForValue().get(key);
            if(StrUtil.isNotBlank(Json)){
                redisData = JSONUtil.toBean(Json, RedisData.class);  // 先将Json转为RedisData对象
                LocalDateTime latestExpireTime = redisData.getExpireTime();
                if(latestExpireTime.isAfter(LocalDateTime.now())){
                    unlock(lockKey);
                    return JSONUtil.toBean((JSONObject) redisData.getData(), type);  // 再转为Shop对象
                }
            }
            if(isLock){
                // 获取锁成功，开启独立线程，实现缓存重建
                CACHE_REBUILD_EXECUTOR.submit(() -> {
                    try {
                        // 去数据库查询数据，上面未未查询数据库，这里要添加逻辑
                        // todo ：这里是新建对象还是对对象重新赋值？
                        T newT = dbfallback.apply(id);   // todo：这是什么操作？？？
                        this.setWithLoginExpired(key, newT, expireTime, unit);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }finally {
            unlock(lockKey);
        }
        return t;
    }

    private boolean trylock(String key){
        // 这里是是包装类Boolean
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        // 这里要返回基本数据类型，但是不能直接返回flag
        // 原因是flag是包装类Boolean，返回flag是会自动拆箱，可能会返回null
        /*
        当stringRedisTemplate.opsForValue().setIfAbsent()操作因网络问题、Redis服务不可用或操作超时等异常情况失败时，
        Spring会返回null而非false。此时若直接返回flag会触发自动拆箱，导致NullPointerException。
         */
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

}


