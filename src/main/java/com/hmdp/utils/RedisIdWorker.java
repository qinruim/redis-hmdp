package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 生成全局唯一id
 */
@Component
public class RedisIdWorker {
    /**
     * 开始时间戳：2022.1.1.0：0：0 对应以秒技术的时间
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    /**
     * 序列号的位数
     */
    private static final int COUNT_BITS = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 生成全局唯一id，返回id是一个64位的long型
     * id需要自增，用keyPrefix区分不同业务
     * @param keyPrefix
     * @return
     */
    public Long nextId(String keyPrefix){
        //1.生成时间戳 当前时间减去开始时间
        LocalDateTime now = LocalDateTime.now();
        long timeStamp = now.toEpochSecond(ZoneOffset.UTC) - BEGIN_TIMESTAMP;
        //2.生成序列号 利用redis的自增(先获取日期拼接到key：每天一个key，再自增；防止一直用同一个key，超过单个key的存储上限)
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        //3.拼接返回
        return (timeStamp << COUNT_BITS) | count;
    }

    /**
     * 获取2022.1.1.0：0：0 对应以秒技术的时间
     * @param args
     */
//    public static void main(String[] args) {
//        LocalDateTime time = LocalDateTime.of(2022, 1, 1, 0, 0, 0);
//        System.out.println(time.toEpochSecond(ZoneOffset.UTC));
//    }
}
