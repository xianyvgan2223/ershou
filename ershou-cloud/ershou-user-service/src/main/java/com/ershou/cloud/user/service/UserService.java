package com.ershou.cloud.user.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ershou.cloud.common.exception.BizException;
import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.result.ResultCode;
import com.ershou.cloud.common.util.PhoneCrypto;
import com.ershou.cloud.user.constant.UserRedisKeys;
import com.ershou.cloud.user.dto.LoginDTO;
import com.ershou.cloud.user.dto.UserProfileUpdateDTO;
import com.ershou.cloud.user.dto.UserStatusDTO;
import com.ershou.cloud.user.entity.Campus;
import com.ershou.cloud.user.entity.School;
import com.ershou.cloud.user.entity.SysUser;
import com.ershou.cloud.user.feign.ItemBriefVO;
import com.ershou.cloud.user.feign.ItemPageVO;
import com.ershou.cloud.user.feign.ItemServiceClient;
import com.ershou.cloud.user.mapper.CampusMapper;
import com.ershou.cloud.user.mapper.SchoolMapper;
import com.ershou.cloud.user.mapper.SysUserMapper;
import com.ershou.cloud.user.vo.InnerUserAdminVO;
import com.ershou.cloud.user.vo.InnerUserVO;
import com.ershou.cloud.user.vo.LoginVO;
import com.ershou.cloud.user.vo.PublicUserVO;
import com.ershou.cloud.user.vo.UserStatsVO;
import com.ershou.cloud.user.vo.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 认证与用户服务（T101 全量拆分：从单体 {@code module/user/service/UserService} 迁入全部用户域能力）。
 *
 * <p>与原切片的差异（都是"微服务化"必须的改动，接口路径/入参/响应 JSON 不变）：
 * <ol>
 *   <li>验证码/发码限流 key 用 {@code ershou:user:} 前缀并落 Redis db1（单体是 db0 的 {@code ershou:sms:}）；</li>
 *   <li>学校/校区名不走单体 {@code DictionaryCache}（跨服务共享缓存实现被禁止），直接查本服务库；</li>
 *   <li>公开主页的"在售数"原先是同库 {@code item} 统计，微服务改成 Feign 调 item-service，
 *       商品服务不可用时降级为 0（不阻塞主页）；</li>
 *   <li>运营身份判定先看配置白名单 {@code ershou.admin.user-ids} 再看 {@code role_flags & role-flag}，
 *       与单体 {@code AdminAuthService} 完全一致；运营后台的动作（审计日志/credit_log）
 *       归 admin/order 服务，这里只负责 {@code sys_user} 本库写入。</li>
 * </ol>
 *
 * <p>登录语义（查/建用户、AES 手机号、封禁判断、{@code StpUtil.login} 写 db0 会话）与单体保持一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private static final Duration SMS_CODE_TTL = Duration.ofMinutes(5);

    /** 管理端列表 pageSize 上限（与单体 AdminUserService.MAX_PAGE_SIZE 一致） */
    private static final int MAX_PAGE_SIZE = 100;

    /** 信用分单次调整上限（与单体 AdminUserService.CREDIT_MAX_DELTA 一致） */
    private static final int DEFAULT_CREDIT_MAX_DELTA = 100;

    private static final int STATUS_BANNED = 3;

    private final SysUserMapper userMapper;
    private final SchoolMapper schoolMapper;
    private final CampusMapper campusMapper;
    private final StringRedisTemplate redis;
    private final PhoneCrypto phoneCrypto;
    private final AdminCheckService adminCheckService;
    private final UserBlockService userBlockService;
    private final ItemServiceClient itemServiceClient;

    /** mock 短信：开发/演示环境固定验证码，不真发短信（与单体同名配置项，走 Nacos） */
    @Value("${ershou.sms.mock:true}")
    private boolean smsMock;

    @Value("${ershou.sms.mock-code:123456}")
    private String mockCode;

    /** 同一手机号每天发码上限（单体是 10） */
    @Value("${ershou.sms.max-per-day:10}")
    private int smsMaxPerDay;

    /** 信用分单次调整范围（默认 ±100） */
    @Value("${ershou.user.credit-max-delta:100}")
    private int creditMaxDelta = DEFAULT_CREDIT_MAX_DELTA;

    /* ------------------------------------------------------------------ *
     * 发码
     * ------------------------------------------------------------------ */

    /** 发送验证码：同号每天上限 N 次；验证码落 Redis db1，TTL 5 分钟（与单体逻辑一致） */
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
     * 手机号验证码登录（轻注册）：校验 Redis 里的验证码 → 按 phone_hash 查用户，没有就建号 →
     * {@code StpUtil.login} 写 db0 会话。
     *
     * <p>注意：这里**必须**走 Redis 校验，不再保留旧切片"提交 mock 码即无条件通过"的分支 ——
     * 全量拆分后本服务是唯一线上入口，行为必须与单体一致（单体也是先发码再登录）。
     * 演示环境仍然是"发码后固定 123456"，所以演示脚本不受影响。
     */
    public LoginVO login(LoginDTO dto) {
        String key = UserRedisKeys.smsCode(dto.getPhone());
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
            log.info("[user][register][{}] 新用户注册（user-service）", user.getId());
        } else {
            if (user.getStatus() != null && user.getStatus() == STATUS_BANNED) {
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
     * 当前用户 / 公开主页 / 资料编辑
     * ------------------------------------------------------------------ */

    /** 按 id 查用户并转 VO（供 `/users/me` 用；id 来自网关 X-User-Id 或直连时的 Sa-Token） */
    public UserVO currentUser(Long userId) {
        return toVO(requireLoginUser(userId), false);
    }

    public SysUser getById(Long userId) {
        return requireLoginUser(userId);
    }

    /** 当前用户：不存在视为 10002（与单体 getById 一致） */
    private SysUser requireLoginUser(Long userId) {
        SysUser user = userId == null ? null : userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ResultCode.NOT_LOGIN);
        }
        return user;
    }

    /** 内部/管理端：用户不存在按参数错误（与单体 AdminUserService 一致） */
    private SysUser requireUser(Long userId) {
        SysUser user = userId == null ? null : userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "用户不存在");
        }
        return user;
    }

    /**
     * 对外可见的用户主页（`GET /api/v1/users/{id}/profile`，公开接口）。
     *
     * <p>刻意复用 {@link PublicUserVO}（不含任何联系方式）；被封禁的账号主页照样能看 ——
     * 历史交易和纠纷处理需要。`onSaleCount` 跨服务取 item-service，失败降级 0。
     */
    public PublicUserVO publicProfile(Long userId, Long viewerId) {
        SysUser user = requireUser(userId);
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
        vo.setSchoolName(schoolName(user.getSchoolId()));
        vo.setCampusName(campusName(user.getCampusId()));
        vo.setOnSaleCount(countOnSale(userId));

        boolean self = Objects.equals(userId, viewerId);
        vo.setSelf(self);
        vo.setBlocked(!self && viewerId != null && userBlockService.blocked(viewerId, userId));
        vo.setBlockedByPeer(!self && viewerId != null && userBlockService.blocked(userId, viewerId));
        return vo;
    }

    /**
     * 编辑自己的资料（`PATCH /api/v1/users/me`）：只更新传了的字段（null = 不改），
     * 头像 / 小铺名 / 小铺简介传空串表示清空。手机号是登录凭证，不在这里改。
     */
    @Transactional(rollbackFor = Exception.class)
    public UserVO updateProfile(Long userId, UserProfileUpdateDTO dto) {
        SysUser user = requireLoginUser(userId);
        if (user.getStatus() != null && user.getStatus() == STATUS_BANNED) {
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
            update.set(SysUser::getShopName, shopName.isEmpty() ? null : shopName);
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
            log.info("[user][profile][{}] 个人资料已更新（user-service）", userId);
        }
        return toVO(requireLoginUser(userId), false);
    }

    /** 学校/校区：必须存在且启用，校区还得属于所选学校（两者都传时以入参为准） */
    private boolean applySchoolCampus(LambdaUpdateWrapper<SysUser> update, SysUser user, UserProfileUpdateDTO dto) {
        if (dto.getSchoolId() == null && dto.getCampusId() == null) {
            return false;
        }
        Long schoolId = dto.getSchoolId() == null ? user.getSchoolId() : dto.getSchoolId();
        if (dto.getSchoolId() != null) {
            School school = schoolMapper.selectById(dto.getSchoolId());
            if (school == null || !Objects.equals(school.getStatus(), 1)) {
                throw new BizException(ResultCode.PARAM_ERROR, "学校不存在或已停用");
            }
        }
        if (dto.getCampusId() != null) {
            Campus campus = campusMapper.selectById(dto.getCampusId());
            if (campus == null || !Objects.equals(campus.getStatus(), 1)) {
                throw new BizException(ResultCode.PARAM_ERROR, "校区不存在或已停用");
            }
            if (schoolId != null && !Objects.equals(campus.getSchoolId(), schoolId)) {
                throw new BizException(ResultCode.PARAM_ERROR, "所选校区不属于该学校");
            }
        } else if (dto.getSchoolId() != null) {
            // 只换学校不换校区：原校区必须仍属于新学校，否则 school_id/campus_id 会自相矛盾
            Campus current = user.getCampusId() == null ? null : campusMapper.selectById(user.getCampusId());
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

    /* ------------------------------------------------------------------ *
     * 内部接口：其他服务读用户
     * ------------------------------------------------------------------ */

    /** `GET /inner/users/{id}` */
    public InnerUserVO innerUser(Long userId) {
        SysUser user = requireUser(userId);
        return toInnerUserVO(user, schoolName(user.getSchoolId()), campusName(user.getCampusId()));
    }

    /** `GET /inner/users/batch?ids=`：按入参顺序返回存在的用户 */
    public List<InnerUserVO> innerUsers(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return List.of();
        }
        Map<Long, SysUser> users = new HashMap<>();
        for (SysUser user : userMapper.selectBatchIds(distinct)) {
            users.put(user.getId(), user);
        }
        if (users.isEmpty()) {
            return List.of();
        }
        Map<Long, String> schoolNames = batchNames(
                users.values().stream().map(SysUser::getSchoolId).filter(Objects::nonNull).collect(Collectors.toSet()),
                id -> schoolName(id));
        Map<Long, String> campusNames = batchNames(
                users.values().stream().map(SysUser::getCampusId).filter(Objects::nonNull).collect(Collectors.toSet()),
                id -> campusName(id));
        List<InnerUserVO> result = new ArrayList<>(distinct.size());
        for (Long id : distinct) {
            SysUser user = users.get(id);
            if (user != null) {
                result.add(toInnerUserVO(user, schoolNames.get(user.getSchoolId()), campusNames.get(user.getCampusId())));
            }
        }
        return result;
    }

    /** `GET /inner/users`：管理端用户列表（支持手机号精确 / 昵称小铺名模糊） */
    public PageResult<InnerUserAdminVO> adminPage(String keyword, Integer status, long pageNum, long pageSize) {
        long num = Math.max(pageNum, 1);
        long size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        LambdaQueryWrapper<SysUser> w = Wrappers.<SysUser>lambdaQuery();
        if (status != null) {
            w.eq(SysUser::getStatus, status);
        }
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            if (kw.matches("^1\\d{10}$")) {
                // 手机号密文存储，按 SHA256 精确命中（与单体 AdminUserService.page 一致）
                w.eq(SysUser::getPhoneHash, phoneCrypto.hash(kw));
            } else {
                w.and(x -> x.like(SysUser::getNickname, kw).or().like(SysUser::getShopName, kw));
            }
        }
        w.orderByDesc(SysUser::getId);
        Page<SysUser> page = userMapper.selectPage(new Page<>(num, size), w);
        List<SysUser> records = page.getRecords();
        Map<Long, String> schoolNames = batchNames(
                records.stream().map(SysUser::getSchoolId).filter(Objects::nonNull).collect(Collectors.toSet()),
                this::schoolName);
        Map<Long, String> campusNames = batchNames(
                records.stream().map(SysUser::getCampusId).filter(Objects::nonNull).collect(Collectors.toSet()),
                this::campusName);
        return PageResult.of(records.stream()
                        .map(u -> toAdminVO(u, schoolNames.get(u.getSchoolId()), campusNames.get(u.getCampusId())))
                        .toList(),
                page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** `GET /inner/users/stats` → `{total,banned,newToday}` */
    public UserStatsVO stats() {
        long total = count(Wrappers.<SysUser>lambdaQuery());
        long banned = count(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getStatus, STATUS_BANNED));
        long newToday = count(Wrappers.<SysUser>lambdaQuery()
                .ge(SysUser::getCreatedAt, LocalDate.now().atStartOfDay()));
        UserStatsVO vo = new UserStatsVO();
        vo.setTotal(total);
        vo.setBanned(banned);
        vo.setNewToday(newToday);
        return vo;
    }

    private long count(LambdaQueryWrapper<SysUser> wrapper) {
        Long value = userMapper.selectCount(wrapper);
        return value == null ? 0L : value;
    }

    /** `POST /inner/users/{id}/status`：封禁 / 解封（status 1/2/3，可带 banDays） */
    @Transactional(rollbackFor = Exception.class)
    public InnerUserVO updateStatus(Long userId, UserStatusDTO dto) {
        SysUser before = requireUser(userId);
        Integer status = dto == null ? null : dto.getStatus();
        if (status == null || status < 1 || status > 3) {
            throw new BizException(ResultCode.PARAM_ERROR, "用户状态只能是 1 正常 / 2 限权 / 3 封禁");
        }
        LocalDateTime banUntil = null;
        if (status == STATUS_BANNED && dto.getBanDays() != null && dto.getBanDays() > 0) {
            banUntil = LocalDateTime.now().plusDays(dto.getBanDays());
        }
        // banUntil 需要能被显式置空，用 update wrapper 而不是 updateById（null 字段会被忽略）
        userMapper.update(null, Wrappers.<SysUser>lambdaUpdate()
                .eq(SysUser::getId, userId)
                .set(SysUser::getStatus, status)
                .set(SysUser::getBanUntil, banUntil));
        log.info("[inner][user][{}][status {} -> {}][banUntil={}][reason={}]",
                userId, before.getStatus(), status, banUntil, dto.getReason());
        return innerUser(userId);
    }

    /**
     * `POST /inner/users/{id}/credit`：信用分调整（order-service 完成面交 +2 / admin-service 人工调整）。
     *
     * <p>逻辑与单体 `AdminUserService.adjustCredit` 一致：delta ∈ [-100,100] 且 ≠0；
     * 新分 = max(0, 旧分 + delta)，并按 95/80/60 重算 credit_level。
     * 只写本库 `sys_user`；`credit_log` 属 `ershou_order` 库，由调用方落库。
     *
     * @return 受影响行数（1=成功；用户不存在抛 10001）
     */
    @Transactional(rollbackFor = Exception.class)
    public int adjustCredit(Long userId, Integer delta, String reason) {
        SysUser before = requireUser(userId);
        int max = Math.max(creditMaxDelta, 1);
        if (delta == null || delta == 0 || Math.abs(delta) > max) {
            throw new BizException(ResultCode.PARAM_ERROR, "单次调整范围为 -" + max + " ~ +" + max + "，且不能为 0");
        }
        int beforeScore = before.getCreditScore() == null ? 100 : before.getCreditScore();
        int after = Math.max(0, beforeScore + delta);
        int level = creditLevelOf(after);
        int rows = userMapper.setCredit(userId, after, level);
        log.info("[inner][user][{}][credit {} -> {}][level={}][reason={}]", userId, beforeScore, after, level, reason);
        return rows;
    }

    /* ------------------------------------------------------------------ *
     * VO 组装
     * ------------------------------------------------------------------ */

    /** 当前用户视图（`/users/me`、登录响应）——字段与单体 UserVO 逐个对齐 */
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
        vo.setIsAdmin(adminCheckService.isAdmin(user));
        vo.setCreatedAt(user.getCreatedAt());
        vo.setPhone(maskedPhone(user));
        vo.setSchoolName(schoolName(user.getSchoolId()));
        vo.setCampusName(campusName(user.getCampusId()));
        return vo;
    }

    /** 内部用户视图（`/inner/users/{id}`、`/batch`） */
    private InnerUserVO toInnerUserVO(SysUser user, String schoolName, String campusName) {
        InnerUserVO vo = new InnerUserVO();
        vo.setId(user.getId());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setSchoolId(user.getSchoolId());
        vo.setSchoolName(schoolName);
        vo.setCampusId(user.getCampusId());
        vo.setCampusName(campusName);
        vo.setCreditScore(user.getCreditScore());
        vo.setCreditLevel(user.getCreditLevel());
        vo.setShopName(user.getShopName());
        vo.setShopIntro(user.getShopIntro());
        vo.setIdentityFlag(user.getIdentityFlag());
        vo.setPublishLimit(user.getPublishLimit());
        vo.setChatLimit(user.getChatLimit());
        vo.setRoleFlags(user.getRoleFlags());
        vo.setIsAdmin(adminCheckService.isAdmin(user));
        vo.setStatus(user.getStatus());
        vo.setCreatedAt(user.getCreatedAt());
        return vo;
    }

    /** 管理端用户列表行（手机号脱敏，与单体 AdminUserVO 对齐） */
    private InnerUserAdminVO toAdminVO(SysUser user, String schoolName, String campusName) {
        InnerUserAdminVO vo = new InnerUserAdminVO();
        vo.setId(user.getId());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setPhone(maskedPhone(user));
        vo.setSchoolId(user.getSchoolId());
        vo.setSchoolName(schoolName);
        vo.setCampusId(user.getCampusId());
        vo.setCampusName(campusName);
        vo.setShopName(user.getShopName());
        vo.setRoleFlags(user.getRoleFlags());
        vo.setIdentityFlag(user.getIdentityFlag());
        vo.setCreditScore(user.getCreditScore());
        vo.setCreditLevel(user.getCreditLevel());
        vo.setCreditLevelText(creditLevelText(user.getCreditLevel()));
        vo.setRiskScore(user.getRiskScore());
        vo.setStatus(user.getStatus());
        vo.setStatusText(statusText(user.getStatus()));
        vo.setBanUntil(user.getBanUntil());
        vo.setPublishLimit(user.getPublishLimit());
        vo.setChatLimit(user.getChatLimit());
        vo.setDeviceId(user.getDeviceId());
        vo.setDeviceAccountCount(user.getDeviceAccountCount());
        vo.setLastLoginAt(user.getLastLoginAt());
        vo.setCreatedAt(user.getCreatedAt());
        return vo;
    }

    /** 手机号脱敏（解密失败不下发，与单体一致） */
    private String maskedPhone(SysUser user) {
        try {
            if (user.getPhone() != null) {
                return PhoneCrypto.mask(phoneCrypto.decrypt(user.getPhone()));
            }
        } catch (Exception ignored) {
            // 解密失败就不下发手机号
        }
        return null;
    }

    public static int creditLevelOf(int score) {
        if (score >= 95) {
            return 1;
        }
        if (score >= 80) {
            return 2;
        }
        return score >= 60 ? 3 : 4;
    }

    /** 信用等级文案（与单体 AdminViewAssembler.creditLevelText 一致） */
    public static String creditLevelText(Integer level) {
        if (level == null) {
            return "";
        }
        return switch (level) {
            case 1 -> "优秀";
            case 2 -> "良好";
            case 3 -> "一般";
            case 4 -> "风险";
            default -> "未知";
        };
    }

    /** 用户状态文案（与单体 AdminViewAssembler.userStatusText 一致） */
    public static String statusText(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case 1 -> "正常";
            case 2 -> "限权";
            case 3 -> "封禁";
            default -> "未知";
        };
    }

    /* ------------------------------------------------------------------ *
     * 学校 / 校区名称 & 跨服务在售数
     * ------------------------------------------------------------------ */

    private String schoolName(Long schoolId) {
        if (schoolId == null) {
            return null;
        }
        School school = schoolMapper.selectById(schoolId);
        return school == null ? null : school.getName();
    }

    private String campusName(Long campusId) {
        if (campusId == null) {
            return null;
        }
        Campus campus = campusMapper.selectById(campusId);
        return campus == null ? null : campus.getName();
    }

    /** 批量 id→name（查询一次；缺失的 id 不在 map 里） */
    private Map<Long, String> batchNames(Collection<Long> ids, Function<Long, String> loader) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> map = new LinkedHashMap<>(ids.size());
        for (Long id : ids) {
            String name = loader.apply(id);
            if (name != null) {
                map.put(id, name);
            }
        }
        return map;
    }

    /**
     * 某卖家在售商品数（公开主页用）。跨服务：item-service 公开列表 total。
     *
     * <p>防御：如果 item-service 还没支持 sellerId 过滤（返回的第一条记录卖家不是本人），
     * 说明拿到的是全站总数，宁可降级为 0 也不展示错误数字；服务不可用同样降级 0。
     */
    private long countOnSale(Long sellerId) {
        try {
            Result<ItemPageVO> result = itemServiceClient.page(sellerId, 1, 1);
            ItemPageVO page = result == null ? null : result.getData();
            if (page == null || page.getTotal() <= 0) {
                return 0L;
            }
            List<ItemBriefVO> records = page.getRecords();
            ItemBriefVO first = records == null || records.isEmpty() ? null : records.get(0);
            if (first == null || first.getSeller() == null
                    || !Objects.equals(first.getSeller().getId(), sellerId)) {
                log.warn("[user][profile][{}] item-service 列表疑似未按 sellerId 过滤（total={}），在售数降级为 0", sellerId, page.getTotal());
                return 0L;
            }
            return page.getTotal();
        } catch (Exception e) {
            log.warn("[user][profile][{}] 查在售数失败，降级为 0：{}", sellerId, e.getMessage());
            return 0L;
        }
    }
}
