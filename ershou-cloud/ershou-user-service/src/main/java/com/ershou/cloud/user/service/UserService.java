package com.ershou.cloud.user.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ershou.cloud.common.exception.BizException;
import com.ershou.cloud.common.result.ResultCode;
import com.ershou.cloud.common.util.PhoneCrypto;
import com.ershou.cloud.user.constant.UserRedisKeys;
import com.ershou.cloud.user.dto.LoginDTO;
import com.ershou.cloud.user.entity.Campus;
import com.ershou.cloud.user.entity.School;
import com.ershou.cloud.user.entity.SysUser;
import com.ershou.cloud.user.mapper.CampusMapper;
import com.ershou.cloud.user.mapper.SchoolMapper;
import com.ershou.cloud.user.mapper.SysUserMapper;
import com.ershou.cloud.user.vo.LoginVO;
import com.ershou.cloud.user.vo.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 认证与用户服务（从单体 `UserService` 裁剪，只保留登录/发码/当前用户三块）。
 *
 * <p>与单体的差异只有两处，都是"微服务化"必须的：
 * <ol>
 *   <li>验证码/发码限流 key 换成 `ershou:user:` 前缀并落 Redis db1（单体是 db0 的 `ershou:sms:`）；
 *   <li>学校/校区名称不再走单体那个 Redis 字典缓存，直接查本服务自己的库
 *       （`ershou_user.school/campus`）——微服务禁止跨服务共享缓存实现。
 * </ol>
 * 登录语义（查/建用户、AES 手机号、封禁判断、`StpUtil.login` 写 db0 会话）与单体保持一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private static final Duration SMS_CODE_TTL = Duration.ofMinutes(5);

    private final SysUserMapper userMapper;
    private final SchoolMapper schoolMapper;
    private final CampusMapper campusMapper;
    private final StringRedisTemplate redis;
    private final PhoneCrypto phoneCrypto;

    /** mock 短信：开发/演示环境固定验证码，不真发短信（与单体同名配置项，走 Nacos） */
    @Value("${ershou.sms.mock:true}")
    private boolean smsMock;

    @Value("${ershou.sms.mock-code:123456}")
    private String mockCode;

    /** 同一手机号每天发码上限（单体是 10） */
    @Value("${ershou.sms.max-per-day:10}")
    private int smsMaxPerDay;

    /** 运营位标记：role_flags & 该值 != 0 即视为运营（单体 AdminProperties.roleFlag 默认 8） */
    @Value("${ershou.admin.role-flag:8}")
    private int adminRoleFlag;

    /* ------------------------------------------------------------------ *
     * 发码
     * ------------------------------------------------------------------ */

    /** 发送验证码：同号每天上限 N 次；验证码落 Redis db1，TTL 5 分钟 */
    public void sendSmsCode(String phone) {
        String limitKey = UserRedisKeys.smsLimit(phone);
        Long count = redis.opsForValue().increment(limitKey);
        if (count != null && count == 1L) {
            redis.expire(limitKey, Duration.ofDays(1));
        }
        if (count != null && count > smsMaxPerDay) {
            throw new BizException(ResultCode.RATE_LIMIT, "今日验证码发送次数已达上限");
        }
        String code = smsMock ? mockCode : String.valueOf(ThreadLocalRandom.current().nextInt(100000, 1000000));
        redis.opsForValue().set(UserRedisKeys.smsCode(phone), code, SMS_CODE_TTL);
        if (smsMock) {
            log.info("[sms][mock] phone={} code={}（开发环境固定验证码，不真发短信）", PhoneCrypto.mask(phone), code);
        } else {
            // TODO 对接短信服务商（演示版不接）
            log.info("[sms][send] phone={}", PhoneCrypto.mask(phone));
        }
    }

    /* ------------------------------------------------------------------ *
     * 登录 / 登出
     * ------------------------------------------------------------------ */

    /**
     * 手机号验证码登录（轻注册）：
     * 校验码（mock 码或 Redis 码）→ 按 phone_hash 查用户，没有就建号 → `StpUtil.login` 写 db0 会话。
     *
     * <p>`StpUtil.login` 写的是 Redis **db0**（与单体 8080 / 网关 8088 同一个 key 空间），
     * 所以这里发的 token 拿去访问旧单体的 `/api/v1/users/me` 也是有效的 —— 这是本轮演示点。
     */
    public LoginVO login(LoginDTO dto) {
        String cached = redis.opsForValue().get(UserRedisKeys.smsCode(dto.getPhone()));
        boolean mockHit = smsMock && mockCode.equals(dto.getCode());
        if (!mockHit && (!StringUtils.hasText(cached) || !cached.equals(dto.getCode()))) {
            throw new BizException(ResultCode.SMS_CODE_ERROR);
        }
        redis.delete(UserRedisKeys.smsCode(dto.getPhone()));

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
            log.info("[user][register][{}] 新用户注册（user-service）", user.getId());
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
            log.info("[user][login][{}] 登录成功（user-service）", user.getId());
        }

        StpUtil.login(user.getId());
        String token = StpUtil.getTokenValue();
        return LoginVO.builder().token(token).user(toVO(user, isNew)).build();
    }

    /** 退出登录：删除当前 token 对应会话（与单体共享 db0，所以单体那边同步失效） */
    public void logout() {
        try {
            StpUtil.logout();
        } catch (Exception e) {
            // 网关透传 X-User-Id 直连时可能没有 Authorization 上下文，登出按幂等处理
            log.debug("[user][logout] 无有效登录上下文，按幂等处理：{}", e.getMessage());
        }
    }

    /* ------------------------------------------------------------------ *
     * 当前用户
     * ------------------------------------------------------------------ */

    /** 按 id 查用户并转 VO（供 `/users/me` 用；id 来自网关 X-User-Id） */
    public UserVO currentUser(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ResultCode.NOT_LOGIN);
        }
        return toVO(user, false);
    }

    public SysUser getById(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ResultCode.NOT_LOGIN);
        }
        return user;
    }

    /* ------------------------------------------------------------------ *
     * VO 组装（字段与单体逐项对齐）
     * ------------------------------------------------------------------ */

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
        vo.setShopIntro(user.getShopIntro());
        vo.setIdentityFlag(user.getIdentityFlag());
        vo.setPublishLimit(user.getPublishLimit());
        vo.setChatLimit(user.getChatLimit());
        vo.setNewUser(isNewUser);
        vo.setRoleFlags(user.getRoleFlags());
        vo.setIsAdmin(isAdmin(user));
        vo.setCreatedAt(user.getCreatedAt());

        String phone = null;
        try {
            if (user.getPhone() != null) {
                // 仅用于展示脱敏号，解密失败不影响主流程
                phone = PhoneCrypto.mask(phoneCrypto.decrypt(user.getPhone()));
            }
        } catch (Exception ignored) {
            // 与单体一致：解密失败就不下发手机号
        }
        vo.setPhone(phone);

        if (user.getSchoolId() != null) {
            School school = schoolMapper.selectById(user.getSchoolId());
            vo.setSchoolName(school == null ? null : school.getName());
        }
        if (user.getCampusId() != null) {
            Campus campus = campusMapper.selectById(user.getCampusId());
            vo.setCampusName(campus == null ? null : campus.getName());
        }
        return vo;
    }

    /** 运营判定：role_flags 命中运营位（与单体 AdminAuthService 的第二条判定一致） */
    private boolean isAdmin(SysUser user) {
        return user.getRoleFlags() != null && (user.getRoleFlags() & adminRoleFlag) != 0;
    }
}
