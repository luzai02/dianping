package com.hmdp.service.impl;

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
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

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

    @Override
    // @Transactional   // 这里做了多次数据库修改，要添加事务
    // 使用悲观锁后，如果seckillVoucher加事务，会导致creatVoucherOrder无法第一时间提交
    public Result seckillVoucher(Long voucherId) {
        // 查询优惠券信息
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if(seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀尚未开始");
        }
        if(seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已将结束啦");
        }
        if(seckillVoucher.getStock()<1){
            return Result.fail("已将抢光了");
        }

        // 加悲观锁，防止并发问题一人多单
        Long userId = UserHolder.getUser().getId();
        // String 中每次toString都会创建一个新的对象，所以要用intern()从字符串池中获取对象
        synchronized (userId.toString().intern()){   // 锁住当前用户（线程中共享的对象）
            // 创建代理对象，使用代理对象调用第三方事务方法，防止事务失效
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
    }

    // 实现用户下单
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 一人一单   查询数据库，看是否已经买过  todo:（这里查过的信息可以存到redis，防止恶意访问数据库
        Long userId = UserHolder.getUser().getId();
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

        // 创建抢购订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order"); // 获取订单id
        voucherOrder.setId(orderId);
        // UserHolder.getUser().getId();  // 获取当前的用户id
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);   // 代金券的id(也就是逻辑外键)
        log.info("创建订单成功");
        save(voucherOrder);

        return Result.ok(orderId);
    }
}
