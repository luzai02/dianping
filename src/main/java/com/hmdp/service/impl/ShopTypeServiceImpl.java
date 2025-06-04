package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.constant.RedisConstant;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 获取所有商铺类型
    @Override
    public List<ShopType> queryTypeList() {
        String key = RedisConstant.CACHE_SHOP_TYPE_KEY;
        // 查找所有商铺类型，也就是0到-1
        String shopTypeJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopTypeJson)){
            // 缓存中有数据，直接返回
            return JSONUtil.toList(shopTypeJson, ShopType.class);
        }
        // 从数据库中查询，按照升序，执行查询并返回列表list
        List<ShopType> list = query().orderByAsc("sort").list();
        // todo:这里需要返回吗？
        if(list == null){
            return list;
        }
        stringRedisTemplate.opsForValue().set(
                key,
                JSONUtil.toJsonStr(list),
                RedisConstant.CACHE_TTL,
                TimeUnit.MINUTES);
        return list;
    }
}
