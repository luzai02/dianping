package com.hmdp.utils;

import cn.hutool.core.lang.DefaultSegment;
import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
    // 要存到redis中
    private StringRedisTemplate stringRedisTemplate;

    // 锁的名称
    private String name;
    private static final String KEY_PREFIX = "lock:"; // 锁的key前缀
    // 使用hutool工具包，里面的toString传入true时可以化简uuid的横线
    private static final String ID_PREFIX = UUID.randomUUID().toString(true)+"-";

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name){
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }


    @Override
    public boolean tryLock(long timeoutSec) {
        String threadId = ID_PREFIX+Thread.currentThread().getId();
        // 获取锁
        Boolean success = stringRedisTemplate.opsForValue().
                setIfAbsent(KEY_PREFIX+name, threadId+"", timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);  // 只有是true才返回成功，false和null都返回失败
    }

    // 使用lua锁来释放锁

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));  // classpathresource是hutool提供的，可以传入文件名，会自动找到文件
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public void unlock() {
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX+name),
                ID_PREFIX+Thread.currentThread().getId()
        );
    }

    //    @Override
//    public void unlock() {
//        // 使用uuid+线程标识来判断释放锁
//        String threadId = ID_PREFIX+Thread.currentThread().getId();
//        // 获取锁中的值
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX+name);
//        if(threadId.equals(id)) {
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//    }
}
