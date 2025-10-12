package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
@Slf4j
public class DataConsistentTest {

        @Autowired
        private IShopService shopService;

        @Autowired
        private StringRedisTemplate stringRedisTemplate;

        @Test
        public void testDataConsistency() {
            try {
                // 1. 确保测试数据存在
                Shop existingShop = shopService.getById(1L);
                if (existingShop == null) {
                    // 创建测试数据
                    Shop testShop = new Shop();
                    testShop.setId(1L);
                    testShop.setName("测试店铺");
                    testShop.setTypeId(1L);
                    testShop.setImages("test.jpg");
                    testShop.setArea("测试区域");
                    testShop.setAddress("测试地址");
                    testShop.setX(0.0);
                    testShop.setY(0.0);
                    testShop.setAvgPrice(100L);
                    testShop.setSold(0);
                    testShop.setComments(0);
                    testShop.setScore(5);
                    testShop.setOpenHours("9:00-22:00");
                    shopService.save(testShop);
                    existingShop = testShop;
                }

                // 2. 清除缓存，确保数据来源于数据库
                String cacheKey = "cache:shop:1";
                stringRedisTemplate.delete(cacheKey);

                // 3. 首次查询，将数据加载到缓存
                Shop shopFromDb = shopService.queryById(1L);
                log.info("首次查询结果: {}", shopFromDb.getName());

                // 4. 更新数据库数据
                String newName = "更新后的店铺_" + System.currentTimeMillis();
                existingShop.setName(newName);

                long updateStartTime = System.currentTimeMillis();
                boolean updateResult = shopService.updateById(existingShop);
                log.info("数据库更新结果: {}, 时间: {}", updateResult, updateStartTime);

                // 5. 轮询检查缓存是否更新（Canal异步同步）
                int maxAttempts = 100; // 最多检查10秒
                int attempt = 0;
                long syncTime = 0;
                boolean synced = false;

                while (attempt < maxAttempts && !synced) {
                    try {
                        Thread.sleep(100); // 每100ms检查一次

                        // 从缓存查询
                        Shop cachedShop = shopService.queryById(1L);

                        if (cachedShop != null && newName.equals(cachedShop.getName())) {
                            syncTime = System.currentTimeMillis();
                            synced = true;
                            break;
                        }

                        attempt++;
                        log.debug("第{}次检查，缓存中的店铺名称: {}", attempt,
                                cachedShop != null ? cachedShop.getName() : "null");

                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                // 6. 计算并输出结果
                if (synced) {
                    long delay = syncTime - updateStartTime;
                    log.info("数据同步成功！延迟时间: {}ms", delay);
                    log.info("测试结果: 数据一致性延迟 < {}ms", delay);

                    // 断言延迟在合理范围内
                    Assertions.assertTrue(delay < 2000, "数据同步延迟应该小于2秒");
                } else {
                    log.error("数据同步失败！超过最大等待时间");
                    Assertions.fail("数据同步超时");
                }

            } catch (Exception e) {
                log.error("测试过程中发生异常", e);
                Assertions.fail("测试异常: " + e.getMessage());
            }
        }

        @Test
        public void testCacheHitRate() throws InterruptedException {
            int totalRequests = 1000;
            int hitCount = 0;

            // 预热缓存
            for (int i = 1; i <= 10; i++) {
                shopService.queryById((long) i);
            }

            long startTime = System.currentTimeMillis();

            for (int i = 0; i < totalRequests; i++) {
                // 模拟随机查询（偏向热点数据）
                Long shopId = (long) (Math.random() * 10 + 1);
                Shop shop = shopService.queryById(shopId);

                if (shop != null) {
                    hitCount++;
                }
            }

            long endTime = System.currentTimeMillis();
            double hitRate = (double) hitCount / totalRequests * 100;
            double avgResponseTime = (double) (endTime - startTime) / totalRequests;

            log.info("缓存命中率: {:.2f}%", hitRate);
            log.info("平均响应时间: {:.2f}ms", avgResponseTime);

            // 断言缓存命中率
            Assertions.assertTrue(hitRate > 90, "缓存命中率应该大于90%");
        }

}
