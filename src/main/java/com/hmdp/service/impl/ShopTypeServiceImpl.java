package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_STATUS_KEY;

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
    StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ShopType> getAll() {
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(SHOP_STATUS_KEY);
        if(entries!=null && !entries.isEmpty()){//Redis缓存命中
            List<ShopType> list = entries.values().stream()
                    .map(value-> JSONUtil.toBean((String) value,ShopType.class))
                    .sorted(Comparator.comparingInt(ShopType::getSort))
                    .collect(Collectors.toList());
            return list;
        }
        //查询数据库
        List<ShopType> list = query().orderByAsc("sort").list();
        // 5. 判空：数据库也没有直接返回错误或空列表
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, String> map = new HashMap<>();
        for (ShopType type : list) {
            map.put(type.getId().toString(), JSONUtil.toJsonStr(type));
        }

        stringRedisTemplate.delete(SHOP_STATUS_KEY);
        stringRedisTemplate.opsForHash().putAll(SHOP_STATUS_KEY, map);
        // 建议设置过期时间
        stringRedisTemplate.expire(SHOP_STATUS_KEY, RedisConstants.SHOP_STATUS_TTL, TimeUnit.MINUTES);

        // 7. 返回结果
        return list;


    }
}
