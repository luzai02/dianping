package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.log.Log;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.aop.framework.DefaultAdvisorChainFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    // AopContext.currentProxy()底层也是利用ThreadLocal获取的，所以异步线程中也无法使用。
    // 解决方案有两种，第一种是将代理对象和订单一起放入阻塞队列中
    // 第二种是将代理对象的作用域提升，变成一个成员变量
    private IVoucherOrderService proxy;



    // 使用消息队列，分开线程处理
    @PostConstruct
    private void inti(){

        // 执行线程任务
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandel());
    }

    // 存储订单的阻塞队列，订单任务
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    // 线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    // 队列名
    private static final String QUEUE_NAME = "stream.orders";



    // 线程任务：不断从阻塞队列中取出订单信息，进行下单
    private class VoucherOrderHandel implements Runnable{
        @Override
        public void run() {
            while(true){

                try{
                    // 从消息队列中获取订单信息
                    // XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders > （从下一个未被消费的开始）
                    List<MapRecord<String, Object, Object>> messageList = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            // StreamReadOptions.empty()：创建一个空的读取选项对象，用于配置读取消息的行为。
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(1)),
                            StreamOffset.create(QUEUE_NAME, ReadOffset.lastConsumed())
                    );
                    // 如果消息没有获取成功，进入下一次循环
                    if(messageList == null || messageList.isEmpty()){
                        continue;
                    }
                    // 消息获取成功，可以下单
                    // 将消息对象转换为VoucherOrder对象
                    MapRecord<String, Object, Object> record = messageList.get(0);
                    Map<Object, Object> messageMap = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(messageMap, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    // ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(QUEUE_NAME, "g1", record.getId());
                }catch (Exception e){
                    log.error("消息队列异常",e);
                    handlePendingList();
                }

//                // 从阻塞队列中获取订单信息，并创建订单
//                try{
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.error("处理订单异常", e);
//                }
            }
        }
    }

    // 处理pending状态的消息，通过XACK确认消息
    private void handlePendingList (){
        while(true){
            try{
                // 从pendingList中获取订单信息, XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.orders 0
                List<MapRecord<String, Object, Object>> messageList = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(QUEUE_NAME, ReadOffset.from("0"))
                );
                // 判断pendingList中的消息是否有效
                if (messageList == null || messageList.isEmpty()) {
                    // 没有消息需要确认
                    break;
                }
                MapRecord<String, Object, Object> record = messageList.get(0);
                Map<Object, Object> messageMap = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(messageMap, new VoucherOrder(), true);
                handleVoucherOrder(voucherOrder);
                // 确认消息
                stringRedisTemplate.opsForStream().acknowledge(QUEUE_NAME, "g1", record.getId());
            }catch (Exception e){
                log.error("pendingList异常", e);
                // 这里不用重新调自己，直接进入下一次循环，再从pending List中取，只需要稍休眠，防止获取消息太频繁
                try{
                    Thread.sleep(50);
                }catch (Exception ex){
                    log.error("线程休眠失败", ex);
                }
            }
        }
    }


    // 创建订单
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 使用Redisson
        RLock lock = redissonClient.getLock("lock:order:"+userId);
        boolean success = lock.tryLock();
        if(!success){
            log.error("一人只能下一单哦亲");
        }
        try {
            // 获取锁成功，创建代理对象，调用第三方事务方法，防止事务失效
            // todo: 这里为什么可以直接使用proxy
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    // 提前加载文件，判断秒杀券库存是否充足和是否下过单的lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static{
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("order.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    // @Transactional   // 这里做了多次数据库修改，要添加事务
    // 使用悲观锁后，如果seckillVoucher加事务，会导致creatVoucherOrder无法第一时间提交
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");

        // 执行lua脚本
        Long result = null ;
        try {
            result = stringRedisTemplate.execute(
                    SECKILL_SCRIPT,
                    // stringRedisTemplate.execute这个方法，第二个参数是应该List集合，标识传入Lua脚本中的的 key，
                    // 如果我们没有传key，那么直接使用Collections.emptyList()，而不是直接使用null，
                    // 是因为在 stringRedisTemplate.execute 方法内部可能对参数进行了处理，如果传递 null 可能引发NPE异常
                    Collections.emptyList(),
                    voucherId.toString(),
                    userId.toString(),
                    String.valueOf(orderId)
            );
        } catch (Exception e) {
            log.error("lua脚本执行失败", e);
            throw new RuntimeException(e);
        }

        int r = result.intValue();  // 将Long类型转换为int类型
        if(r!=0){
            return Result.fail(r==2?"不能重复下单":"库存不足");
        }

//        // long orderId = redisIdWorker.nextId("order");
//        VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setId(orderId);
//        // UserHolder.getUser().getId();  // 获取当前的用户id
//        // 异步线程无法从ThreadLocal获取id
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);   // 代金券的id(也就是逻辑外键)
//        // 将订单保存到阻塞队列中，等待线程处理
//        orderTasks.add(voucherOrder);

        // 获取锁代理对象，防止事务失效
        IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
        this.proxy = proxy;
        return Result.ok();




//        // 查询优惠券信息
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        if(seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("秒杀尚未开始");
//        }
//        if(seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("秒杀已将结束啦");
//        }
//        if(seckillVoucher.getStock()<1){
//            return Result.fail("已将抢光了");
//        }
//
//        Long userId = UserHolder.getUser().getId();

//        // 加悲观锁，防止并发问题一人多单
//        // todo：重点  String 中每次toString都会创建一个新的对象，所以要用intern()从字符串池中获取对象
//        synchronized (userId.toString().intern()){   // 锁住当前用户（线程中共享的对象）
//            // 创建代理对象，使用代理对象调用第三方事务方法，防止事务失效
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }


        // 使用redis作为分布式锁
//        SimpleRedisLock lock  = new SimpleRedisLock(stringRedisTemplate, "order:"+userId);
//        boolean success = lock.tryLock(1200);


        // todo: 注释后，使用到了分布式锁了吗？  消息-队列，一个个执行，前面已经获取锁了
//        // 使用Redisson
//        RLock lock = redissonClient.getLock("lock:order:"+userId);
//        boolean success = lock.tryLock();
//        if(!success){
//            return Result.fail("请勿重复下单");
//        }
//        try {
//            // 获取锁成功，创建代理对象，调用第三方事务方法，防止事务失效
//            proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }




    }

    // 实现用户下单
    @Transactional
    public Result createVoucherOrder(VoucherOrder voucherOrder) {
        // 一人一单   查询数据库，看是否已经买过  todo:（这里查过的信息可以存到redis，防止恶意访问数据库
        // todo: lua脚本已经判断过一次了，这里不需要判断了吧。。。
        Long userId = voucherOrder.getUserId();  // 异步线程无法从ThreadLocal中获取userId，我们需要从voucherOrder中获取userId
        Long voucherId = voucherOrder.getVoucherId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();

        if(count>0){
            return Result.fail("您已经购买过一次了");
        }


        // 库存充足，开抢
        // 乐观锁防止超卖，在CAS方法上优化————》库存大于0
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")  // 设置库存自减的原子操作
                .eq("voucher_id", voucherId)
                .gt("stock", 0)   // gt是"greater than"的缩写，表示字段 > 值的查询条件
                .update();
        if(!success){
            return Result.fail("抢购失败");
        }

//        // 创建抢购订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        long orderId = redisIdWorker.nextId("order"); // 获取订单id
//        voucherOrder.setId(orderId);
//        // UserHolder.getUser().getId();  // 获取当前的用户id
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);   // 代金券的id(也就是逻辑外键)
//        log.info("创建订单成功");
//        save(voucherOrder);
        success = this.save(voucherOrder);
        if(!success){
            throw new RuntimeException("创建订单失败");
        }
        return Result.ok(voucherOrder.getId());
    }
}
