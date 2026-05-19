package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import cn.hutool.core.util.StrUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone) {
//        判断手机号格式
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
//        生成验证码
        String code = RandomUtil.randomNumbers(6);
//        将验证码存在redis里面
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
//        将验证码短信发送给用户（这里不做处理）
        log.debug("验证码是{}",code);
//        返回
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm) {
        String phone = (String) loginForm.getPhone();
        String code = (String) loginForm.getCode();
        //        判断手机号格式是否正确
        if (RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
//        判断验证码是否正确
        if (code==null||!code.equals(stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone))) {
            return Result.fail("验证码错误");
        }
//        根据手机号查询用户
        User user = (User) query().eq("phone", phone).one();
//        不存在
        if(user==null){
            user = createUserByPhone(phone);
        }
//        创建token
        String uuid = UUID.randomUUID().toString();
//        将user对象转化为UserDTO存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
//        将userDTO转化为hash并存入redis
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) ->
                                fieldValue == null ? null : String.valueOf(fieldValue)));
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY+uuid,userMap);
        stringRedisTemplate.expire(LOGIN_USER_KEY + uuid, LOGIN_USER_TTL, TimeUnit.MINUTES);
        return Result.ok(uuid);
    }

    private User createUserByPhone(String phone) {
        User user=new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomNumbers(10));
        save(user);
        return  user;
    }
}