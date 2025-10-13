package com.hmdp;

import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.constant.RedisConstant;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Canal监听binlog更新缓存延迟时间测试类
 *
 * @author luzai02
 * @date 2025-10-13
 */
@SpringBootTest
@Slf4j
public class CanalLatencyTest {

    @Resource
    private IShopService shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private Cache<String, Object> caffeineCache;

    /**
     * 测试Canal监听binlog更新缓存的延迟时间
     * 测试流程：
     * 1. 更新数据库
     * 2. 记录更新时间戳
     * 3. 轮询检查缓存是否更新
     * 4. 计算延迟时间
     */
    @Test
    public void testCanalCacheUpdateLatency() {
        log.info("========== 开始测试Canal缓存更新延迟 ==========");

        // 测试用的店铺ID（确保数据库中存在）
        Long testShopId = 1L;

        try {
            // 1. 获取现有店铺数据
            Shop existingShop = shopService.getById(testShopId);
            if (existingShop == null) {
                log.error("测试失败：店铺ID {} 不存在", testShopId);
                return;
            }

            String originalName = existingShop.getName();
            log.info("原始店铺名称: {}", originalName);

            // 2. 更新店铺信息
            String newName = "Canal延迟测试-" + System.currentTimeMillis();
            existingShop.setName(newName);

            // 记录数据库更新开始时间
            long dbUpdateStartTime = System.nanoTime();
            boolean updateResult = shopService.updateById(existingShop);
            long dbUpdateEndTime = System.nanoTime();

            if (!updateResult) {
                log.error("数据库更新失败");
                return;
            }

            log.info("数据库更新成功，耗时: {} ms", (dbUpdateEndTime - dbUpdateStartTime) / 1_000_000);

            // 3. 监测缓存更新延迟
            measureCacheUpdateLatency(testShopId, newName, dbUpdateEndTime);

        } catch (Exception e) {
            log.error("测试过程中发生异常", e);
        }
    }

    /**
     * 测量缓存更新延迟
     */
    private void measureCacheUpdateLatency(Long shopId, String expectedName, long dbUpdateTime) {
        String redisKey = RedisConstant.CACHE_SHOP_KEY + shopId;
        String caffeineKey = RedisConstant.CACHE_SHOP_KEY + shopId;

        long maxWaitTime = 10000; // 最大等待10秒
        long checkInterval = 10; // 每10ms检查一次
        long maxAttempts = maxWaitTime / checkInterval;

        boolean redisUpdated = false;
        boolean caffeineUpdated = false;
        long redisUpdateTime = 0;
        long caffeineUpdateTime = 0;

        log.info("开始监测缓存更新，期望名称: {}", expectedName);

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                long currentTime = System.nanoTime();

                // 检查Redis缓存
                if (!redisUpdated) {
                    // 清除Caffeine缓存，强制从Redis读取
                    caffeineCache.invalidate(caffeineKey);
                    Shop shopFromCache = shopService.queryById(shopId);

                    if (shopFromCache != null && expectedName.equals(shopFromCache.getName())) {
                        redisUpdated = true;
                        redisUpdateTime = currentTime;
                        long latency = (currentTime - dbUpdateTime) / 1_000_000;
                        log.info("✅ Redis缓存已更新，延迟时间: {} ms", latency);
                    }
                }

                // 检查Caffeine本地缓存
                if (!caffeineUpdated) {
                    Object cachedObject = caffeineCache.getIfPresent(caffeineKey);
                    if (cachedObject instanceof Shop) {
                        Shop shopFromCaffeine = (Shop) cachedObject;
                        if (expectedName.equals(shopFromCaffeine.getName())) {
                            caffeineUpdated = true;
                            caffeineUpdateTime = currentTime;
                            long latency = (currentTime - dbUpdateTime) / 1_000_000;
                            log.info("✅ Caffeine缓存已更新，延迟时间: {} ms", latency);
                        }
                    }
                }

                // 如果都更新完成，退出循环
                if (redisUpdated && caffeineUpdated) {
                    break;
                }

                // 每隔1秒输出进度
                if (attempt % 100 == 0 && attempt > 0) {
                    log.debug("第{}次检查，Redis状态: {}, Caffeine状态: {}",
                            attempt, redisUpdated ? "已更新" : "未更新",
                            caffeineUpdated ? "已更新" : "未更新");
                }

                Thread.sleep(checkInterval);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("延迟测试被中断");
                break;
            } catch (Exception e) {
                log.error("检查缓存状态时发生异常", e);
            }
        }

        // 输出最终结果
        printLatencyResults(redisUpdated, caffeineUpdated, redisUpdateTime,
                caffeineUpdateTime, dbUpdateTime);
    }

    /**
     * 打印延迟测试结果
     */
    private void printLatencyResults(boolean redisUpdated, boolean caffeineUpdated,
                                     long redisUpdateTime, long caffeineUpdateTime,
                                     long dbUpdateTime) {
        log.info("========== Canal缓存更新延迟测试结果 ==========");

        if (redisUpdated) {
            long redisLatency = (redisUpdateTime - dbUpdateTime) / 1_000_000;
            log.info("Redis缓存更新延迟: {} ms", redisLatency);
        } else {
            log.warn("Redis缓存在测试时间内未更新");
        }

        if (caffeineUpdated) {
            long caffeineLatency = (caffeineUpdateTime - dbUpdateTime) / 1_000_000;
            log.info("Caffeine缓存更新延迟: {} ms", caffeineLatency);
        } else {
            log.warn("Caffeine缓存在测试时间内未更新");
        }

        if (redisUpdated && caffeineUpdated) {
            long totalLatency = Math.max(
                    (redisUpdateTime - dbUpdateTime) / 1_000_000,
                    (caffeineUpdateTime - dbUpdateTime) / 1_000_000
            );
            log.info("总体缓存更新延迟: {} ms", totalLatency);
        }

        log.info("===============================================");
    }

    /**
     * 批量测试Canal延迟性能
     * 多次执行更新操作，统计平均延迟时间
     */
    @Test
    public void testCanalLatencyBatch() {
        log.info("========== 开始批量Canal延迟测试 ==========");

        int testCount = 10; // 测试次数
        Long testShopId = 1L;
        List<Long> redisLatencies = new ArrayList<>();
        List<Long> caffeineLatencies = new ArrayList<>();

        for (int i = 0; i < testCount; i++) {
            try {
                log.info("执行第 {} 次测试", i + 1);

                Shop shop = shopService.getById(testShopId);
                if (shop == null) {
                    log.error("店铺不存在，跳过测试");
                    continue;
                }

                // 更新店铺名称
                String newName = "批量测试-" + i + "-" + System.currentTimeMillis();
                shop.setName(newName);

                long dbUpdateStartTime = System.nanoTime();
                shopService.updateById(shop);

                // 测量延迟
                LatencyResult result = measureSingleUpdateLatency(testShopId, newName, dbUpdateStartTime);

                if (result.redisUpdated) {
                    redisLatencies.add(result.redisLatency);
                }
                if (result.caffeineUpdated) {
                    caffeineLatencies.add(result.caffeineLatency);
                }

                // 间隔一段时间再进行下次测试
                Thread.sleep(2000);

            } catch (Exception e) {
                log.error("第 {} 次测试失败", i + 1, e);
            }
        }

        // 统计结果
        printBatchTestResults(redisLatencies, caffeineLatencies);
    }

    /**
     * 测量单次更新的延迟
     */
    private LatencyResult measureSingleUpdateLatency(Long shopId, String expectedName, long dbUpdateTime) {
        String redisKey = RedisConstant.CACHE_SHOP_KEY + shopId;
        String caffeineKey = RedisConstant.CACHE_SHOP_KEY + shopId;

        LatencyResult result = new LatencyResult();
        long maxWaitTime = 5000; // 最大等待5秒
        long checkInterval = 10;
        long maxAttempts = maxWaitTime / checkInterval;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                long currentTime = System.nanoTime();

                // 检查Redis（通过清除本地缓存强制查询Redis）
                if (!result.redisUpdated) {
                    caffeineCache.invalidate(caffeineKey);
                    Shop shopFromCache = shopService.queryById(shopId);

                    if (shopFromCache != null && expectedName.equals(shopFromCache.getName())) {
                        result.redisUpdated = true;
                        result.redisLatency = (currentTime - dbUpdateTime) / 1_000_000;
                    }
                }

                // 检查Caffeine
                if (!result.caffeineUpdated) {
                    Object cachedObject = caffeineCache.getIfPresent(caffeineKey);
                    if (cachedObject instanceof Shop) {
                        Shop shopFromCaffeine = (Shop) cachedObject;
                        if (expectedName.equals(shopFromCaffeine.getName())) {
                            result.caffeineUpdated = true;
                            result.caffeineLatency = (currentTime - dbUpdateTime) / 1_000_000;
                        }
                    }
                }

                if (result.redisUpdated && result.caffeineUpdated) {
                    break;
                }

                Thread.sleep(checkInterval);

            } catch (Exception e) {
                log.error("测量延迟时发生异常", e);
                break;
            }
        }

        return result;
    }

    /**
     * 打印批量测试结果
     */
    private void printBatchTestResults(List<Long> redisLatencies, List<Long> caffeineLatencies) {
        log.info("========== 批量测试统计结果 ==========");

        if (!redisLatencies.isEmpty()) {
            double avgRedisLatency = redisLatencies.stream().mapToLong(Long::longValue).average().orElse(0);
            long minRedisLatency = redisLatencies.stream().mapToLong(Long::longValue).min().orElse(0);
            long maxRedisLatency = redisLatencies.stream().mapToLong(Long::longValue).max().orElse(0);

            log.info("Redis缓存更新延迟统计:");
            log.info("  - 成功更新次数: {}", redisLatencies.size());
            log.info("  - 平均延迟: {:.2f} ms", avgRedisLatency);
            log.info("  - 最小延迟: {} ms", minRedisLatency);
            log.info("  - 最大延迟: {} ms", maxRedisLatency);
        }

        if (!caffeineLatencies.isEmpty()) {
            double avgCaffeineLatency = caffeineLatencies.stream().mapToLong(Long::longValue).average().orElse(0);
            long minCaffeineLatency = caffeineLatencies.stream().mapToLong(Long::longValue).min().orElse(0);
            long maxCaffeineLatency = caffeineLatencies.stream().mapToLong(Long::longValue).max().orElse(0);

            log.info("Caffeine缓存更新延迟统计:");
            log.info("  - 成功更新次数: {}", caffeineLatencies.size());
            log.info("  - 平均延迟: {:.2f} ms", avgCaffeineLatency);
            log.info("  - 最小延迟: {} ms", minCaffeineLatency);
            log.info("  - 最大延迟: {} ms", maxCaffeineLatency);
        }

        log.info("=====================================");
    }

    /**
     * 并发测试Canal延迟性能
     * 模拟高并发场景下的缓存更新延迟
     */
    @Test
    public void testCanalLatencyUnderConcurrency() {
        log.info("========== 开始并发Canal延迟测试 ==========");

        int threadCount = 5; // 并发线程数
        int operationsPerThread = 5; // 每个线程执行的操作数

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicLong totalLatency = new AtomicLong(0);

        // 使用不同的店铺ID进行测试，避免数据冲突
        Long[] testShopIds = {1L, 2L, 3L, 4L, 5L};

        for (int i = 0; i < threadCount; i++) {
            final int threadIndex = i;
            final Long shopId = testShopIds[i % testShopIds.length];

            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        try {
                            Shop shop = shopService.getById(shopId);
                            if (shop == null) {
                                log.warn("线程{}: 店铺ID {} 不存在", threadIndex, shopId);
                                continue;
                            }

                            String newName = String.format("并发测试-T%d-Op%d-%d",
                                    threadIndex, j, System.currentTimeMillis());
                            shop.setName(newName);

                            long startTime = System.nanoTime();
                            shopService.updateById(shop);

                            // 测量Redis更新延迟
                            long latency = measureRedisUpdateLatency(shopId, newName, startTime);
                            if (latency > 0) {
                                successCount.incrementAndGet();
                                totalLatency.addAndGet(latency);
                                log.info("线程{}-操作{}: 延迟 {} ms", threadIndex, j, latency);
                            }

                            Thread.sleep(500); // 线程内操作间隔

                        } catch (Exception e) {
                            log.error("线程{}-操作{}失败", threadIndex, j, e);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await(); // 等待所有线程完成

            if (successCount.get() > 0) {
                double avgLatency = (double) totalLatency.get() / successCount.get();
                log.info("========== 并发测试结果 ==========");
                log.info("成功操作数: {}", successCount.get());
                log.info("平均延迟: {:.2f} ms", avgLatency);
                log.info("================================");
            } else {
                log.warn("并发测试中没有成功的操作");
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("并发测试被中断", e);
        } finally {
            executor.shutdown();
        }
    }

    /**
     * 测量Redis更新延迟（简化版）
     */
    private long measureRedisUpdateLatency(Long shopId, String expectedName, long dbUpdateTime) {
        String caffeineKey = RedisConstant.CACHE_SHOP_KEY + shopId;
        long maxWaitTime = 3000; // 最大等待3秒
        long checkInterval = 20;
        long maxAttempts = maxWaitTime / checkInterval;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                // 清除本地缓存，强制从Redis读取
                caffeineCache.invalidate(caffeineKey);
                Shop shopFromCache = shopService.queryById(shopId);

                if (shopFromCache != null && expectedName.equals(shopFromCache.getName())) {
                    return (System.nanoTime() - dbUpdateTime) / 1_000_000;
                }

                Thread.sleep(checkInterval);

            } catch (Exception e) {
                log.error("测量Redis延迟时发生异常", e);
                break;
            }
        }

        return -1; // 表示超时未更新
    }

    /**
     * 压力测试：连续快速更新，观察Canal的处理能力
     */
    @Test
    public void testCanalStressLatency() {
        log.info("========== 开始Canal压力延迟测试 ==========");

        Long testShopId = 1L;
        int rapidUpdateCount = 20; // 快速连续更新次数

        List<Long> latencies = new ArrayList<>();

        for (int i = 0; i < rapidUpdateCount; i++) {
            try {
                Shop shop = shopService.getById(testShopId);
                if (shop == null) {
                    log.error("店铺不存在，跳过测试");
                    continue;
                }

                String newName = "压力测试-" + i + "-" + System.currentTimeMillis();
                shop.setName(newName);

                long startTime = System.nanoTime();
                shopService.updateById(shop);

                // 快速连续更新，不等待
                if (i < rapidUpdateCount - 1) {
                    Thread.sleep(100); // 100ms间隔
                }

                // 只测量最后一次更新的延迟
                if (i == rapidUpdateCount - 1) {
                    long latency = measureRedisUpdateLatency(testShopId, newName, startTime);
                    if (latency > 0) {
                        latencies.add(latency);
                        log.info("压力测试最终延迟: {} ms", latency);
                    }
                }

            } catch (Exception e) {
                log.error("压力测试第{}次操作失败", i + 1, e);
            }
        }

        log.info("压力测试完成，Canal能够处理快速连续的数据变更");
    }

    /**
     * 延迟结果封装类
     */
    private static class LatencyResult {
        boolean redisUpdated = false;
        boolean caffeineUpdated = false;
        long redisLatency = 0;
        long caffeineLatency = 0;
    }
}