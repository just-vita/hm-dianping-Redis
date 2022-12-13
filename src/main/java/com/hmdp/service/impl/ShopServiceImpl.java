package com.hmdp.service.impl;

import cn.hutool.core.lang.TypeReference;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
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

    @Override
    public Result queryShopById(Long id) {
        // 缓存穿透 储存空值的解决方案
        // Shop shop = cachePassThrough(id);
        // 使用缓存工具类实现
        // Shop shop = cacheClient.cachePassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 缓存穿透+缓存击穿 互斥锁的解决方案
         Shop shop = cacheMutexLock(id);

        // 使用逻辑过期解决热点商品的缓存击穿问题
        // Shop shop = cacheLogicalExpire(id);
        // 使用缓存工具类实现
//        Shop shop = cacheClient.cacheLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null){
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);
    }

    public void saveShopToRedis(Long id, Long expireTime){
        Shop shop = getById(id);
        try {
            // 模拟查询时间
            Thread.sleep(200);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        RedisData<Shop> redisData = new RedisData<>();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    // 创建重建缓存的线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    private Shop cacheLogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(shopJson)){
            // 缓存为空，代表并未预热此热点商品，直接返回空
            return null;
        }
        // 缓存不为空
        // 使用泛型实现类型转换
        RedisData<Shop> redisData = JSONUtil.toBean(shopJson,
                new TypeReference<RedisData<Shop>>() {},
                false);
        Shop shop = redisData.getData();
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())){
            // 未过期，直接返回数据
            return shop;
        }

        // 已过期，需要进行缓存重建
        String lockKey = LOCK_SHOP_KEY + id;
        // 获取互斥锁
        boolean flag = tryLock(lockKey);
        if (flag){
            // 获取成功，再查询一次缓存，以免缓存已更新
            String shopJson2 = stringRedisTemplate.opsForValue().get(key);
            // 检查缓存是否过期
            RedisData<Shop> redisData2 = JSONUtil.toBean(shopJson2,
                    new TypeReference<RedisData<Shop>>() {},
                    false);
            Shop shop2 = redisData2.getData();
            LocalDateTime expireTime2 = redisData2.getExpireTime();
            if (expireTime2.isAfter(LocalDateTime.now())){
                // 已被更新，直接返回新数据
                return shop2;
            }

            // 缓存未被更新，开启新线程进行缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 更新缓存
                    saveShopToRedis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            });
        }
        // 此处要返回已过期的数据
        // 因为缓存重建是使用新线程进行的，主线程还是要返回过期的商品信息
        return shop;
    }

    private Shop cacheMutexLock(Long id){
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)){
            // 成功命中缓存
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 此时已经可以断定redis并没有命中缓存
        // 如果取出来的是null代表redis里没有这条数据
        // 但如果取出来的不是null，就代表已经存入过空值，可以直接返回错误信息
        if (shopJson != null){ // 同 "".equals(shopJson)
            return null;
        }
        String lockKey = LOCK_SHOP_KEY + id;
        // 获取互斥锁
        Shop shop = null;
        try {
             /* 递归方式
             boolean flag = tryLock(lockKey);
             if (!flag){
                 Thread.sleep(50);
                 // 回头再查询一次数据在redis中是否存在
                 return cacheMutexLock(id);
             }*/

            // 循环获取的方式，获取到锁后要再查询一次缓存
            while (!tryLock(lockKey)){
                Thread.sleep(50);
            }
            // 获取到锁，第二次查询缓存中数据是否存在
            String secondGet = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(secondGet)){
                // 成功命中缓存
                return JSONUtil.toBean(secondGet, Shop.class);
            }
            // 获取互斥锁成功，查询数据存入redis
            shop = getById(id);
            // 模拟查询需要的时间
            Thread.sleep(200);
            // 不存在，防止缓存穿透问题
            if (shop == null){
                // 存入空数据，设置过期时间
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 存入真数据，设置过期时间
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            // 只是睡眠打断异常，无需在意
            throw new RuntimeException(e);
        } finally {
            // 释放互斥锁
            unLock(lockKey);
        }
        return shop;
    }

    private Shop cachePassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 此时已经可以断定redis并没有命中缓存
        // 如果取出来的是null代表redis里没有这条数据
        // 但如果取出来的不是null，就代表已经存入过空值，可以直接返回错误信息
        if (shopJson != null){
            return null;
        }

        Shop shop = getById(id);
        // 不存在，防止缓存穿透问题
        if (shop == null){
            // 存入空数据，设置过期时间
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        // 直接返回有可能会因为自动拆箱而发生空指针异常，此处直接进行拆箱操作
        return BooleanUtil.isTrue(flag);
    }

    public void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    @Override
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if (id == null){
            return Result.fail("店铺ID不能为空");
        }
        updateById(shop);
        stringRedisTemplate.delete(CACHE_SHOP_KEY +shop.getId());
        return Result.ok();
    }
}
