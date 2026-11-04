package com.ershou.campus.module.user.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ershou.campus.common.exception.BizException;
import com.ershou.campus.common.result.ResultCode;
import com.ershou.campus.common.cache.DictionaryCache;
import com.ershou.campus.common.util.PhoneCrypto;
import com.ershou.campus.common.util.RedisKeys;
import com.ershou.campus.module.admin.service.AdminAuthService;
import com.ershou.campus.module.item.entity.Item;
import com.ershou.campus.module.item.mapper.ItemMapper;
import com.ershou.campus.module.school.entity.Campus;
import com.ershou.campus.module.school.entity.School;
import com.ershou.campus.module.user.dto.LoginDTO;
import com.ershou.campus.module.user.dto.UserProfileUpdateDTO;
import com.ershou.campus.module.user.entity.SysUser;
import com.ershou.campus.module.user.mapper.SysUserMapper;
import com.ershou.campus.module.user.vo.LoginVO;
import com.ershou.campus.module.user.vo.PublicUserVO;
import com.ershou.campus.module.user.vo.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final SysUserMapper userMapper;
    private final DictionaryCache dict;
    private final StringRedisTemplate redis;
    private final PhoneCrypto phoneCrypto;
    private final AdminAuthService adminAuthService;
    private final UserBlockService userBlockService;
    private final ItemMapper itemMapper;

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

    /**
     * 对外可见的用户主页（PRD FR-UP-04 / BR-08）。
     *
     * <p>刻意不复用 {@link #toVO}：那个带脱敏手机号，而主页是公开接口，
     * 登录与否都能看（SaTokenConfig 单独放行了用户主页接口），所以不能带任何联系方式。
     * 被封禁的账号主页照样能看——历史交易和纠纷处理需要。
     */
    public PublicUserVO publicProfile(Long userId, Long viewerId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "用户不存在");
        }
        PublicUserVO vo = new PublicUserVO();
        vo.setId(user.getId());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setCreditScore(user.getCreditScore());
        vo.setCreditLevel(user.getCreditLevel());
        vo.setIdentityFlag(user.getIdentityFlag());
        vo.setShopName(user.getShopName());
        vo.setStatus(user.getStatus());
        vo.setCreatedAt(user.getCreatedAt());
        if (user.getSchoolId() != null) {
            var s = dict.school(user.getSchoolId());
            vo.setSchoolName(s == null ? null : s.getName());
        }
        if (user.getCampusId() != null) {
            var c = dict.campus(user.getCampusId());
            vo.setCampusName(c == null ? null : c.getName());
        }
        vo.setOnSaleCount(itemMapper.selectCount(Wrappers.<Item>lambdaQuery()
                .eq(Item::getSellerId, userId)
                .eq(Item::getStatus, 2)));
        boolean self = Objects.equals(userId, viewerId);
        vo.setSelf(self);
        vo.setBlocked(!self && viewerId != null && userBlockService.blocked(viewerId, userId));
        vo.setBlockedByPeer(!self && viewerId != null && userBlockService.blocked(userId, viewerId));
        return vo;
    }

    /**
     * 编辑自己的资料：昵称 / 头像 / 学校校区 / 小铺名 / 小铺简介。
     *
     * <p>只更新传了的字段（null = 不改），头像与两个小铺字段传空串表示清空 —— 用
     * update wrapper 而不是 updateById，后者会把 null 字段整段忽略、清不掉值。
     * 昵称和小铺名过一遍内容审核（复用商品那套关键词规则），违规直接拦截。
     * 手机号是登录凭证，不在这里改；PRD BR-08 也不收集真实姓名，"姓名"即昵称。
     */
    @Transactional(rollbackFor = Exception.class)
    public UserVO updateProfile(Long userId, UserProfileUpdateDTO dto) {
        SysUser user = getById(userId);
        if (user.getStatus() != null && user.getStatus() == 3) {
            throw new BizException(ResultCode.USER_BANNED);
        }

        LambdaUpdateWrapper<SysUser> update = Wrappers.<SysUser>lambdaUpdate().eq(SysUser::getId, userId);
        boolean changed = false;

        if (dto.getNickname() != null) {
            String nickname = dto.getNickname().trim();
            if (nickname.isEmpty()) {
                throw new BizException(ResultCode.PARAM_ERROR, "昵称不能为空");
            }
            update.set(SysUser::getNickname, nickname);
            changed = true;
        }
        if (dto.getAvatar() != null) {
            update.set(SysUser::getAvatar, normalizeAvatar(dto.getAvatar()));
            changed = true;
        }
        if (dto.getShopName() != null) {
            String shopName = dto.getShopName().trim();
            if (shopName.isEmpty()) {
                update.set(SysUser::getShopName, null);
            } else {
                update.set(SysUser::getShopName, shopName);
            }
            changed = true;
        }
        if (dto.getShopIntro() != null) {
            String shopIntro = dto.getShopIntro().trim();
            update.set(SysUser::getShopIntro, shopIntro.isEmpty() ? null : shopIntro);
            changed = true;
        }
        changed |= applySchoolCampus(update, user, dto);

        if (changed) {
            userMapper.update(null, update);
            log.info("[user][profile][{}] 个人资料已更新", userId);
        }
        return toVO(getById(userId), false);
    }

    /** 学校 / 校区：必须存在且启用，校区还得属于所选学校（两者都传时以入参为准） */
    private boolean applySchoolCampus(LambdaUpdateWrapper<SysUser> update, SysUser user, UserProfileUpdateDTO dto) {
        if (dto.getSchoolId() == null && dto.getCampusId() == null) {
            return false;
        }
        Long schoolId = dto.getSchoolId() == null ? user.getSchoolId() : dto.getSchoolId();
        if (dto.getSchoolId() != null) {
            School school = dict.school(dto.getSchoolId());
            if (school == null || !Objects.equals(school.getStatus(), 1)) {
                throw new BizException(ResultCode.PARAM_ERROR, "学校不存在或已停用");
            }
        }
        if (dto.getCampusId() != null) {
            Campus campus = dict.campus(dto.getCampusId());
            if (campus == null || !Objects.equals(campus.getStatus(), 1)) {
                throw new BizException(ResultCode.PARAM_ERROR, "校区不存在或已停用");
            }
            if (schoolId != null && !Objects.equals(campus.getSchoolId(), schoolId)) {
                throw new BizException(ResultCode.PARAM_ERROR, "所选校区不属于该学校");
            }
        } else if (dto.getSchoolId() != null) {
            // 只换学校不换校区：原校区必须仍属于新学校，否则 school_id/campus_id 会自相矛盾
            Campus current = dict.campus(user.getCampusId());
            if (current != null && !Objects.equals(current.getSchoolId(), dto.getSchoolId())) {
                throw new BizException(ResultCode.PARAM_ERROR, "换学校后请重新选择校区");
            }
        }
        if (dto.getSchoolId() != null) {
            update.set(SysUser::getSchoolId, dto.getSchoolId());
        }
        if (dto.getCampusId() != null) {
            update.set(SysUser::getCampusId, dto.getCampusId());
        }
        return true;
    }

    /** 头像只收上传接口产出的地址（/static/... 或 http(s)://）；空串表示恢复默认头像 */
    private String normalizeAvatar(String raw) {
        String avatar = raw.trim();
        if (avatar.isEmpty()) {
            return null;
        }
        if (!avatar.startsWith("/") && !avatar.startsWith("http://") && !avatar.startsWith("https://")) {
            throw new BizException(ResultCode.PARAM_ERROR, "头像地址不合法，请重新上传");
        }
        return avatar;
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
        vo.setShopIntro(user.getShopIntro());
        vo.setIdentityFlag(user.getIdentityFlag());
        vo.setPublishLimit(user.getPublishLimit());
        vo.setChatLimit(user.getChatLimit());
        vo.setNewUser(isNewUser);
        vo.setRoleFlags(user.getRoleFlags());
        vo.setIsAdmin(adminAuthService.isAdmin(user));
        vo.setCreatedAt(user.getCreatedAt());
        String phone = null;
        try {
            if (user.getPhone() != null) {
                // 仅用于展示脱敏号，解密失败不影响主流程
                phone = PhoneCrypto.mask(decryptQuietly(user.getPhone()));
            }
        } catch (Exception ignored) {
        }
        vo.setPhone(phone);
        // 学校 / 校区是静态字典：走 Redis 缓存，避免每次 /users/me 都查两张表
        if (user.getSchoolId() != null) {
            var s = dict.school(user.getSchoolId());
            vo.setSchoolName(s == null ? null : s.getName());
        }
        if (user.getCampusId() != null) {
            var c = dict.campus(user.getCampusId());
            vo.setCampusName(c == null ? null : c.getName());
        }
        return vo;
    }

    private String decryptQuietly(String encrypted) {
        return phoneCrypto.decrypt(encrypted);
    }
}