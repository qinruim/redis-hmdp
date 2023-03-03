package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.sql.StringEscape;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

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

    /**
     * 添加缓存的店铺分类数据查询（string）
     */
    @Override
    public Result queryShopTypeList() {
        //1.从redis中查询店铺分类数据
        String shopTypeJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);
        //2.若命中则返回店铺分类数据
        if (StrUtil.isNotBlank(shopTypeJson)){
            //注意这个数据是一个list 不是一个bean对象  否则会解析出错
//            ShopType shopType = JSONUtil.toBean(shopTypeJson, ShopType.class);
            List<ShopType> shopTypeList = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(shopTypeList);
        }
        //3.未命中就去数据库查询
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        if (shopTypeList == null){
            //4.数据库中不存在返回错误信息
            return Result.fail("无店铺类别！");
        }
        //5.数据库中存在则存储到redis 并返回给客户端
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_KEY,JSONUtil.toJsonStr(shopTypeList));
        return Result.ok(shopTypeList);
    }

    /**
     * 添加缓存的店铺分类数据查询（List）
     * redis读写是json 数据库读写是bean 返回给客户端是bean
     */
//    @Override
//    public Result queryShopTypeList() {
//        //1.从redis中查询店铺分类数据(得到一个json集合)
//        List<String> shopTypeJsonList = stringRedisTemplate.opsForList().range(CACHE_SHOP_TYPE_KEY, 0, -1);
//        if (CollectionUtil.isNotEmpty(shopTypeJsonList)){
//            //2.集合不为空则命中,将json集合转为bean集合，返回给客户端
////            List<ShopType> shopTypeBeanList = new ArrayList<>();
////            for (String shopType : shopTypeJsonList) {
////                ShopType shopTypeBean = JSONUtil.toBean(shopType, ShopType.class);
////                shopTypeBeanList.add(shopTypeBean);
////            }
//            List<ShopType> shopTypeBeanList = shopTypeJsonList.stream()
//                    .map(item->JSONUtil.toBean(item,ShopType.class))
//                    .sorted(Comparator.comparingInt(ShopType::getSort))
//                    .collect(Collectors.toList());
//
//            return Result.ok(shopTypeBeanList);
//        }
//        //3.未命中就去数据库查询(得到bean集合)
//        List<ShopType> shopTypes = lambdaQuery().orderByAsc(ShopType::getSort).list();
//        if (CollectionUtil.isEmpty(shopTypes)){
//            //数据库中不存在 存入一个空的集合 防止缓存穿透
//            stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_KEY, Collections.emptyList().toString());
//            return Result.fail("不存在店铺分类");
//        }
//        //5.数据库中存在,则存储到redis(把bean集合转成json集合) 再返回给客户端
////        List<String> shopTypeCache = new ArrayList<>();
////        for (ShopType shopType : shopTypes) {
////            String shopTypeJsonStr = JSONUtil.toJsonStr(shopType);
////            shopTypeCache.add(shopTypeJsonStr);
////        }
//        List<String> shopTypeCache = shopTypes.stream()
//                        .sorted(Comparator.comparingInt(ShopType::getSort))
//                        .map(JSONUtil::toJsonStr)
//                        .collect(Collectors.toList());
//        //只能用右插入保证顺序
//        stringRedisTemplate.opsForList().rightPushAll(CACHE_SHOP_TYPE_KEY,shopTypeCache);
//        return Result.ok(shopTypes);
//    }
}
