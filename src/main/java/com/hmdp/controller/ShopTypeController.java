package com.hmdp.controller;


import cn.hutool.core.collection.ListUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {
    @Resource
    private IShopTypeService typeService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @GetMapping("list")
    public Result queryTypeList() {
        List<String> list = stringRedisTemplate.opsForList().range(CACHE_SHOP_TYPE_KEY, 0, 0);
        if (list != null && !list.isEmpty()){
            String listStr = list.get(0);
            List<ShopType> shopTypes = JSONUtil.toList(listStr, ShopType.class);
            return Result.ok(shopTypes);
        }
        List<ShopType> typeList = typeService
                .query().orderByAsc("sort").list();
        stringRedisTemplate.opsForList().leftPush(CACHE_SHOP_TYPE_KEY, JSONUtil.toJsonStr(typeList));
        stringRedisTemplate.expire(CACHE_SHOP_TYPE_KEY, CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        return Result.ok(typeList);
    }
}
