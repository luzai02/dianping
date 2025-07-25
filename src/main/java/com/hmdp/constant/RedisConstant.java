package com.hmdp.constant;

public class RedisConstant {
    public static final String CODE = "code";

    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final String CACHE_SHOP_KEY = "cache:shop:";
    public static final String CACHE_SHOP_TYPE_KEY = "cache:shop_type:";

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final Long LOGIN_CODE_TTL = 5L;
    public static final Long CACHE_TTL = 30L;
    public static final Long CACHE_NULL_TTL = 2L;
    public static final Long LOGIN_USER_TTL = 24L;
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String ERROR_FORM_PHONE = "手机号格式错误";
    public static final String ERROR_CODE = "验证码错误";

    public static final String USER_PREFIX = "user_";
    public static final String PHONE = "手机号";
    public static final String SECKKILL_VOUCHER = "seckill:voucher:";


    // public static final Long CACHE_NULL_TTL = 2L;



    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
}
