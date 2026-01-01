package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.*;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;
    @Autowired
    private CacheClient cacheClient;
    private ExecutorService es = Executors.newFixedThreadPool(500);
    @Autowired
    private RedisIdWorker redisIdWorker;

//    @Test
//    void testSaveShop(){
//        shopService.saveShop2Redis(1L,10L);
//    }

    @Test
    void testSaveShop() throws InterruptedException{
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY+1,shop,10L, TimeUnit.SECONDS);
    }

    @Test
    void testIdWorker() throws Exception{
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task=()->{
            for(int i=0;i<100;i++){
                long id = redisIdWorker.nextId("order");
                System.out.println("id="+id);
            }
            latch.countDown();
        };

        long begin = System.currentTimeMillis();
        for(int i=0;i<300;i++){
            es.submit(task);
        }
        latch.await();
        long end=System.currentTimeMillis();
        System.out.println("时间："+(end-begin));
    }
}
