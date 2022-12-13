package com.hmdp.utils;

/**
 * @Author vita
 * @Date 2022/12/6 13:46
 */
public interface ILock {

    boolean tryLock(long time);

    void unlock();

}
