package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    Result saveBlog(Blog blog);

    Result queryBlogById(Long id);

    void queryUserByBlog(Blog blog);

    Result queryMyBlog(Integer current);

    Result queryHotBlog(Integer current);

    //判断是否点赞
    void isLiked(Blog blogId);

    // 为博客点赞
    Result likeBlog(Long blogId);

    Result queryBlogLikes(Long id);

    Result queryUserBlogById(Long current);
}
