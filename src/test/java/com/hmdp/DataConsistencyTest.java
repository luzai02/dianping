package com.hmdp;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.constant.RedisConstant;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisData;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
@Slf4j
public class DataConsistencyTest {

    @Autowired
    private IShopService shopService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private Cache<String, Object> caffeineCache;

/*    @Test
    public void testDataConsistency() {
        try {
            // 1. 选择已存在的店铺进行测试
            Long testShopId = 1L;

            // 2. 确保测试数据存在
            Shop existingShop = shopService.getById(testShopId);
            if (existingShop == null) {
                log.warn("店铺ID {} 不存在，尝试查询其他店铺", testShopId);
                // 查找任意一个存在的店铺
                for (long i = 1; i <= 10; i++) {
                    Shop shop = shopService.getById(i);
                    if (shop != null) {
                        testShopId = i;
                        existingShop = shop;
                        log.info("使用店铺ID {} 进行测试", testShopId);
                        break;
                    }
                }

                if (existingShop == null) {
                    log.error("未找到可用的测试数据，请检查数据库");
                    Assertions.fail("测试数据不存在");
                    return;
                }
            }

            // 清理一级缓存，确保从二级/DB开始
            caffeineCache.invalidate(RedisConstant.CACHE_SHOP_KEY + testShopId);
            log.info("已清除一级缓存：{}", RedisConstant.CACHE_SHOP_KEY + testShopId);
*//*            // 3. 清除二级缓存，确保数据来源于数据库
            String cacheKey = RedisConstant.CACHE_SHOP_KEY + testShopId;
            stringRedisTemplate.delete(cacheKey);
            log.info("已清除缓存：{}", cacheKey);

            // 4. 首次查询，将数据加载到缓存
            Shop shopFromCache = shopService.queryById(testShopId);
            if (shopFromCache == null) {
                Assertions.fail("查询店铺失败");
                return;
            }

            log.info("首次查询成功，店铺名称: {}", shopFromCache.getName());*//*

            // 5. 更新数据库数据
            String originalName = existingShop.getName();
            String newName = "第14次测试——" + System.currentTimeMillis();
            existingShop.setName(newName);

            long updateStartTime = System.currentTimeMillis();
            boolean updateResult = shopService.updateById(existingShop);
            log.info("数据库更新结果: {}, 更新时间: {}ms", updateResult, updateStartTime);

            if (!updateResult) {
                Assertions.fail("数据库更新失败");
                return;
            }

*//*            // ---- 模拟 Canal 将变更写回 Redis（用于本地测试，真实环境可移除） ----
            try {
                shopService.saveShop2Redis(testShopId, 20L); // 模拟 Canal 同步 Redis
                log.info("已模拟 Canal：已将更新写回 Redis");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("模拟 Canal 同步被中断");
            }
            // --------------------------------------------------------------------*//*


            // 6. 轮询检查缓存是否更新（Canal异步同步）
            int maxAttempts = 1000; // 最多检查10秒
            int attempt = 0;
            long syncTime = 0;
            boolean synced = false;

            while (attempt < maxAttempts && !synced) {
                try {
                    Thread.sleep(100); // 每100ms检查一次

                    // 从缓存查询
                    Shop cachedShop = shopService.queryById(testShopId);
                    Shop caffeineShop = (Shop) caffeineCache.getIfPresent(RedisConstant.CACHE_SHOP_KEY + testShopId);


                    if (cachedShop != null && caffeineShop != null && (newName.equals(cachedShop.getName()) || newName.equals(caffeineShop.getName()))) {
                        syncTime = System.currentTimeMillis();
                        synced = true;
                        break;
                    }

                    attempt++;
                    if (attempt % 10 == 0) { // 每1秒输出一次日志
                        log.debug("第{}次检查，缓存中的店铺名称: {}", attempt,
                                cachedShop != null ? cachedShop.getName() : "null");
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // 7. 计算并输出结果
            if (synced) {
                long delay = syncTime - updateStartTime;
                log.info("✅ 数据同步成功！延迟时间: {}ms", delay);
                log.info("📊 检查次数: {}", attempt + 1);

                // 输出测试结果用于简历
                System.out.println("=================================");
                System.out.println("📈 数据一致性测试结果:");
                System.out.println("⏱️  延迟时间: " + delay + "ms");
                System.out.println("🔄 检查次数: " + (attempt + 1));
                System.out.println("✅ 测试结论: 数据一致性延迟 < " + delay + "ms");
                System.out.println("=================================");

                // 断言延迟在合理范围内
                //  Assertions.assertTrue(delay < 2000, "数据同步延迟应该小于2秒，实际: " + delay + "ms");
            } else {
                log.error("❌ 数据同步失败！超过最大等待时间");
                System.out.println("=================================");
                System.out.println("❌ 数据一致性测试失败:");
                System.out.println("⏱️  超时时间: " + (maxAttempts * 100) + "ms");
                System.out.println("🔄 检查次数: " + attempt);
                System.out.println("=================================");
                // Assertions.fail("数据同步超时");
            }

//            // 8. 恢复原始数据
//            existingShop.setName(originalName);
//            shopService.updateById(existingShop);
//            log.info("🔄 已恢复原始数据");

        } catch (Exception e) {
            log.error("❌ 测试过程中发生异常", e);
            System.out.println("=================================");
            System.out.println("❌ 数据一致性测试异常:");
            System.out.println("错误信息: " + e.getMessage());
            System.out.println("=================================");
            Assertions.fail("测试异常: " + e.getMessage());
        }
    }*/


    @Test
    public void testRealCanalLatency() {
        try {
            Long testShopId = 1L;
            Shop existingShop = shopService.getById(testShopId);
            String originalName = existingShop.getName();
            String newName = "延迟测试3-" + System.currentTimeMillis();

            // 清理缓存确保数据来源
            caffeineCache.invalidate(RedisConstant.CACHE_SHOP_KEY + testShopId);
            stringRedisTemplate.delete(RedisConstant.CACHE_SHOP_KEY + testShopId);

            // 记录更新开始时间
            long updateStartTime = System.nanoTime();

            // 更新数据库
            existingShop.setName(newName);
            shopService.updateById(existingShop);

            // 等待Canal真实同步（不要模拟）
            boolean synced = false;
            long syncTime = 0;
            int maxWait = 50; // 最多等待5秒

            for (int i = 0; i < maxWait; i++) {
                Thread.sleep(100);

                // 清除本地缓存，强制从Redis读取
                caffeineCache.invalidate(RedisConstant.CACHE_SHOP_KEY + testShopId);

                Shop cachedShop = shopService.queryById(testShopId);
                if (cachedShop != null && newName.equals(cachedShop.getName())) {
                    syncTime = System.nanoTime();
                    synced = true;
                    break;
                }
            }

            if (synced) {
                long latencyNs = syncTime - updateStartTime;
                long latencyMs = latencyNs / 1_000_000;

                System.out.println("=================================");
                System.out.println("🎯 真实Canal延迟测试结果:");
                System.out.println("⏱️  端到端延迟: " + latencyMs + "ms");
                // System.out.println("🔧 检查次数: " + (i + 1));
                System.out.println("✅ 测试结论: Canal数据一致性延迟 = " + latencyMs + "ms");
                System.out.println("=================================");

                // 恢复数据
                existingShop.setName(originalName);
                shopService.updateById(existingShop);

            } else {
                Assertions.fail("Canal同步超时，可能存在配置问题");
            }

        } catch (Exception e) {
            Assertions.fail("测试异常: " + e.getMessage());
        }
    }
}
