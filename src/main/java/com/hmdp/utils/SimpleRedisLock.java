package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 初步的redis分布式锁
 *
 * @Author vita
 * @Date 2022/12/6 19:16
 */
public class SimpleRedisLock implements ILock {

    private StringRedisTemplate stringRedisTemplate;
    private String name;
    private static final String KEY_PREFIX = "lock:";
    // 锁标识
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    // 读取lua文件的脚本对象
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        // 初始化redisScript对象
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        // 根据resource路径查找文件
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        // 设置返回类型
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    @Override
    public boolean tryLock(long time) {
        // 不同项目中的线程ID可能重复
        // 拼接上唯一的UUID
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 如果存入失败代表已经有锁了
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(
                KEY_PREFIX + name, threadId, time, TimeUnit.SECONDS);
        // 使用这种写法的话就算flag为null，也会返回false
        return Boolean.TRUE.equals(flag);
    }

    @Override
    public void unlock() {
//        // 防止锁误删问题
//        // 拼接出UUID和线程标识
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        // 获取redis中存入的线程标识
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        // 判断是否是当前线程加的锁
//        if (threadId.equals(id)){
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }

        // 使用lua脚本释放锁
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name), // 创建一个单元素的列表
                ID_PREFIX + Thread.currentThread().getId()
        );
    }
}
