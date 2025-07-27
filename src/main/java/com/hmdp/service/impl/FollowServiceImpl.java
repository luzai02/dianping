package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import net.bytebuddy.description.type.TypeDescription;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;
    @Override
    public Result isFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();  // 获取当前用户id
        // 查询过当前用户是否关注博客中的用户了
        // todo: 这里count 和 one 有什么区别，mybatis plus 不熟悉
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(count > 0);
    }

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = "blog:follow:" + userId;
        // 关注or取关
        // todo: 这里是为什么是否关注
        if(!isFollow){
            // 在 QueryWrapper 中使用的字段名通常是数据库表中的列名，而不是实体类的属性名。
            // 如果需要使用实体类属性名，可以考虑使用 LambdaQueryWrapper。
            // LambdaQueryWrapper 是 QueryWrapper 的增强版，支持使用实体类的属性引用，避免硬编码字段名
//            remove(new QueryWrapper<Follow>()
//                    .eq("follow_user_id", followUserId)
//                    .eq("user_id", userId));

            /*QueryWrapper 是 MyBatis-Plus 提供的通用条件构造器，其泛型参数用于指定与数据库表对应的实体类类型。
            具体来说：
            泛型 Follow 表示这个 QueryWrapper 将用于构建针对 Follow 实体类相关表的查询条件。
            Follow 实体类通常通过注解（如 @TableName）映射到数据库中的某张表*/

            boolean result = remove(new LambdaQueryWrapper<Follow>()
                    .eq(Follow::getFollowUserId, followUserId)
                    .eq(Follow::getUserId, userId));
            if( result ){
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }else{
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean result =  save(follow);
            if( result ){
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result followCommon(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        String key1 = "blog:follow:" + userId;
        String key2 = "blog:follow:" + followUserId;

        // 取交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if(intersect.isEmpty() || Objects.isNull(intersect)){
            return Result.ok(Collections.emptyList());
        }

        // stream流map映射
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> userDTOList;
        if(ids.isEmpty()){
            userDTOList = Collections.emptyList();
        }else{
            // 封装为DTO(VO)
            // map 是 Java Stream API 中的一个中间操作方法，用于对流中的每个元素进行映射（转换）。它的核心功能是：
            //接收一个函数作为参数，将流中的每个元素通过该函数进行处理，并返回一个新的流。
            userDTOList = userService.listByIds(ids)
                    .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                    .collect(Collectors.toList());
        }

        return Result.ok(userDTOList);
    }
}
