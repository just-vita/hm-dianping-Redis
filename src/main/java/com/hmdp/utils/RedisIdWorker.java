package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;


/**
 * Redis全局唯一ID生成类
 *
 * @Author vita
 * @Date 2022/12/5 10:22
 */
@Component
public class RedisIdWorker {
    /**
     * 开始的时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    /**
     * 序列号的长度
     */
    private static final int COUNT_BITS = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String keyPrefix){
        // 生成时间戳部分
        LocalDateTime now = LocalDateTime.now();
        // 转为秒数
        long second = now.toEpochSecond(ZoneOffset.UTC);
        // 获取秒数的差值
        long timeStamp = second - BEGIN_TIMESTAMP;

        // 生成序列号部分 使用自增count实现ID递增效果
        // 得到今天的时间字符串
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 使redis中的今日ID生成数量增加1
        // 注意 此处不会发生空指针，可以使用基本类型long
        // 如果redis中没有此key数据，则会自动创建一条新数据，防止了空指针的出现
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        // 时间戳前移32位并对count进行与运算后拼接并返回
        return timeStamp << COUNT_BITS | count;
    }
}
