package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisIdWorker;
import io.lettuce.core.api.sync.RedisGeoCommands;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private IShopService shopService;
    @Resource
    private RedisIdWorker redisIdWorker;

    private static final ExecutorService ES = Executors.newFixedThreadPool(500);

    @Test
    void testSaveShop() throws InterruptedException {
        shopService.saveShop2Redis(1L, 10L);
    }

    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for(int i = 0; i < 300; i++){
            ES.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));
    }

    @Test
    public void loadShopListCache(){
        // 将相同类型的店铺分组  todo:为什么要分组
        List<Shop> shops = shopService.list();

        // 根据TypeId进行分组
        // 可以用哈希表进行判断，然后存储   也可以使用lambda表达式
        Map<Long, List<Shop>> shopMap = shops.stream().collect(Collectors.groupingBy(Shop::getTypeId));

        // 将分好的店铺写入redis
        for(Map.Entry<Long, List<Shop>> entry : shopMap.entrySet()){
            Long typeId = entry.getKey();
            List<Shop> values = entry.getValue();
            String key = "shop:GEO:" + typeId;
            // 可以遍历values一个个请求发送给redis，但是很浪费资源
            // 我们使用批量写入
            // todo:不做了
            // List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(values.size());>
        }
    }

    /**
     * 预热店铺数据
     */
    @Test
    public void testSaveShopToCache() throws InterruptedException {
        shopService.saveShop2Redis(1L, 20);
    }

}
