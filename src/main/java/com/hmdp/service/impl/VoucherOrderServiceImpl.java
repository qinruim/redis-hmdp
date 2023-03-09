package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
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
@SuppressWarnings("AlibabaAvoidCommentBehindStatement")
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    /**
     * 秒杀下单
     */
    @Override
    public Result secKillOrder(Long voucherId) {
         //1.查询优惠券信息
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始或结束，未开始或已结束返回异常结果
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now()) ){
            return Result.fail("秒杀尚未开始！");
        }
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束！");
        }
        //3.判断库存是否充足，不够则返回异常结果
        if (seckillVoucher.getStock() < 1){
            return Result.fail("库存不足！");
        }

        /**
         * 需要加锁（从查询订单一直到新增订单的逻辑），防止多线程并发时一人多单
         * 给用户id加锁，即一个用户id一把锁，而不是加在createVoucherOrder方法上，所有用户一把锁
         * 注意锁的粒度（范围）范围变小，性能提升
         * 不应把锁加在下面事务内部，防止锁释放了，而事务还没有提交，导致线程安全问题，应把锁加在这个事务外部
         *
         * 使用intern()方法  因为tostring（）方法底层会new一个String，即使id值一样tostring之后时不一样的
         * 而intern方法会去常量池里寻找一样的值返回，因此只要id值一样，每次返回值就一样，保证同一个id值是同一个对象，加同一把锁
         */
        Long userId = UserHolder.getUser().getId();
        synchronized (userId.toString().intern()) {
            /**
             * 这里this调用的是当前VoucherOrderServiceImpl对象，而不是代理对象，没有事务功能
             * 而事务生效是springBoot对当前类做了动态代理，拿到了代理对象来做事务处理
             * 因此要先拿到对应接口的事务代理对象,用代理对象调用事务函数（在接口中创建这个函数）
             */
//            return createVoucherOrder(voucherId);
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //一人一单逻辑   判断订单是否存在（防止一人多个订单）
        Long userId = UserHolder.getUser().getId();


            int count = this.query()
                    .eq("user_id", userId)
                    .eq("voucher_id", voucherId)
                    .count();
            if (count > 0) {
                //用户已经购买过了，不能下单
                return Result.fail("您已经购买过秒杀券，一个用户限购一张！");
            }


            //4.减库存，创建订单，返回订单id
            //返回一个boolean值，代表当前更新操作是否成功
            boolean success = seckillVoucherService.update()
                    //更新语句
                    .setSql("stock = stock - 1")
                    //where  用查询到的stock做乐观锁 防止超卖
                    .eq("voucher_id", voucherId)
                    .gt("stock", 0)
                    .update();
            //扣减库存失败
            if (!success) {
                return Result.fail("库存不足！");
            }

            //5.创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            //订单id（唯一生成）
            Long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            //用户id
            voucherOrder.setUserId(userId);
            //代金券id
            voucherOrder.setVoucherId(voucherId);
            //6.将订单写入数据库
            save(voucherOrder);
            //7.返回订单id
            return Result.ok(orderId);

    }
}
