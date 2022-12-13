package com.hmdp;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.assertj.core.data.TemporalUnitOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.time.temporal.TemporalAmount;
import java.time.temporal.TemporalUnit;
import java.util.HashSet;
import java.util.concurrent.*;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Test
    public void saveShopToRedis() {
        shopService.saveShopToRedis(1L, 10L);
    }

    @Test
    public void saveShopToRedis2() {
        Shop shop = shopService.getById(2L);
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 2L, shop, 5L, TimeUnit.SECONDS);
    }

    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    public void redisIDTest() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println(id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println(end - begin);
    }

    @Test
    public void createJsonTest(){
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setBeginTime(LocalDateTime.now());
        seckillVoucher.setEndTime(LocalDateTime.now().plusDays(1));
        seckillVoucher.setStock(100);
        seckillVoucher.setCreateTime(LocalDateTime.now());
        seckillVoucher.setVoucherId(1L);
        System.out.println(JSONUtil.toJsonPrettyStr(seckillVoucher));
    }
}
