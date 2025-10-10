package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.log.Log;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.aop.framework.DefaultAdvisorChainFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

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

    // 改为使用rocketmq作为消息队列
    @Resource
    private RocketMQTemplate rocketMQTemplate;
    // 定义RocketMQ主题（同一业务用同一主题）
    private static final String SECKILL_ORDER_TOPIC = "seckill-order1";

    // AopContext.currentProxy()底层也是利用ThreadLocal获取的，所以异步线程中也无法使用。
    // 解决方案有两种，第一种是将代理对象和订单一起放入阻塞队列中
    // 第二种是将代理对象的作用域提升，变成一个成员变量
    private IVoucherOrderService proxy;


    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    // 创建一个静态代码块，初始化lua脚本
    static{
        // 提前加载文件，判断秒杀券库存是否充足和是否下过单的lua脚本
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("order.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    @Override
    @Transactional   // 这里做了多次数据库修改，要添加事务
    //todo:使用悲观锁后，如果seckillVoucher加事务，会导致creatVoucherOrder无法第一时间提交???
    public Result seckillVoucher(Long voucherId) {
        // 获取当前用户
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
                    userId.toString()
            );
        } catch (Exception e) {
            log.error("lua脚本执行失败", e);
            throw new RuntimeException(e);
        }

        int r = result.intValue();  // 将Long类型转换为int类型
        if(r!=0){
            return Result.fail(r==2?"不能重复下单":"库存不足");
        }

        // 拥有下单资格，创建订单对象
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        // 通过rocketmq发送消息
        try{
            rocketMQTemplate.convertAndSend(SECKILL_ORDER_TOPIC, voucherOrder);
            log.info("发送消息成功:{}",voucherOrder);
        }catch (Exception e){
            // 消息发送失败有很多原因：1.网络问题 2.服务器问题 3.业务问题
            log.error("消息发送失败", e);
            // redis回滚
            rollbackRedis(voucherId, userId);
            return Result.fail("下单失败，请重试");
        }
        
        // 获取锁代理对象，防止事务失效
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        return Result.ok(orderId);
    }

    private void rollbackRedis(Long voucherId, Long userId){
        String lockKey = "lock:rollback:" + voucherId;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            boolean locked = lock.tryLock(0, 10, TimeUnit.SECONDS);
            if (!locked) {
                log.error("回滚库存获取锁失败，voucher={}", voucherId);
                return; // 未获锁，直接返回，后续finally会执行，但需避免解锁
            }

            try {
                // 原回滚逻辑：仅在获锁成功后执行
                String stockKey = "seckill:stock:" + voucherId;
                String orderKey = "seckill:order:" + voucherId;
                String rollbackScript = "redis.call('INCR', KEYS[1]); redis.call('SREM', KEYS[2], ARGV[1]); return 1;";
                stringRedisTemplate.execute(
                        new DefaultRedisScript<>(rollbackScript, Long.class),
                        Arrays.asList(stockKey, orderKey),
                        userId.toString()
                );
            } finally {
                // 仅在“获锁成功”后，才执行解锁（嵌套finally确保解锁）
                lock.unlock();
            }
        } catch (Exception e) {
            // 外层finally删除，避免未获锁时解锁
            log.error("回滚库存异常", e);
        }
    }


    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {

        // todo： 可以查询订单号来先一步判断

        // 一人一单   查询数据库，看是否已经买过  todo:（这里查过的信息可以存到redis，防止恶意访问数据库
        // todo: lua脚本已经判断过一次了，这里不需要判断了吧。。。
        Long userId = voucherOrder.getUserId();  // 异步线程无法从ThreadLocal中获取userId，我们需要从voucherOrder中获取userId
        Long voucherId = voucherOrder.getVoucherId();
        // 幂等性判断
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();

        if(count>0){
            log.error("重复下单，user={}, voucher={}", userId, voucherId);
            return; // 重复下单直接返回，不抛异常（避免 RocketMQ 重复重试）
        }

        // 库存充足，开抢
        // 乐观锁防止超卖，在CAS方法上优化————》库存大于0
        // 因为是一人一单，所以只要库存大于0就可以卖
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")  // 设置库存自减的原子操作
                .eq("voucher_id", voucherId)
                .gt("stock", 0)   // gt是"greater than"的缩写，表示字段 > 值的查询条件
                .update();
        if(!success){
            log.error("抢购失败");
            throw new RuntimeException("库存不足");   // 抛异常，RocketMQ会进行重试
        }
        save(voucherOrder);
    }

    // 实现用户下单
    public void handleVoucherOrder(VoucherOrder voucherOrder) throws InterruptedException {
        Long userId = voucherOrder.getUserId();  // 异步线程无法从ThreadLocal中获取userId，我们需要从voucherOrder中获取userId
        // 使用Redisson分布式锁，防止多进程访问同一用户下单
        RLock lock = redissonClient.getLock("lock:order:"+userId);
        boolean success = lock.tryLock(0,30, TimeUnit.SECONDS);
        if(!success){
            log.error("获取锁失败");
            throw new RuntimeException("一人只能下一单哦亲");
        }
        // 获取锁成功
        try {
            // 获取锁成功，创建代理对象，调用第三方事务方法，防止事务失效
            // todo: 这里为什么不能使用代理？什么时候该使用代理？
            proxy.createVoucherOrder(voucherOrder);
            log.info("创建订单成功");
        }catch (Exception e){
            log.error("创建订单失败：{}",e.getMessage());
        } finally {
            lock.unlock();
        }
    }
}




