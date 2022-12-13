package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * 缓存击穿、缓存穿透工具类
 *
 * @Author vita
 * @Date 2022/11/30 16:12
 */
@Component
public class CacheClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void set(String key, Object value, Long time, TimeUnit timeUnit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit){
        RedisData<Object> redisData = new RedisData<>();
        redisData.setData(value);
        // 设置逻辑过期时间为time秒后
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存穿透
     */
    public <R, ID> R cachePassThrough(
            String keyPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallBack,
            Long time, TimeUnit timeUnit){
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)){
            // 将json转换为指定type
            return JSONUtil.toBean(json, type);
        }
        // 此时已经可以断定redis并没有命中缓存
        // 如果取出来的是null代表redis里没有这条数据
        // 但如果取出来的不是null，就代表已经存入过空值，可以直接返回错误信息
        if (json != null){ // 同 "".equals(json)
            return null;
        }
        // 调用用户传过来的查询数据库的方法
        R r = dbFallBack.apply(id);
        // 不存在，防止缓存穿透问题
        if (r == null){
            // 存入空数据，设置过期时间
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 调用已有方法
        this.set(key, r, time, timeUnit);
        return r;
    }

    /**
     * 缓存击穿
     */
    // 创建重建缓存的线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R, ID> R cacheLogicalExpire(
            String keyPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time, TimeUnit timeUnit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            // 缓存为空，代表并未预热此热点商品，直接返回空
            return null;
        }
        // 缓存不为空
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 未过期，直接返回数据
            return r;
        }

        // 已过期，需要进行缓存重建
        String lockKey = LOCK_SHOP_KEY + id;
        // 获取互斥锁
        boolean flag = tryLock(lockKey);
        if (flag) {
            // 获取成功，再查询一次缓存，以免缓存已更新
            String json2 = stringRedisTemplate.opsForValue().get(key);
            // 检查缓存是否过期
            RedisData redisData2 = JSONUtil.toBean(json2, RedisData.class);
            R r2 = JSONUtil.toBean((JSONObject) redisData2.getData(), type);
            LocalDateTime expireTime2 = redisData2.getExpireTime();
            if (expireTime2.isAfter(LocalDateTime.now())) {
                // 已被更新，直接返回新数据
                return r2;
            }

            // 缓存未被更新，开启新线程进行缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    R res = dbFallback.apply(id);
                    // 更新缓存
                    setWithLogicalExpire(key, res, time, timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            });
        }
        // 此处要返回已过期的数据
        // 因为缓存重建是使用新线程进行的，主线程还是要返回过期的商品信息
        return r;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        // 直接返回有可能会因为自动拆箱而发生空指针异常，此处直接进行拆箱操作
        return BooleanUtil.isTrue(flag);
    }

    public void unLock(String key){
        stringRedisTemplate.delete(key);
    }

}
