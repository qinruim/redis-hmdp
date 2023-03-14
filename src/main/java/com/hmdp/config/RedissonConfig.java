package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {
    /**
     * redisson工厂类，可以拿到各种redisson提供的工具
     * @return
     */
    @Bean
    public RedissonClient redissonClient(){
        //配置
        Config config = new Config();
        //设置单节点redis（集群设置多节点）
        config.useSingleServer().setAddress("redis://127.0.0.1:6379").setPassword("qinruimao");
        //创建RedissonClient对象,将之返回
        return Redisson.create(config);
    }
}
