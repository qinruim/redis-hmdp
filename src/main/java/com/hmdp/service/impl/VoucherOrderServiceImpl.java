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
    @Transactional
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
        //4.减库存，创建订单，返回订单id
        //返回一个boolean值，代表当前更新操作是否成功
        boolean success = seckillVoucherService.update()
                //更新语句
                .setSql("stock = stock - 1")
                //where
                .eq("voucher_id", voucherId)
                .update();
        //扣减库存失败
        if (!success){
            return Result.fail("库存不足！");
        }
        //5.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //订单id（唯一生成）
        Long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //用户id
        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        //代金券id
        voucherOrder.setVoucherId(voucherId);
        //6.将订单写入数据库
        save(voucherOrder);
        //7.返回订单id
        return Result.ok(orderId);
    }
}
