package com.hmdp.service.impl;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.constant.RedisConstant;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import io.lettuce.core.RedisClient;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.RedissonBloomFilter;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private CacheClient cacheClient;

    // 为避免线程频繁创建，销毁消耗性能，使用线程池
    // 线程池会创建一个固定数量的线程池，线程池中的线程会重复使用，不会被销毁
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /*Spring的依赖注入是通过反射机制在运行时完成的
    如果字段被声明为final，就无法在运行时通过反射修改其值（因为final字段必须在构造时完成初始化）
    这是Java语言规范的限制，不是Spring特有的
     */
    @Resource
    private StringRedisTemplate stringRedisTemplate;  // 注入的类不能使用final

    @Resource
    private RedissonClient redissonClient;  // 引入Redisson，方便使用布隆过滤器

    @Resource
    private ShopMapper shopMapper;
    @Resource
    private Cache<String, Object> caffeineCache;

    private static final String BLOOMFILTER_KEY = "shop:bloom:shop_id";
    private static final long EXPECTED_ELEMENTS = 100000;
    // 误判率 1%
    private static final double FPP = 0.01;

    // 创建布隆过滤器对象
    private RBloomFilter<Long> bloomFilter;

    // 创建布隆过滤器
    // todo 这里最好是离线生成，序列化后在线加载，否则启动会很慢
    @PostConstruct
    public void initBloomFilter() {
        // 布隆过滤器名字
        bloomFilter = redissonClient.getBloomFilter(BLOOMFILTER_KEY);
        bloomFilter.tryInit(EXPECTED_ELEMENTS, FPP);
        // 加载数据库中所有的 数据
        loadExitingShopIds();
    }

    private void loadExitingShopIds() {
        // 分页查询，避免数据库压力过大
        int pageSize = 1000;
        int pageNum = 1;
        while(true){
            // 就是一个分页查询，获取当前页的起始索引
            int offset = (pageNum - 1) * pageSize;
            List<Long> ids = shopMapper.selectAllShopIds(pageSize, offset);
            if(ids.isEmpty()){
                break;
            }
            for(Long id : ids){
//                System.out.println( id);
                bloomFilter.add(id);
            }
            pageNum++;  // 下一页
        }
        log.info("布隆过滤器初始化完成，加载店铺ID总数: {}", (pageNum - 1) * pageSize);
    }

/*    // 从缓存中查找商铺
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
                this::getById,  // 可以写成 this::getById  todo:为什么这样写，还能怎么写
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
    }*/

/*    @Override
    public Result queryById(Long id) {
        // 先查找布隆过滤器
        if(!bloomFilter.contains(id)){
            return Result.fail("店铺不存在");
        }

        String key = RedisConstant.CACHE_SHOP_KEY + id;
        // 1、从Redis中查询店铺数据
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        Shop shop = null;
        // 2、判断缓存是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            // 2.1 缓存命中，直接返回店铺数据
            shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        // 2.2 缓存未命中，从数据库中查询店铺数据
        shop = this.getById(id);

        // 4、判断数据库是否存在店铺数据
        if (Objects.isNull(shop)) {
            // 4.1 数据库中不存在，返回失败信息
            return Result.fail("店铺不存在");
        }
        // 4.2 数据库中存在，写入Redis，并返回店铺数据
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstant.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(shop);
    }*/

    public Shop queryById(Long id) throws InterruptedException {
        if(!bloomFilter.contains(id)){
            log.info("布隆过滤器拦截，id不存在: {}", id);
            return null;
        }

        // 使用Caffeine作为一级缓存
        Object o = caffeineCache.getIfPresent(RedisConstant.CACHE_SHOP_KEY+id);
        if(Objects.nonNull(o)){
            log.info("一级缓存命中");
            return (Shop) o;
        }

        // Shop shop = queryWithMutex(id);
        Shop shop = cacheClient.queryWithLoginExpired(
                RedisConstant.CACHE_SHOP_KEY,
                id,
                RedisConstant.CACHE_SHOP_TTL,
                TimeUnit.MINUTES,
                this::getById, // 可以写成 this::getById
                Shop.class
        );
        if(shop != null){
            log.info("二级缓存命中");
            // 将当前缓存放入一级缓存
            caffeineCache.put(RedisConstant.CACHE_SHOP_KEY+id, shop);
        }else{
            log.info("缓存未命中，店铺不存在");
            return null;
        }
        return shop;
    }



    // 单独抽离封装成函数
    // 缓存穿透：redis中和数据库中都不存在数据，可以使用  设空值  或  布隆过滤器
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
        if(!bloomFilter.contains(id)){
            return null;
        }

        String key = RedisConstant.CACHE_SHOP_KEY+id;
        // 从缓存中查找
        String shopJson = stringRedisTemplate.opsForValue().get(key); // redis中存储的数据是json字符串

        // 如果存在
        if(StrUtil.isNotBlank(shopJson)){   // 只有是有效（非空非空白）字符 时才为true
            // 缓存中存在，返回
            return JSONUtil.toBean(shopJson, Shop.class); // json字符串转对象
        }

        // 互斥锁
        Shop shop = null;
        String lockKey = RedisConstant.LOCK_SHOP_KEY+id;
        try {
            // 这要不要使用递归，可能会栈溢出
            while(true){
                boolean isLock = trylock(lockKey);
                if(isLock){
                    break;  // 获取锁成功，跳出循环
                }
                Thread.sleep(50);
                // 再次检验，避免  创建锁过程中
                shopJson = doubleCheck(key);
                if(StrUtil.isNotBlank(shopJson)){
                    return JSONUtil.toBean(shopJson, Shop.class);
                }
            }


            // doubleCheck 再次获取缓存，防止  创建锁过程中（查询的时候不存在  已经有进程重建了缓存，导致缓存重建，浪费时间
            shopJson = doubleCheck(key);
            // 如果已经重建好了缓存
            if(StrUtil.isNotBlank(shopJson)){
                // 直接删除锁
                unlock(lockKey);
                return JSONUtil.toBean(shopJson, Shop.class);
            }

            // 查询数据库
            shop = getById(id);
            // 如果不存在，向redis中写入空值，解决缓存穿透问题  // 这里包含了缓存穿透的解决
            if(shop == null){
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
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            unlock(lockKey);
        }
        return shop;
    }

    public String doubleCheck(String key){
        // 前面已经使用过布隆过滤器，这里只需要检查缓存是否更新
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            return shopJson;
        }
        // 缓存未命中，即还没更新
        return null;
    }


    // 逻辑过期（不存在没有的数据）
    public Shop queryWithLoginExpire(Long id) throws InterruptedException {
        if(!bloomFilter.contains(id)){
            return null;
        }

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

        // 已过期
        String lockKey = RedisConstant.LOCK_SHOP_KEY+id;
        // 尝试获取锁
        boolean isLock = trylock(lockKey);
        if(isLock){
            // 获取锁成功，启动新的线程重建缓存，当前线程直接返回旧数据
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try{
                    this.saveShop2Redis(id, 20L);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    // 最后一定要释放锁
                    unlock(lockKey);
                }
            });
        }
        // 不需要双重检验了
        // 在“互斥锁 + 同步重建”方案中，请求线程会查库重建，代价大；
        // 为避免已被其他线程重建还重复查库，需要“加锁后再读一次缓存”的双检。这里不存在该问题。

       return shop;
    }

    // 先更新数据库再删除redis缓存
    @Override
    public boolean updateShop(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            log.info("店铺id不能为空");
            return false;
        }
        // 根据id来更新数据库
        updateById(shop);
        // 已经通过canal监听实现
//        // 删除缓存
//        stringRedisTemplate.delete(RedisConstant.CACHE_SHOP_KEY+id);
        return true;
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

    // 将商铺信息写入redis
    @Override
    public void saveShop2Redis(long id, long expiredSeconds) throws InterruptedException {
        // 获取商铺信息
        Shop shop = getById(id);
        // Thread.sleep(100);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        // 设置过期时间，用plusSeconds
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expiredSeconds));
        // 存入redis中
        stringRedisTemplate.opsForValue().set(RedisConstant.CACHE_SHOP_KEY+id, JSONUtil.toJsonStr(redisData));
    }
}
