package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.apache.tomcat.util.buf.StringUtils;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private UserServiceImpl userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean succeed = save(blog);
        if (!succeed) {
            // 返回id
            return Result.ok("保存失败");
        }
        // todo:将博客推送给所有粉丝

        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogById(Long id) {
        // 先查找博客
        Blog blog = getById(id);  // 使用mybatisplus的查询方法
        if (blog == null) {
            return Result.fail("博客不存在");
        }
        // 显示用户
        queryUserByBlog(blog);
        this.isLiked(blog);  // 显示当前博客是否点赞了
        // 返回
        return Result.ok(blog);
    }

    @Override
    public void queryUserByBlog(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setIcon(user.getIcon());
        blog.setName(user.getNickName());
    }

    @Override
    public Result queryMyBlog(Integer current) {
        UserDTO user = UserHolder.getUser();
        Page<Blog> page = this.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = this.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            this.queryUserByBlog( blog);
            this.isLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public void isLiked(Blog blog) {
        UserDTO user = UserHolder.getUser(); //
        if(user == null){  // 未登录，直接返回
            return ;
        }
        String key = "blog:liked:" + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, user.getId().toString());  // 分数(最近时间)
        blog.setIsLike(Objects.nonNull(score));
    }

    @Override
    public Result likeBlog(Long blogId) {
        // 判断用户是否点过赞
        Long userId = UserHolder.getUser().getId(); // 获取登录用户id
        String key = "blog:liked:" + blogId;
        Double isLike = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (isLike != null) {
            // 点过赞，取消点赞
            boolean flag = update()
                    .setSql("liked = liked - 1").eq("id", blogId).update();
            // 先确保数据库更新成功再更新redis
            if(flag){
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        } else {
            // 没有点过赞，点赞
            boolean flag = update()
                    .setSql("liked = liked + 1").eq("id", blogId).update();
            if(flag){
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        }
        return Result.ok();
    }

    // 获取点赞数量
    @Override
    public Result queryBlogLikes(Long id) {
        Blog blog = getById(id);
        // 显示最近五位点赞用户
        String key = "blog:liked:" + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        // 获取这五位用户id，封装为DTO(VO)
        // 用stream流做map映射
        // top5.stream()：将 top5 转换为流。
        //map(Long::valueOf)：将每个字符串元素映射为对应的 Long 值。
        //collect(Collectors.toList())：将映射后的结果收集为一个 List<Long>。
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        // 根据用户id批量查询用户信息，WHERE id IN (5, 1) ORDER BY FIELD(ID, 5, 1);
        // 需要将逗号和id拼接为字符串，然后在mybatis后追加，让其按照指定顺序显示
        // 否者用in来查询，是默认id的自增查询
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id,"+idStr+")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        // this.isLiked(blog);
        return Result.ok(userDTOS);
    }

    @Override
    public Result queryUserBlogById(Long current) {
        User user = userService.getById(current);
        // 表示查询第一页，最大值为maxSize
        Page<Blog> page = query().eq("user_id", current).page(new Page<>(1, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();  // 提取分页结果
        return Result.ok(records);
    }
}
