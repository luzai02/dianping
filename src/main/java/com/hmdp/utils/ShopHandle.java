package com.hmdp.utils;

import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.constant.RedisConstant;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import top.javatool.canal.client.annotation.CanalTable;
import top.javatool.canal.client.handler.EntryHandler;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

@Component
@Slf4j
@CanalTable(value = "tb_shop") // 监听的表名
public class ShopHandle implements EntryHandler<Shop>{
    @Resource
    private Cache<String, Object> cache;
    @Resource
    private ShopRedisHandle shopRedisHandle;

    @PostConstruct
    public void init() {
        log.info("ShopHandle 初始化成功");
    }

    @Override
    public void insert(Shop shop) {
        try {
            String key = RedisConstant.CACHE_SHOP_KEY + shop.getId();
            shopRedisHandle.saveShop(shop);
            cache.put(key, shop);
            log.info("Canal监听到插入操作，更新缓存成功：{}", key);
        } catch (Exception e) {
            log.error("Canal处理插入操作失败", e);
        }
    }

        @Override
    public void update(Shop before, Shop after) {
            try {
                String key = RedisConstant.CACHE_SHOP_KEY + after.getId();
                shopRedisHandle.saveShop(after);
                cache.put(key, after);
                log.info("Canal监听到更新操作，更新缓存成功：{}", key);
            } catch (Exception e) {
                log.error("Canal处理更新操作失败", e);
            }
    }

    @Override
    public void delete(Shop shop) {
        try {
            String key = RedisConstant.CACHE_SHOP_KEY + shop.getId();
            shopRedisHandle.deleteShop(shop.getId());
            cache.invalidate(key);
            log.info("Canal监听到删除操作，删除缓存成功：{}", key);
        } catch (Exception e) {
            log.error("Canal处理删除操作失败", e);
        }
    }
}
