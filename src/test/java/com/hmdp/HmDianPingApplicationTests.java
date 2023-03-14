package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    //线程池
    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    void testShopSave(){
        shopService.savaShopToRedis(1L,10L);
    }

    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(300);
        //每个线程生成100个id
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                Long id = redisIdWorker.nextId("order");
                System.out.println("id:"+id);
            }
            countDownLatch.countDown();
        };

        long begin = System.currentTimeMillis();
        //提交300个线程   一共300*100个id
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        countDownLatch.await();
        long end = System.currentTimeMillis();
        System.out.println("time=" + (end - begin));

    }

    @Test
    void restRedisson() throws InterruptedException {
        //获取锁（可重入），指定锁的名称
        RLock lock = redissonClient.getLock("anyLock");
        //尝试获取锁，参数：获取锁的最大等待时间（期间会重试），锁自动释放时间，时间单位
        boolean isLock = lock.tryLock(1, 10, TimeUnit.SECONDS);
        //判断释放获取成功
        if (isLock){
            try {
                System.out.println("执行业务");
            } finally {
                lock.unlock();
            }
        }

    }
}
