package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
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
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private final StringRedisTemplate stringRedisTemplate;

    public UserServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        //符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        //保存验证码到Redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone, code,LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //发送验证码
        log.info("发送验证码：{}",code);
        // 返回OK
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(loginForm.getPhone())){
            //不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        //校验验证码（从redis获得）
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if(cacheCode==null || !cacheCode.equals(code)){
            //不一致报错
            return  Result.fail("验证码错误");
        }
        //一致,根据手机号查询用户
        //MybatisPlus：
        User user = query().eq("phone",phone).one();
        //判断用户是否存在
        if(user==null){
            user = createUserWithPhone(phone);
        }
        // 保存到redis

//        1.生成token-UUID
        String token = UUID.randomUUID().toString(true);

        // 2. 转化为 Hash 存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 使用 CopyOptions 自定义字段编辑，将所有字段值转为 String 类型
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

        //3存储
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY+token,userMap);
        stringRedisTemplate.expire(LOGIN_USER_KEY,LOGIN_USER_TTL,TimeUnit.MINUTES);
        //4返回token
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone){
        //创建用户
        User user= new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
