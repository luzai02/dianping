package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.constant.RedisConstant;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_TTL;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource  // 用法类似@atutowired，是jdk的标准注解
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail(RedisConstant.ERROR_FORM_PHONE);
        }
        // 生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 保存到 session   todo: 这里的验证码到底保存在哪？
        stringRedisTemplate.opsForValue().set(RedisConstant.LOGIN_CODE_KEY + phone, code, RedisConstant.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        log.info("发送验证码成功:{}",code);
        // 发送验证码
        return Result.ok(code);
    }

    /**
     * 登录功能
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public String login(LoginFormDTO loginForm, HttpSession session) {
        // 登录还是要校验手机号的正确性
        if(RegexUtils.isPhoneInvalid(loginForm.getPhone())){
            return "ERROR_PHONE";
        }
        // 然后从redis中获取验证码那对比前端的验证码
        // redis中验证码的key是phone    // todo: 为什么
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstant.LOGIN_CODE_KEY + loginForm.getPhone());
        String code = loginForm.getCode();
        // 如果验证码不一致或者redis中的验证码过期，返回错误信息
        if(!code.equals(cacheCode)){
            return "ERROR_CODE";
        }

        // 每次登录都会生成随机token

        // 查询用户是否存在，不存在就创建新用户
        User user = query().eq("phone", loginForm.getPhone()).one();
        if(user == null){
            // 保存的到数据库中
            user = new User();
            user.setPhone(loginForm.getPhone());
            user.setNickName(RedisConstant.USER_PREFIX + RandomUtil.randomString(10));
            save(user);

        }

        // 从redis中查找用户信息，这里应该要用上一个登录状态的token
        //  Map<Object, Object> userRedisInfo = stringRedisTemplate.opsForHash().entries(RedisConstant.LOGIN_USER_KEY + loginForm.getPhone());
        // if(userRedisInfo.isEmpty()){

        // todo ： 这里需要优化——》如果redis中已经有用户信息，那么不需要再新建到redis中了。
        // 由于登录状态的token是随机生成的，所以无法查找redis的用户
        // todo: 能不能有jwt令牌？跟随机token比有什么好处？

        // 将用户信息保存到redis中，保存的是DTO（只用来查找用户的基本信息)
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // redis中的用户数据用Hash来保存，一减省空间，二方便crud     将user对象转为map
        // 而StringRedisTemplate中要求存储的数据都要是String类型，这里要做类型的转换
        String token = UUID.randomUUID().toString(true);  // 使用hutool生成随机字符串token
        Map<String,Object> userMap = BeanUtil.beanToMap(userDTO,  new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)   // 忽略null值
                        // 将字段值转换为String类型
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())
        );
        // redis中user的key是随机的login:token
        stringRedisTemplate.opsForHash().putAll(RedisConstant.LOGIN_USER_KEY + token, userMap);

        // }

        // 设置token过期时间
        stringRedisTemplate.expire(RedisConstant.LOGIN_USER_KEY + token, RedisConstant.LOGIN_USER_TTL, TimeUnit.HOURS);

        // 最后返回登录结果
        return token;
    }

    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();
        // 获取日期
        LocalDateTime now = LocalDateTime.now();
        String dateString = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = "sign:"+userId+":"+dateString;
        // 获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        Long userId = UserHolder.getUser().getId();
        String dateString = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = "sign:"+userId+":"+dateString;
        int dayOfMonth = LocalDateTime.now().getDayOfMonth();

        // 获取本个月所有的签到记录
        // 这个命令可以同时进行多种操作，所以返回结果是List
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        //
        Long num = result.get(0);
        if(num == null || num == 0){
            return Result.ok(0);
        }

        // 遍历循环，获取连续签到天数
        int count = 0;
        while (true){
            if((num&1) == 0){
                break;
            }else{
                count++;
            }
            num >>= 1;
        }
        return Result.ok(count);
    }
}
