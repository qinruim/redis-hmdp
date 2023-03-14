package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * @author qrpop
 */
public class SimpleRedisLock implements ILock{
    //锁的名字
    private String name;
    private StringRedisTemplate stringRedisTemplate;

    //构造函数给参数初始化（外部传过来）
    public SimpleRedisLock(String name,StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }
    private static final String KEY_PREFIX = "lock:";
    private static final String THREAD_ID_PREFIX = UUID.randomUUID().toString(true) + "_";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    //用静态常量和静态代码块初始化脚本，避免每次到释放锁时再加载，提升性能
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        //指定脚本文件位置，ClassPathResource即resources路径
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        //指定返回值类型
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程标识（不同jvm之间的线程id可能重复，每一个锁对象加上一个uuid的前缀，保证锁唯一性）
        String threadId =THREAD_ID_PREFIX + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
//        return success;  //自动拆箱有NPE风险
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unLock() {
       //基于lua脚本释放锁，保证原子性
        //调用lua脚本
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                                    //指定锁的key，此处要集合，字符串转为一个单元素集合
                                    Collections.singletonList(KEY_PREFIX + name),
                                    //指定线程标识
                                    THREAD_ID_PREFIX + Thread.currentThread().getId()
                                    );
    }
//    @Override
//    public void unLock() {
//        //获取线程标识
//        String threadId =THREAD_ID_PREFIX + Thread.currentThread().getId();
//        //获取锁中的线程标识
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        //判断是否是本线程的锁，防止误删别的线程的锁
//        if (threadId.equals(id)){
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//    }
}
