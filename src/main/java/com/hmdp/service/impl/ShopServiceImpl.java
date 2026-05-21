package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool( 10);


    @Override
    public Result queryById(long id) {
        Shop shop = redisQueryById(id);
//        Shop shop = redisPenetrateQueryById(id);
//        Shop shop = redisLogicExpiredQueryById(id);
        if (shop == null) {
            Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }


    //    使用redis实现缓存
    private Shop redisQueryById(Long id) {
//        从redis里面查询
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//        判断是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
//        未命中，根据id查询数据库
        Shop shop = getById(id);
//        判断商铺是否存在
        if (shop == null) {
            return null;
        }
//        命中，返回商铺信息
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    //    实现处理缓存穿透,防止不存在数据总是打入数据库
    private Shop redisPenetrateQueryById(long id) {
//        从redis获取数据
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//        判断是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
//        命中后判断是否空值
        if (shopJson != null) {
            return null;
        }
//        未命中在数据库查询
        Shop shop = getById(id);
//        不存在将空值写入redis
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
//        存在，写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }


    //    实现处理缓存击穿，防止热点key对数据库造成大量冲击(使用互斥锁)
    private Shop redisBreakThroughQueryById(long id) {
//        从redis获取数据
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//    判断缓存是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
//        命中后判断是否空值
        if (shopJson != null) {
            return null;
        }
        try {
//        尝试获取互斥锁
            if (!Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(LOCK_SHOP_KEY + id, "1", LOCK_SHOP_TTL, TimeUnit.MINUTES))) {
                Thread.sleep(50);
                return redisBreakThroughQueryById(id);
            }
//            二次查询redis
//        从redis获取数据
            shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//    判断缓存是否命中
            if (StrUtil.isNotBlank(shopJson)) {
                return JSONUtil.toBean(shopJson, Shop.class);
            }
//        命中后判断是否空值
            if (shopJson != null) {
                return null;
            }
//        未命中在数据库查询
            Shop shop = getById(id);
//        不存在将空值写入redis
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
//        存在，写入redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return shop;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            stringRedisTemplate.delete(LOCK_SHOP_KEY + id);
        }
    }


    //    实现处理缓存击穿，防止热点key对数据库造成大量冲击(使用逻辑过期方式)
    private Shop redisLogicExpiredQueryById(Long id){
        //        从redis获取数据
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //    判断缓存是否命中
        if (StrUtil.isBlank(shopJson)) {
//            未命中(同时判断完是否存在和是否是空shop)
            return null;
        }
//        判断缓存是否过期
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = (Shop) redisData.getData();
        LocalDateTime expireTime = redisData.getExpireTime();
        if (LocalDateTime.now().isBefore(expireTime)) {
            //        未过期，返回商铺信息
            return shop;
        }
//        过期，尝试获取互斥锁
        if (!Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(LOCK_SHOP_KEY + id, LOCK_VALUE, LOCK_SHOP_TTL, TimeUnit.SECONDS))) {
            //        没有获取互斥锁
            return shop;
        }
//      二次检测，判断缓存是否过期
//        未过期，获取互斥锁，开启独立线程
        try {
            // 重新查询缓存，防止其他线程已经重建了
            shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
            if (StrUtil.isNotBlank(shopJson)) {
                redisData = JSONUtil.toBean(shopJson, RedisData.class);
                if (LocalDateTime.now().isBefore(redisData.getExpireTime())) {
                    return (Shop) redisData.getData();
                }
            }

            // 7. 开启独立线程重建缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    log.error("重建缓存失败, id: {}", id, e);
                } finally {
                    // 释放锁时校验是否是自己加的
                    String currentLockValue = stringRedisTemplate.opsForValue().get(LOCK_SHOP_KEY + id);
                    if (LOCK_VALUE.equals(currentLockValue)) {
                        stringRedisTemplate.delete(LOCK_SHOP_KEY + id);
                    }
                }
            });

            // 8. 返回旧数据
            return shop;

        } catch (Exception e) {
            // 释放锁
            String currentLockValue = stringRedisTemplate.opsForValue().get(LOCK_SHOP_KEY + id);
            if (LOCK_VALUE.equals(currentLockValue)) {
                stringRedisTemplate.delete(LOCK_SHOP_KEY + id);
            }
            throw new RuntimeException(e);
        }
    }


    @Transactional
    @Override
    public Result update(Shop shop) {
        if (shop.getId() == null) {
            return Result.fail("店铺不存在");
        }
//        操作数据库
        updateById(shop);
//        删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }


    public void saveShop2Redis(long id,long expireSeconds){
        Shop shop = getById(id);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }

}
