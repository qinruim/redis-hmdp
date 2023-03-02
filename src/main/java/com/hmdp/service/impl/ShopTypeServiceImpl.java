package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;

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
     * 添加缓存的店铺分类数据查询
     */
    @Override
    public Result queryShopTypeList() {
        //1.从redis中查询店铺分类数据
        String shopTypeJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);
        //2.若命中则返回店铺分类数据
        if (StrUtil.isNotBlank(shopTypeJson)){
            ShopType shopType = JSONUtil.toBean(shopTypeJson, ShopType.class);
            return Result.ok(shopType);
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
}
