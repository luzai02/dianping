package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.constant.RedisConstant;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshInterceptor implements HandlerInterceptor {
    // @Resource  // 依赖注入
    private final StringRedisTemplate  stringRedisTemplate;
    // 构造函数
    public RefreshInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 从请求头中获取token
        String token = request.getHeader("authorization");
        // 如果为空，直接放行  todo: 那这里的设置不就没有意义了吗？这里的只起到了token刷新的功能
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
        UserHolder.removeUser();
    }
}
