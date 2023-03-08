package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;

    /**
     * 根据id查询商户信息
     */
    @Override
    public Result queryById(Long id) {
        //缓存空值解决缓存穿透的查询方法
        Shop shop = queryWithPassThrough(id);
//        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //互斥锁解决缓存击穿的查询方法
//        Shop shop = queryWithMutex(id);

        //逻辑过期解决缓存击穿
//        Shop shop = queryWithLogicalExpire(id);
//        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);


        if (shop == null){
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }

    /**
     * 线程池
     */
    private static final ExecutorService CACHE_REBUILD_EXCUTOR = Executors.newFixedThreadPool(10);

    /**
     * 缓存穿透（根据id查shop信息）
     */
    public Shop queryWithPassThrough(Long id){
        String shopKey = CACHE_SHOP_KEY + id;
        //1.从redis查询商铺
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        //2.判断缓存是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            //3.命中则返回商铺信息（将json转为java对象返回）
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断命中的是否是空值
        if (shopJson != null) {
            //即命中空字符串“”
            return null;
        }
        //4.未命中且不为空则根据id查询数据库
        Shop shop = getById(id);
        //5.判断数据库中是否存在该商铺
        if (shop == null) {
            //6.不存在则返回错误  给redis写入空值防止缓存穿透
            stringRedisTemplate.opsForValue().set(shopKey, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //7.存在则将商铺数据写入redis，并设置过期时间，再从redis返回给客户端
        stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }
    /**
     * 用逻辑过期解决缓存击穿
     * @param id
     * @return
     */
    public Shop queryWithLogicalExpire(Long id){
        String shopKey = CACHE_SHOP_KEY + id;
        //1.从redis查询商铺
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        //2.判断缓存是否命中
        if (StrUtil.isBlank(shopJson)) {
            //3.未命中则返回空
            return null;
        }
        //4.命中,先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //6.未过期返回shop信息
            return shop;
        }
        //7.已经过期，缓存重建
        //获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        if (getLock(lockKey)) {
            //获取成功，开辟新线程重建缓存（根据id到db查询，写入redis，释放互斥锁）
            //获取锁成功后应该再次检测redis缓存是否过期，DoubleCheck，若存在则无需重建缓存
            CACHE_REBUILD_EXCUTOR.submit(()->{
                //重建缓存
                try {
                    this.savaShopToRedis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }

        //没能成功获取，或者重建缓存后都要返回过期的shop信息
        return shop;
    }

    /**
     * 互斥锁解决缓存击穿
     */
    private Shop queryWithMutex(Long id) {
        //缓存key
        String shopKey = CACHE_SHOP_KEY + id;
        //1.从redis查询商铺
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        //2.判断缓存是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            //3.命中则返回商铺信息（将json转为java对象返回）
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断命中的是否是空值而非空字符串
        if (shopJson != null) {
            //即命中空字符串“”
            return null;
        }
        //4.未命中先尝试获取互斥锁
        Shop shop;
        String lockKey = LOCK_SHOP_KEY + id;
        try {
            if (!getLock(lockKey)){
                //获取互斥锁失败，休眠一段时间并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //获取互斥锁成功根据id查询数据库并写入redis
            shop = getById(id);
            //模拟重建缓存（从数据库查询的延迟）
            Thread.sleep(200);
            //5.判断数据库中是否存在该商铺
            if (shop == null) {
                //6.不存在则返回错误  给redis写入空值防止缓存穿透
                stringRedisTemplate.opsForValue().set(shopKey, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //7.存在则将商铺数据写入redis，并设置过期时间
            stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //8.释放互斥锁
            unLock(lockKey);
        }
        //9.返回数据
        return shop;
    }

    /**
     * 获取锁
     * 用redis中的setnx（stringRedisTemplate.opsForValue().setIfAbsent）设置一个key-value，过期时间比实际业务要长
     */
    private boolean getLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        //用工具拆箱，防止包装类拆箱成基础类时出错
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁 ：redis删除key即可
     */
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }
    /**
     * 根据id修改店铺，先修改数据库，再删除缓存
     * 添加事务，在更新数据库、删除缓存出现异常时进行回滚 确保数据库和焕勋操作的原子性（都成功或都失败）
     */
    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        Long shopId = shop.getId();
        if (shopId == null) {
            return Result.fail("店铺id不能为空！");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }

    /**
     * 存储含逻辑过期时间的存储店铺信息到redis的方法
     * @param id
     * @param expireSeconds
     */
    public void savaShopToRedis(Long id,Long expireSeconds){
        //1.查询店铺信息
        Shop shop = getById(id);
        //模拟重建缓存的延迟
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        //在当前时间加上定义的逻辑过期时间
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
    }
}
