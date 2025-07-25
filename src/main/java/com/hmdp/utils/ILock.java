package com.hmdp.utils;

public interface ILock {

    /*
    * 尝试获取锁
    * @param timeoutSec 锁的过期时间，过期自动释放
    * @return true成功获取锁，false获取锁失败
    * */
    boolean tryLock(long timeoutSec);

    void unlock();
}
