package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.constant.RedisConstant;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import io.lettuce.core.RedisClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private CacheClient cacheClient;

    // todo: 学习线程池
    // 为避免线程频繁创建，销毁消耗性能，使用线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /*Spring的依赖注入是通过反射机制在运行时完成的
    如果字段被声明为final，就无法在运行时通过反射修改其值（因为final字段必须在构造时完成初始化）
    这是Java语言规范的限制，不是Spring特有的
     */
    @Resource
    private StringRedisTemplate stringRedisTemplate;  // 注入的类不能使用final


    // 从缓存中查找商铺
    @Override
    public Result queryById(Long id) throws InterruptedException {
        // 缓存穿透
        // Shop shop = queryWithPassThrough(id);

        // 缓存击穿-互斥锁
        // Shop shop = queryWithMutex(id);

        // 缓存击穿-逻辑过期
        // Shop shop = queryWithLoginExpire(id);

        Shop shop = cacheClient.queryWithLoginExpired(
                RedisConstant.CACHE_SHOP_KEY,
                id,
                RedisConstant.CACHE_SHOP_TTL,
                TimeUnit.MINUTES,
                this::getById,  // 可以写成 this::getById
                Shop.class
                );

//        Shop shop = cacheClient.queryWithNullPassThrough(
//                id,
//                RedisConstant.CACHE_SHOP_KEY,
//                RedisConstant.CACHE_SHOP_TTL,
//                TimeUnit.MINUTES,
//                Shop.class,
//                this::getById // 可以写成 this::getById
//        );

        if(shop == null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    // 单独抽离封装成函数
    // 缓存穿透：redis中和数据库中都不存在数据，可以使用设空值或布隆过滤器
    public Shop queryWithPassThrough(Long id) {
        String key = RedisConstant.CACHE_SHOP_KEY+id;
        // 从缓存中查找
        String shopJson = stringRedisTemplate.opsForValue().get(key); // redis中存储的数据是json字符串

        // 如果存在
        if(StrUtil.isNotBlank(shopJson)){   // 只有是有效（非空非空白）字符 时才为true
            // 缓存中存在，返回
            return JSONUtil.toBean(shopJson, Shop.class); // json字符串转对象
        }
        // 如果不存在
        if(shopJson != null){   // 也就是为 “” 时，如果为null，可能是第一次查询而redis还没有缓存数据
            return null;
        }

        // 查询数据库
        Shop shop = getById(id);
        // 如果不存在，向redis中写入空值，解决缓存穿透问题
        if(shop == null){
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
                JSONUtil.toJsonStr(shop),
                RedisConstant.CACHE_SHOP_TTL,
                TimeUnit.MINUTES
        );
        return shop;
    }

    // 缓存击穿：在高并发下缓存重建业务发杂的key突然失效，可以使用互斥锁或逻辑过期方法
    // 互斥锁：采用tryLock方法 + double check来解决这样的问题
    public Shop queryWithMutex(Long id) throws InterruptedException {
        String key = RedisConstant.CACHE_SHOP_KEY+id;
        // 从缓存中查找
        String shopJson = stringRedisTemplate.opsForValue().get(key); // redis中存储的数据是json字符串

        // 如果存在
        if(StrUtil.isNotBlank(shopJson)){   // 只有是有效（非空非空白）字符 时才为true
            // 缓存中存在，返回
            return JSONUtil.toBean(shopJson, Shop.class); // json字符串转对象
        }
        // 如果不存在
        if(shopJson != null){   // 也就是为 “” 时，如果为null，可能是第一次查询而redis还没有缓存数据
            return null;
        }
        // 互斥锁
        Shop shop = null;
        String lockKey = RedisConstant.LOCK_SHOP_KEY+id;
        try {
            boolean isLock = trylock(lockKey);
            // todo 这要不要使用递归
            if(!isLock){
                // 获取失败，休眠一段时间，重新查询（递归）
                Thread.sleep(50);
                queryById(id);
            }
            // doubleCheck 再次获取缓存，防止  创建锁过程中（查询的时候不存在  已经有进程重建了缓存，导致缓存重建，浪费时间
            shopJson = stringRedisTemplate.opsForValue().get(key);
            // 如果已经重建好了缓存
            if(StrUtil.isNotBlank(shopJson)){
                // 直接删除锁
                unlock(lockKey);
                return JSONUtil.toBean(shopJson, Shop.class);
            }

            // 查询数据库
            shop = getById(id);
            // 如果不存在，向redis中写入空值，解决缓存穿透问题
            if(shop == null){
                stringRedisTemplate.opsForValue().set(key, "", RedisConstant.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            // 这里包含了缓存穿透的解决
            // 写入缓存，将shop对象转为json字符串
            // 设置过期时间
            stringRedisTemplate.opsForValue().set(
                    key,
                    JSONUtil.toJsonStr(shop),
                    RedisConstant.CACHE_SHOP_TTL,
                    TimeUnit.MINUTES
            );
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            unlock(lockKey);
        }
        return shop;
    }


    // 逻辑过期（不存在没有的数据）
    public Shop queryWithLoginExpire(Long id) throws InterruptedException {
        String key = RedisConstant.CACHE_SHOP_KEY+id;
        // 从缓存中查找
        String shopJson = stringRedisTemplate.opsForValue().get(key); // redis中存储的数据是json字符串

        // 如果为空，直接返回null
        // 理论上是不会存在未命中的情况的，因为redis的数据是一直存在的，只是有过期时间，这里是为了代码健壮性
        if(StrUtil.isBlank(shopJson)){
            return null;
        }

        // 将当前的json字符串转对象
        /*
        redisData.getData()返回的是Object类型，实际存储的是JSONObject结构（通过(JSONObject)强制转换可以看出）。
        直接强制转换为Shop会导致ClassCastException，因为JSONObject和Shop没有继承关系。
        JSONUtil.toBean()方法需要明确的目标类型参数才能正确反序列化。
         */
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop  = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);

        // 判断是否过期
        LocalDateTime expireTime = redisData.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())){
            // 未过期，直接返回
            return shop;
        }

        String lockKey = RedisConstant.LOCK_SHOP_KEY+id;
        // 利用互斥锁，开启新线程
        try {
            boolean isLock = trylock(lockKey);
            // doubleCheck  todo : 这里的双重检验很有讲究
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if(StrUtil.isNotBlank(shopJson)){
                // todo: 这里需要获取新的对象
                redisData = JSONUtil.toBean(shopJson, RedisData.class);  // 先将Json转为RedisData对象
                LocalDateTime latestExpireTime = redisData.getExpireTime();
                if(latestExpireTime.isAfter(LocalDateTime.now())){
                    // todo: 存在锁释放不安全问题，后面要用唯一id标识+lua脚本
                    unlock(lockKey);
                    return JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
                }
                // return JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);  // 再转为Shop对象
            }
            if(isLock){
                // 获取锁成功，开启独立线程，实现缓存重建
                CACHE_REBUILD_EXECUTOR.submit(() -> {
                    try {
                        saveShop2Redis(id, 20L);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }finally {
            unlock(lockKey);
        }
        return shop;
    }

    // 先更新数据库再删除redis缓存
    @Override
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺id不能为空");
        }
        // 根据id来更新数据库
        updateById(shop);
        // 删除缓存
        stringRedisTemplate.delete(RedisConstant.CACHE_SHOP_KEY+id);
        return Result.ok();
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

    @Override
    public void saveShop2Redis(long id, long expiredSeconds) throws InterruptedException {
        // 获取商铺信息
        Shop shop = getById(id);
        Thread.sleep(100);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        // 设置过期时间，用plusSeconds
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expiredSeconds));
        // 存入redis中
        stringRedisTemplate.opsForValue().set(RedisConstant.CACHE_SHOP_KEY+id, JSONUtil.toJsonStr(redisData));
    }
}
