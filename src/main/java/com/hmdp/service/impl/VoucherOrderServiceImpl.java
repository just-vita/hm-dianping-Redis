package com.hmdp.service.impl;

import cn.hutool.core.collection.ListUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private SeckillVoucherServiceImpl seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 创建单线程的异步处理线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    // 创建阻塞队列
    private final BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    // 准备好aop代理对象
    private IVoucherOrderService proxy;

    // 初始化时执行
    @PostConstruct
    private void init() {
        // 项目初始化时就开始启动阻塞队列
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    // 线程任务
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 阻塞队列中没有数据的时候会阻塞，直到有数据
                    VoucherOrder voucherOrder = orderTasks.take();
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        private void handleVoucherOrder(VoucherOrder voucherOrder) {
            // 注意：多线程无法用ThreadLocal获取userId
            Long userId = voucherOrder.getUserId();

            // 使用redisson加锁与解锁
            RLock lock = redissonClient.getLock("lock:order:" + userId);
            boolean flag = lock.tryLock();
            // 加锁成功
            if (flag) {
                try {
                    // 注意：由于是spring的事务是放在threadLocal中，此时的是多线程，事务会失效
                    // 使用提前准备好的代理对象调用方法，保证事务正常
                    proxy.createVoucherOrder(voucherOrder);
                } finally {
                    lock.unlock();
                }
            }

        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        long orderId = redisIdWorker.nextId("order");
        Long userId = UserHolder.getUser().getId();
        // 执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                ListUtil.of(SECKILL_ORDER_KEY, SECKILL_STOCK_KEY),
                voucherId.toString(),
                String.valueOf(orderId),
                userId.toString()
        );
        int r = result.intValue();
        // 返回0代表成功，不为0代表失败
        if (r != 0) {
            return r == 1 ? Result.fail("库存不足") : Result.fail("重复下单");
        }

        // 异步提交部分
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        // 放入阻塞队列
        orderTasks.add(voucherOrder);
        // 提前保存aop代理对象，保证事务正常使用
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 返回订单ID，拟定下单成功
        return Result.ok(orderId);
    }

    /*@Override
    public Result seckillVoucher(Long voucherId) {
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀还未开始");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束");
        }
        if (voucher.getStock() < 1){
            return Result.fail("库存不足");
        }

        // 控制一人一单
        Long userId = UserHolder.getUser().getId();
        // 使用intern方法，将其加入字符串常量池中
        // 只要存入一次字符串后再存入相同的字符串就必定相同
//        synchronized(userId.toString().intern()) {
//            // 获取本类的代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            // 使用代理对象来调用事务方法
//            return proxy.createVoucherOrder(voucherId);
//        }

        // 换成redisson来加锁与解锁
        // SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean flag = lock.tryLock();
        // 加锁失败，代表用户已经下过单
        if (!flag){
            return Result.fail("不能重复下单");
        }

        try {
            // 获取本类的代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            // 使用代理对象来调用事务方法
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.unlock();
        }
    }*/

    /**
     * 控制一人一单
     * 交给事务控制的类必须要被public修饰
     */
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 注意：多线程无法用ThreadLocal获取userId
        Long userId = voucherOrder.getUserId();
        Integer count = lambdaQuery()
                .eq(VoucherOrder::getUserId, userId)
                .eq(VoucherOrder::getVoucherId, voucherOrder.getVoucherId())
                .count();
        // 没有下过单
        if (count == 0) {
            // 减少库存
            boolean flag = seckillVoucherService.lambdaUpdate()
                    .setSql("stock = stock - 1")
                    .eq(SeckillVoucher::getVoucherId, voucherOrder.getVoucherId())
                    // 乐观锁错误率高，舍弃乐观锁方案
                    // .eq(SeckillVoucher::getStock, voucher.getStock())
                    // 借助数据库行锁解决超卖问题
                    .gt(SeckillVoucher::getStock, 0) // where stock > 0
                    .update();
            // 库存操作成功
            if (flag) {
                save(voucherOrder);
            }
        }
    }
}
