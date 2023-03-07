package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

/**
 * @author qrpop
 * 封装redis工具类
 */
@Component
@Slf4j
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;


    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 线程池
     */
    private static final ExecutorService CACHE_REBUILD_EXCUTOR = Executors.newFixedThreadPool(10);

    /**
     * 将任意java对象序列化为json并存储在string类型的key
     * 并且可以设置TTL过期时间
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    /**
     * 将任意java对象序列化为json并存储在string类型的key
     * 并且可以设置逻辑过期时间，用于处理缓存击穿问题
     * @param key
     * @param value
     * @param logicalExpireTime
     * @param timeUnit
     */
    public void setWithLogicalExpire(String key, Object value, Long logicalExpireTime, TimeUnit timeUnit){
        RedisData redisData = new RedisData();
        //LocalDateTime.now().plusSeconds()方法带单位，需要转成秒
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(logicalExpireTime)));
        redisData.setData(value);
        //写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 根据指定的key查询缓存，并反序列化为指定类型
     * 利用缓存空值解决缓存穿透问题
     * 返回值类型和id类型未知，用泛型
     * @param keyPrefix id前缀
     * @param id
     * @param type
     * @param <R>
     * @param <ID>
     * @return
     */
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time,TimeUnit timeUnit){
        String key = keyPrefix + id;
        //1.从redis查询商铺
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断缓存是否命中
        if (StrUtil.isNotBlank(json)) {
            //3.命中则反序列化
            return JSONUtil.toBean(json, type);
        }
        //判断命中的是否是空值
        if (json != null) {
            //即命中空字符串“”
            return null;
        }
        //4.未命中且不为空则根据id查询数据库
        // 需要调用者传递查询逻辑 才能知道具体查询什么
        R r = dbFallback.apply(id);
        //5.判断数据库中是否存在该商铺
        if (r == null) {
            //6.不存在则返回错误  给redis写入空值防止缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //7.存在则将数据写入redis，并设置过期时间，再从redis返回给客户端
        //直接调用前面写的set方法
        this.set(key,r,time,timeUnit);
        return r;
    }

    /**
     * 根据指定的key查询缓存，并反序列化为指定类型
     * 利用逻辑过期解决缓存击穿问题
     * 返回值类型和id类型未知，用泛型
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param timeUnit
     * @param <R>
     * @param <ID>
     * @return
     */
    public <R,ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time,TimeUnit timeUnit){
        String key = keyPrefix + id;
        //1.从redis查询商铺
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断缓存是否命中
        if (StrUtil.isBlank(json)) {
            //3.未命中则返回空
            return null;
        }
        //4.命中,先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //6.未过期返回信息
            return r;
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
                    //查数据库
                    R r1 = dbFallback.apply(id);
                    //写入redis
                    this.setWithLogicalExpire(key,r1,time,timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }

        //没能成功获取，或者重建缓存后都要返回过期的shop信息
        return r;
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

}
