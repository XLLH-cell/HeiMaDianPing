package com.hmdp.utils;

import ch.qos.logback.core.util.TimeUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j

public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;
    public CacheClient(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }
    //
    public void set (String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time ,unit);
    }

    /**
     *
     设置逻辑过期，存入Redis
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData  = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));

    }
    //穿透，数据库不存在数据：插入空值
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time, TimeUnit unit){
        String key = keyPrefix+id;
        //1.从Redis查询
        String json = stringRedisTemplate.opsForValue().get(key);
        //判断存在
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json,type);
        }
        //判断命中的是否是空值
        if(json != null){
            return null;
        }

        //不存在，查询数据库
        R r = dbFallback.apply(id);
        //mysql数据库内不存在,返回错误404
        if(r==null){
            stringRedisTemplate.opsForValue().set(key,"",time,unit);
            return null;
        }

        //存在，写入Redis并返回
        this.set(key,r,time ,unit);
        return r;
    }

    private boolean tryLock(String key){
        Boolean flag=stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //逻辑过期解决缓存击穿
    public <R,ID> R queryWithLogicalExpire(String keyPrefix,ID id, Class<R>type,Function<ID,R> dbFallback,Long time, TimeUnit unit){
        //1.从Redis查询(这里保存为字符串，演示字符串如何保存/查询)
        String key = keyPrefix+id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(json)){//未命中，返回null。（默认命中，因为热点key都是预存的。）
            return null;
        }
        //命中，判断过期时间
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        //提取数据
        R r = JSONUtil.toBean((JSONObject) redisData.getData(),type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //没过期，返回。
        if(expireTime.isAfter(LocalDateTime.now())){
            return r;
        }
        //数据过期
        //获取锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //获取成功
        if(isLock){
            //double check 二次检查
            json = stringRedisTemplate.opsForValue().get(key);
            if(StrUtil.isBlank(json)){//未命中，返回null。（默认命中，因为热点key都是预存的。）
                return null;
            }
            //命中，判断过期时间
            redisData = JSONUtil.toBean(json, RedisData.class);
            //提取数据
            r = JSONUtil.toBean((JSONObject) redisData.getData(),type);
            expireTime = redisData.getExpireTime();
            //没过期，返回。
            if(expireTime.isAfter(LocalDateTime.now())){
                return r;
            }
            //成功，开启独立线程重建缓存（就是更新）。
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //查询数据库
                    R r1 =dbFallback.apply(id);
                    //写入redis
                    this.setWithLogicalExpire(key,r1,time,unit);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    unlock(lockKey);
                }
            });
            //释放锁
        }

        //失败就返回shop，成功也在此返回。
        return r;
    }


}
