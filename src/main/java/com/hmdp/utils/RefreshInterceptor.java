package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.constant.RedisConstant;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class RefreshInterceptor implements HandlerInterceptor {
    // @Resource  // 依赖注入
    // new出来的对象是无法直接注入IOC容器的（LoginInterceptor是直接new出来的）
    // 所以这里需要再配置类中注入，然后通过构造器传入到当前类中
    // todo: 已通过注解Component 注入
    private final StringRedisTemplate  stringRedisTemplate;
    // 构造函数
    public RefreshInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 从请求头中获取token
        String token = request.getHeader("authorization");
        // 如果为空，直接放行
        if(StrUtil.isBlank(token)){
            return true;
        }
        // 从redis中获取用户，里面的用户是用Map存储的，也需要用Map取出
        String key = RedisConstant.LOGIN_USER_KEY + token;
        Map<Object, Object> user = stringRedisTemplate.opsForHash().entries(key);

        if(user.isEmpty()){
            // 如果为空，直接放行
            return true;
        }

        // 将用户信息存储到ThreadLocal中
        // 将查询到的map转化为DTO对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(user, new UserDTO(), false);
        UserHolder.saveUser(userDTO);
        // 刷新token有效期
        stringRedisTemplate.expire(key, RedisConstant.LOGIN_USER_TTL, TimeUnit.HOURS);
        return true;
    }
    // 释放资源

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 若 ThreadLocal 不清理，用户数据会一直留在线程里，下次该线程处理其他请求时，可能拿到错误的用户信息（脏数据）
        // 长期积累还会占用内存，导致内存泄漏。
        // 资源释放是请求处理的最后一步，已经不需要用户信息了，可以释放 ThreadLocal
        UserHolder.removeUser();
    }
}
