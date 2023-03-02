package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        String shopKey = CACHE_SHOP_KEY+id;
        //1.从redis查询商铺
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        //2.判断缓存是否命中
        if (StrUtil.isNotBlank(shopJson)){
            //3.命中则返回商铺信息（将json转为java对象返回）
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //4.未命中根据id查询数据库
        Shop shop = getById(id);
        //5.判断数据库中是否存在该商铺
        if (shop == null){
            //6.不存在则返回错误
            return Result.fail("店铺不存在！");
        }
        //7.存在则将商铺数据写入redis，再从redis返回给客户端
        stringRedisTemplate.opsForValue().set(shopKey,JSONUtil.toJsonStr(shop));
        return Result.ok(shop);
    }
}
