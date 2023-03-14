package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.hmdp.utils.RedisConstants.LOCK_ORDER_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@SuppressWarnings("AlibabaAvoidCommentBehindStatement")
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
    /**
     * 代理对象 在主线成初始化
     */
    IVoucherService proxy;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("secKill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * 阻塞队列
     */
    private BlockingDeque<VoucherOrder> orderTasks = (BlockingDeque<VoucherOrder>) new ArrayBlockingQueue<VoucherOrder>(1024 * 1024);
    /**
     * 处理秒杀订单的线程池（性能要求不高给一个线程就可以）
     */
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    /**
     * 前置处理器
     * 该注解在当前类初始化后立即执行
     */
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    /**
     * 线程任务 用一个内部类来实现线程处理的逻辑
     * 在用户秒杀抢购之前执行任务，即在当前类初始化之后立即执行
     */
    private class VoucherOrderHandler implements Runnable{
        /**
         * 执行异步下单
         * 不断从队列中取优惠券信息，更新数据库
         */
        @Override
        public void run() {
            while (true){
                //1.获取队列中的订单信息
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                }
            }
        }
    }

    /**
     * 处理订单
     * @param voucherOrder
     */
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //这是一个新线程，不能再从UserHolder中获取用户id
        Long userId = voucherOrder.getUserId();
        //创建锁对象（不同用户获取不同的锁，用userId做标识）
//      SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock(LOCK_ORDER_PREFIX + userId);
        //使用redisson提供的锁
//        boolean isLock = lock.tryLock(REDIS_LOCK_TTL);
        boolean isLock = lock.tryLock();

        if (isLock) {
            //获取锁失败 异步处理 不用再返回信息给前端
            log.error("获取锁失败，不允许重复下单");
            return;
        }
        try {
            //提前在主线程获取代理对象(是通过ThreadLocal获取的，这是一个新线程，拿不到代理对象)
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            //释放锁
            lock.unlock();
        }
    }

    /**
     * 秒杀下单
     */
//    @Override
//    public Result secKillOrder(Long voucherId) {
//        //1.查询优惠券信息
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        //2.判断秒杀是否开始或结束，未开始或已结束返回异常结果
//        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始！");
//        }
//        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束！");
//        }
//        //3.判断库存是否充足，不够则返回异常结果
//        if (seckillVoucher.getStock() < 1) {
//            return Result.fail("库存不足！");
//        }
//
//        /**
//         * 需要加锁（从查询订单一直到新增订单的逻辑），防止多线程并发时一人多单
//         * 给用户id加锁，即一个用户id一把锁，而不是加在createVoucherOrder方法上，所有用户一把锁
//         * 注意锁的粒度（范围）范围变小，性能提升
//         * 不应把锁加在下面事务内部，防止锁释放了，而事务还没有提交，导致线程安全问题，应把锁加在这个事务外部
//         *
//         * 使用intern()方法  因为tostring（）方法底层会new一个String，即使id值一样tostring之后是不一样的
//         * 而intern方法会去常量池里寻找一样的值返回，因此只要id值一样，每次返回值就一样，保证同一个id值是同一个对象，加同一把锁
//         */
//        Long userId = UserHolder.getUser().getId();
//        //使用synchronized，分布式部署时，不同服务器会获得不同的锁，有线程安全问题
////        synchronized (userId.toString().intern()) {
////            /**
////             * 这里this调用的是当前VoucherOrderServiceImpl对象，而不是代理对象，没有事务功能
////             * 而事务生效是springBoot对当前类做了动态代理，拿到了代理对象来做事务处理
////             * 因此要先拿到对应接口的事务代理对象,用代理对象调用事务函数（在接口中创建这个函数）
////             */
//////            return createVoucherOrder(voucherId);
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);
////        }
//        /**
//         * 使用分布式锁
//         */
//        //创建锁对象(用的是自定义的锁)（不同用户获取不同的锁，用userId做标识）
////      SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock(LOCK_ORDER_PREFIX + userId);
//        //使用redisson提供的锁
////        boolean isLock = lock.tryLock(REDIS_LOCK_TTL);
//        boolean isLock = lock.tryLock();
//
//        if (isLock) {
//            //获取锁失败
//            return Result.fail("不允许重复下单");
//        }
//        try {
//            //获取代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//
//            //释放锁
//            lock.unlock();
//        }
//    }
    /**
     * 秒杀下单改造（lua）
     */
    @Override
    public Result secKillOrder(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                //第二个参数是需要的key（一个集合），但是脚本中没有key类型参数，给一个空集合
                Collections.emptyList(),
                //其他类型参数（args）,要字符串形式，有两个
                voucherId.toString(), userId
        );
        int r = result.intValue();
        //2.判断结果是否为0，不为0返回异常信息
        if (r != 0){
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //3.结果为0,有购买资格，封装优惠券
        Long orderId = redisIdWorker.nextId("order");
            //创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
            //订单id
        voucherOrder.setId(orderId);
             //用户id
        voucherOrder.setUserId(userId);
             //代金券id
        voucherOrder.setVoucherId(voucherId);
        //4.获取代理对象
        proxy = (IVoucherService) AopContext.currentProxy();
        //5.将优惠券信息放入阻塞队列
        orderTasks.add(voucherOrder);

        return Result.ok();
    }


    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //一人一单逻辑   判断订单是否存在（防止一人多个订单）
//        Long userId = UserHolder.getUser().getId();
        Long userId = voucherOrder.getUserId();
        //查询订单
        int count = this.query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherOrder.getVoucherId())
                .count();
        if (count > 0) {
            //用户已经购买过了，不能下单
//            return Result.fail("您已经购买过秒杀券，一个用户限购一张！");
            log.error("您已经购买过秒杀券，一个用户限购一张");
            return;
        }


        //4.减库存，创建订单，返回订单id
        //返回一个boolean值，代表当前更新操作是否成功
        boolean success = seckillVoucherService.update()
                //更新语句
                .setSql("stock = stock - 1")
                //where  用查询到的stock做乐观锁 防止超卖
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        //扣减库存失败
        if (!success) {
//            return Result.fail("库存不足！");
            log.error("库存不足");
            return;
        }

//        //5.创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        //订单id（唯一生成）
//        Long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        //用户id
//        voucherOrder.setUserId(userId);
//        //代金券id
//        voucherOrder.setVoucherId(voucherOrder);

        //6.将订单写入数据库 (改造后voucherOrder是现成的不需要再封装订单对象，直接保存到数据库)
        save(voucherOrder);
        //7.返回订单id
//        return Result.ok(orderId);

    }
}
