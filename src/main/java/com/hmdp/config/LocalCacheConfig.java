package com.hmdp.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/*
    * 本地缓存配置类
 */
@Configuration   // todo:这个注解有什么用
public class LocalCacheConfig {
    @Bean
    public Cache<String, Object> localCacheManager(){
        return Caffeine.newBuilder()
                // 写入或更新后60秒过期
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .initialCapacity(50)  // 初始容量，减少扩容次数
                .maximumSize(100)  // 最大容量
                // 开启数据收集功能后，Caffeine会收集并记录缓存的各种统计信息
                // 例如缓存命中次数、缓存未命中次数、缓存加载耗时、缓存被逐出次数等
                .recordStats()
                .build();
    }
}
