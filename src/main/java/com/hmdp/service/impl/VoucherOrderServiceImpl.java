package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.ILock;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.NonNull;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result seckillVoucher(Long voucherId) {
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }
        if(voucher.getStock()<1){
            return Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();


        //这里先加锁再调用，本质上是两个P操作的顺序问题。如果先调用再加锁，依然会导致线程安全问题
        //具体是，如果先进入事务再加锁，设A、B为同一用户的两个线程。
        // 当线程A释放锁，尚未提交事务时（尚未提交事务，即未创建订单），若线程B获取锁，查询到未创建订单，依然可以完成事务，并提交。
        // 结果：一人多单


//         改为分布式锁

            SimpleRedisLock lock = new SimpleRedisLock("order:"+userId, stringRedisTemplate);
            boolean isLock = lock.tryLock(1000);
            if(!isLock){
                //获取锁失败
                return Result.fail("一个人只能下一单！！");
            }
//        synchronized (userId.toString().intern()) {// .intern() 返回字符串规范表示
//            return createVoucherOrder(voucherId);直接用this调用绕过了事务代理，不安全。
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
//        }
        }catch (RuntimeException e){
            throw new RuntimeException(e);
        }finally {
            lock.unLock();
        }

    }

    @Transactional
    @NonNull
    public Result createVoucherOrder(Long voucherId) {
        //做一人一单的判断，在创建订单之前。
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("该用户已经购买过了");
        }

        //更新数据库，数量-1
        boolean success = seckillVoucherService.update().setSql("stock = stock -1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();
        if (!success) {
            return Result.fail("库存不足");
        }
        //创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //userId
        voucherOrder.setUserId(userId);
        //优惠券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        return Result.ok(orderId);

    }
}
