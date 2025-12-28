package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

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
    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id){
        //缓存穿透
//        Shop shop=queryWithPassThrough(id);
        //缓存穿透-利用工具类
        Shop shop=cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class, id2->getById(id2),CACHE_SHOP_TTL,TimeUnit.MINUTES);

        //互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
        //逻辑过期解决缓存击穿
//        Shop shop =queryWithLogicalExpire(id);

        if(shop==null)return Result.fail("店铺不存在！");
        return Result.ok(shop);
    }

    /**
     * 缓存击穿，加互斥锁。
     * @param id
     * @return
     */

    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        String lockKey = LOCK_SHOP_KEY + id;
        //1.从Redis查询(这里保存为字符串，演示字符串如何保存/查询)
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY+id);
        //判断存在
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中的是否是空值
        if(shopJson != null){
            return null;
        }

        //实现缓存重建
        //1、获取互斥锁
        //2、失败：休眠并重试
        //3、成功：根据id查询数据库
            //3.1不存在，返回错误
        //数据写入redis
        //释放互斥锁
        //返回
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //获取锁失败
            if (BooleanUtil.isFalse(isLock)) {
                //睡眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 获取锁成功,  再次检测缓存是否存在!! (Double-Check)
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJson)) {
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            if (shopJson != null) { return null; } // 再次检测空值缓存

            shop = getById(id);
            Thread.sleep(200);//模拟重建延迟
            if (shop == null) {
                //写入空值
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        }
        catch (InterruptedException e){
            throw new RuntimeException(e);
        }
        finally {
            unlock(lockKey);
        }
        return shop;

    }

    private boolean tryLock(String key){
        Boolean flag=stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    //线程池
//    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

//    public Shop queryWithLogicalExpire(Long id){
//        //1.从Redis查询(这里保存为字符串，演示字符串如何保存/查询)
//        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY+id);
//        if(StrUtil.isBlank(shopJson)){//未命中，返回null。（默认命中，因为热点key都是预存的。）
//            return null;
//        }
//        //命中，判断过期时间
//        RedisData redisData = JSONUtil.toBean(shopJson,RedisData.class);
//        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(),Shop.class);
//        LocalDateTime expireTime = redisData.getExpireTime();
//        if(expireTime.isAfter(LocalDateTime.now())){
//            return shop;
//        }
//        //获取锁
//        String lockKey = LOCK_SHOP_KEY + id;
//        boolean isLock = tryLock(lockKey);
//        //获取成功
//        if(isLock){
//            //成功，开启独立线程重建缓存（就是更新）。
//            CACHE_REBUILD_EXECUTOR.submit(()->{
//                try {
//                    this.saveShop2Redis(id, 20L);
//                }catch (Exception e){
//                    throw new RuntimeException(e);
//                }finally {
//                    unlock(lockKey);
//                }
//            });
//            //释放锁
//        }
//
//        //失败就返回shop，成功也在此返回。
//        return shop;
//    }


    /**
     * 缓存穿透（存空值）
     * @param id
     * @return
     */
   /* public Shop queryWithPassThrough(Long id){
        //1.从Redis查询(这里保存为字符串，演示字符串如何保存/查询)
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY+id);
        //判断存在
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中的是否是空值
        if(shopJson != null){
            return null;
        }

        //不存在，查询数据库
        Shop shop = getById(id);
        //mysql数据库内不存在,返回错误404
        if(shop==null){
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }

        //存在，写入Redis并返回
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }
    */


    @Transactional
    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id==null){
            return Result.fail("店铺ID不能为null");
        }
        //1、更新数据库
        updateById(shop);
        //2、删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+shop.getId());
        return Result.ok();
    }

    /**
     * 添加逻辑过期存入redis
     * @param id
     * @param expireSeconds
     */
    public void saveShop2Redis(Long id,Long expireSeconds){
        //查询店铺数据
        Shop shop = getById(id);
        //封装逻辑过期时间
        RedisData redisData= new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }




}
