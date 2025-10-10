package com.hmdp.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.constant.RedisConstant;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

// 将redis的操作进行封装
// 实现InitializingBean接口的作用是：
// 在Spring容器初始化完成后，会自动调用afterPropertiesSet()方法
@Component
public class ShopRedisHandle implements InitializingBean {
    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private Cache<String, Object> caffeineCache;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // 实现缓存预热
    @Override
    public void afterPropertiesSet() throws Exception {
        List<Shop> shopList = shopService.list();
        for(Shop shop : shopList){
            // 将数据序列化为JSON
            String shopJson = objectMapper.writeValueAsString(shop);
            // 存入Redis
            stringRedisTemplate.opsForValue().set(RedisConstant.CACHE_SHOP_KEY+shop.getId(), shopJson);
            // 存入Caffeine缓存
            caffeineCache.put(RedisConstant.CACHE_SHOP_KEY+shop.getId(), shop);
        }
    }

    public void saveShop(Shop shop){
        try{
            String json = objectMapper.writeValueAsString(shop);
            stringRedisTemplate.opsForValue().set(RedisConstant.CACHE_SHOP_KEY+shop.getId(), json);
            caffeineCache.put(RedisConstant.CACHE_SHOP_KEY+shop.getId(), shop);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public void deleteShop(Long id){
        stringRedisTemplate.delete(RedisConstant.CACHE_SHOP_KEY+id);
    }
}
