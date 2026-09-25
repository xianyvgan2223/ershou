package com.ershou.campus.module.user.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ershou.campus.common.exception.BizException;
import com.ershou.campus.common.result.ResultCode;
import com.ershou.campus.common.util.PhoneCrypto;
import com.ershou.campus.common.util.RedisKeys;
import com.ershou.campus.module.admin.service.AdminAuthService;
import com.ershou.campus.module.school.entity.Campus;
import com.ershou.campus.module.school.entity.School;
import com.ershou.campus.module.school.mapper.CampusMapper;
import com.ershou.campus.module.school.mapper.SchoolMapper;
import com.ershou.campus.module.user.dto.LoginDTO;
import com.ershou.campus.module.user.entity.SysUser;
import com.ershou.campus.module.user.mapper.SysUserMapper;
import com.ershou.campus.module.user.vo.LoginVO;
import com.ershou.campus.module.user.vo.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final SysUserMapper userMapper;
    private final SchoolMapper schoolMapper;
    private final CampusMapper campusMapper;
    private final StringRedisTemplate redis;
    private final PhoneCrypto phoneCrypto;
    private final AdminAuthService adminAuthService;

    @Value("${ershou.sms.mock:true}")
    private boolean smsMock;

    @Value("${ershou.sms.mock-code:123456}")
    private String mockCode;

    /** 发送验证码：同号 60 秒一次，每日上限 10 次 */
    public void sendSmsCode(String phone) {
        String limitKey = RedisKeys.smsLimit(phone);
        Long count = redis.opsForValue().increment(limitKey);
        if (count != null && count == 1L) {
            redis.expire(limitKey, Duration.ofDays(1));
        }
        if (count != null && count > 10) {
            throw new BizException(ResultCode.RATE_LIMIT, "今日验证码发送次数已达上限");
        }
        String code = smsMock ? mockCode : String.valueOf(ThreadLocalRandom.current().nextInt(100000, 1000000));
        redis.opsForValue().set(RedisKeys.smsCode(phone), code, Duration.ofMinutes(5));
        if (smsMock) {
            log.info("[sms][mock] phone={} code={}（开发环境固定验证码，不真发短信）", PhoneCrypto.mask(phone), code);
        } else {
            // TODO 对接短信服务商
            log.info("[sms][send] phone={}", PhoneCrypto.mask(phone));
        }
    }

    public LoginVO login(LoginDTO dto) {
        String key = RedisKeys.smsCode(dto.getPhone());
        String cached = redis.opsForValue().get(key);
        if (!StringUtils.hasText(cached) || !cached.equals(dto.getCode())) {
            throw new BizException(ResultCode.SMS_CODE_ERROR);
        }
        redis.delete(key);

        String hash = phoneCrypto.hash(dto.getPhone());
        SysUser user = userMapper.selectOne(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getPhoneHash, hash));
        boolean isNew = false;
        if (user == null) {
            user = new SysUser();
            user.setPhone(phoneCrypto.encrypt(dto.getPhone()));
            user.setPhoneHash(hash);
            user.setNickname("同学" + dto.getPhone().substring(7));
            user.setSchoolId(dto.getSchoolId() == null ? 1L : dto.getSchoolId());
            user.setCampusId(dto.getCampusId() == null ? 1L : dto.getCampusId());
            user.setRoleFlags(1);
            user.setIdentityFlag(0);
            user.setCreditScore(100);
            user.setCreditLevel(1);
            user.setRiskScore(0);
            user.setDeviceId(dto.getDeviceId());
            user.setDeviceAccountCount(1);
            user.setPublishLimit(5);
            user.setChatLimit(20);
            user.setStatus(1);
            userMapper.insert(user);
            isNew = true;
            log.info("[user][register][{}] 新用户注册", user.getId());
        } else {
            if (user.getStatus() != null && user.getStatus() == 3) {
                throw new BizException(ResultCode.USER_BANNED);
            }
            SysUser update = new SysUser();
            update.setId(user.getId());
            update.setLastLoginAt(LocalDateTime.now());
            if (dto.getDeviceId() != null) {
                update.setDeviceId(dto.getDeviceId());
            }
            userMapper.updateById(update);
            log.info("[user][login][{}] 登录成功", user.getId());
        }

        StpUtil.login(user.getId());
        String token = StpUtil.getTokenValue();
        return LoginVO.builder().token(token).user(toVO(user, isNew)).build();
    }

    public SysUser getById(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ResultCode.NOT_LOGIN);
        }
        return user;
    }

    public UserVO currentUser() {
        return toVO(getById(StpUtil.getLoginIdAsLong()), false);
    }

    /** 批量取用户：返回 HashMap，调用方即使拿 null key 取值也不会 NPE */
    public Map<Long, SysUser> mapByIds(Collection<Long> ids) {
        Map<Long, SysUser> map = new HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return map;
        }
        for (SysUser user : userMapper.selectBatchIds(ids)) {
            map.putIfAbsent(user.getId(), user);
        }
        return map;
    }

    public UserVO toVO(SysUser user, boolean isNewUser) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setSchoolId(user.getSchoolId());
        vo.setCampusId(user.getCampusId());
        vo.setCreditScore(user.getCreditScore());
        vo.setCreditLevel(user.getCreditLevel());
        vo.setShopName(user.getShopName());
        vo.setIdentityFlag(user.getIdentityFlag());
        vo.setPublishLimit(user.getPublishLimit());
        vo.setChatLimit(user.getChatLimit());
        vo.setNewUser(isNewUser);
        vo.setRoleFlags(user.getRoleFlags());
        vo.setIsAdmin(adminAuthService.isAdmin(user));
        String phone = null;
        try {
            if (user.getPhone() != null) {
                // 仅用于展示脱敏号，解密失败不影响主流程
                phone = PhoneCrypto.mask(decryptQuietly(user.getPhone()));
            }
        } catch (Exception ignored) {
        }
        vo.setPhone(phone);
        if (user.getSchoolId() != null) {
            School s = schoolMapper.selectById(user.getSchoolId());
            vo.setSchoolName(s == null ? null : s.getName());
        }
        if (user.getCampusId() != null) {
            Campus c = campusMapper.selectById(user.getCampusId());
            vo.setCampusName(c == null ? null : c.getName());
        }
        return vo;
    }

    private String decryptQuietly(String encrypted) {
        return phoneCrypto.decrypt(encrypted);
    }
}