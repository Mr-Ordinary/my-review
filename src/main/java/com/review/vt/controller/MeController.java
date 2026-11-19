package com.review.vt.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.review.vt.pojo.dto.*;

import com.review.vt.pojo.param.*;

import com.review.vt.auth.AuthContext;
import com.review.vt.pojo.dto.UserCache;
import com.review.vt.pojo.entity.User;
import com.review.vt.mapper.UserMapper;
import com.review.vt.service.ProfileSummaryService;
import com.review.vt.service.UserCacheService;
import com.review.vt.service.UserLlmService;
import lombok.AllArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import com.review.vt.web.ex.NotFoundException;

/**
 * 个人中心接口：昵称 / 头像 / API Key（加密，只回显掩码）/ 求职总结。
 * 全部操作当前登录用户自己的数据（AuthContext）。
 */
@Tag(name = "个人中心", description = "资料 / 头像 / 求职总结 / API 配置与共享")
@RestController
@RequestMapping("/api")
public class MeController {

    /** 头像允许的扩展名（内容不重编码，仅限图片扩展） */
    private static final Set<String> AVATAR_EXTS = Set.of("png", "jpg", "jpeg", "webp", "gif");
    private static final long AVATAR_MAX_BYTES = 2L * 1024 * 1024;

    private final UserMapper userMapper;
    private final UserLlmService userLlmService;
    private final com.review.vt.service.ApiConfigService apiConfigService;
    private final ProfileSummaryService profileSummaryService;
    private final UserCacheService userCache;
    private final Path avatarDir;

    public MeController(UserMapper userMapper, UserLlmService userLlmService,
                        com.review.vt.service.ApiConfigService apiConfigService,
                        ProfileSummaryService profileSummaryService,
                        UserCacheService userCache,
                        @Value("${myreview.data-dir:E:/m_review}") String dataDir) {
        this.userMapper = userMapper;
        this.userLlmService = userLlmService;
        this.apiConfigService = apiConfigService;
        this.profileSummaryService = profileSummaryService;
        this.userCache = userCache;
        this.avatarDir = Path.of(dataDir, "avatars");
    }


    /** 当前用户信息（与 /api/auth/me 同构）；走 Redis 快照，零 SQL */
    @Operation(summary = "个人资料", description = "昵称/头像/求职总结状态")
    @GetMapping("/me")
    public AuthUser me() {
        return userCache.currentCached().toAuthUser();
    }

    /** 改昵称（3-24 字，不允许只填空白）；两步同步缓存：修改前删 + 修改后写 */
    @Operation(summary = "修改昵称", description = "1-24 字")
    @PostMapping("/me/profile")
    public AuthUser updateNickName(@RequestBody ProfileParam req) {
        User u = loadDbUser();
        String nickname = req.nickname() == null ? "" : req.nickname().trim();
        if (nickname.length() < 1 || nickname.length() > 24) {
            throw new IllegalArgumentException("昵称长度需在 1-24 字之间");
        }
        u.setNickname(nickname);
        u.setUpdatedAt(System.currentTimeMillis());
        try (UserCacheService.UpdatePlan plan = userCache.evictForUpdate(u.getId())) {
            try {
                userMapper.update(u);
            } catch (RuntimeException e) {
                try { userCache.writeAfterReload(plan); } catch (Exception ignored) { /* 尽力恢复会话 */ }
                throw e;
            }
            userCache.writeAfter(plan, u);
        }
        return dto(u);
    }

    /** 上传头像（multipart，≤2MB，png/jpg/jpeg/webp/gif）；旧头像文件被替换 */
    @Operation(summary = "上传头像", description = "multipart 上传，前端裁 512×512 后提交")
    @PostMapping("/me/avatar")
    public AuthUser uploadAvatar(@RequestParam("file") MultipartFile file) {
        User u = loadDbUser();
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("没有收到图片内容");
        if (file.getSize() > AVATAR_MAX_BYTES) throw new IllegalArgumentException("头像不能超过 2MB");

        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        int dot = name.lastIndexOf('.');
        String ext = (dot >= 0 ? name.substring(dot + 1) : "").toLowerCase();
        if (!AVATAR_EXTS.contains(ext)) {
            throw new IllegalArgumentException("头像只支持 png / jpg / jpeg / webp / gif");
        }
        try {
            Files.createDirectories(avatarDir);
            // 先删旧头像（扩展名可能变了），再写新文件
            if (u.getAvatarExt() != null) Files.deleteIfExists(avatarPath(u.getId(), u.getAvatarExt()));
            Files.copy(file.getInputStream(), avatarPath(u.getId(), ext), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("保存头像失败：" + e.getMessage(), e);
        }
        u.setAvatarExt(ext);
        u.setUpdatedAt(System.currentTimeMillis());
        try (UserCacheService.UpdatePlan plan = userCache.evictForUpdate(u.getId())) {
            try {
                userMapper.update(u);
            } catch (RuntimeException e) {
                try { userCache.writeAfterReload(plan); } catch (Exception ignored) { /* 尽力恢复会话 */ }
                throw e;
            }
            userCache.writeAfter(plan, u);
        }
        return dto(u);
    }

    /** 读取用户头像（仅登录后可见；无头像 404）；缓存反查 + DB 只读兜底 */
    @Operation(summary = "读取头像", description = "无头像返回 404")
    @GetMapping("/avatars/{userId}")
    public ResponseEntity<byte[]> avatar(@Parameter(description = "用户 ID") @PathVariable Long userId) {
        UserCache u = userCache.loadForRead(userId).orElse(null);
        if (u == null || u.avatarExt() == null) return ResponseEntity.notFound().build();
        Path file = avatarPath(userId, u.avatarExt());
        if (!Files.isRegularFile(file)) return ResponseEntity.notFound().build();
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentTypeOf(u.avatarExt())))
                    .body(Files.readAllBytes(file));
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /* ---------- API 配置：多套 + 启用 + 共享 ---------- */

    /** 配置列表：我的全部 + 共享给我的（每条带来源标注与启用状态） */
    @Operation(summary = "API 配置列表", description = "自己的全部配置 + 共享给我的（带来源标注与启用状态）")
    @GetMapping("/me/llm-configs")
    public java.util.List<Map<String, Object>> llmConfigs() {
        return apiConfigService.list(AuthContext.require().userId());
    }

    /** 新建配置（自己的） */
    @Operation(summary = "新建 API 配置", description = "密钥加密保存，只回显末四位")
    @PostMapping("/me/llm-configs")
    public Map<String, Object> createLlmConfig(@RequestBody ApiConfigParam req) {
        return apiConfigService.create(AuthContext.require().userId(), req);
    }

    /** 更新自己的配置（clearKey=true 显式清 key，否则留空/掩码不动密文） */
    @Operation(summary = "更新 API 配置", description = "clearKey=true 清除 Key；apiKey 留空/含 * 不动密文")
    @PostMapping("/me/llm-configs/{id}/update")
    public Map<String, Object> updateLlmConfig(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id, @RequestBody ApiConfigParam req) {
        return apiConfigService.update(AuthContext.require().userId(), id, req);
    }

    /** 删除自己的配置（级联清共享；正启用的会回落默认） */
    @Operation(summary = "删除 API 配置", description = "级联清共享；正启用的回落服务端默认")
    @PostMapping("/me/llm-configs/{id}/delete")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void deleteLlmConfig(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id) {
        apiConfigService.delete(AuthContext.require().userId(), id);
    }

    /** 启用某套配置：自己的或共享给我的都行。返回最新列表 */
    @Operation(summary = "启用 API 配置", description = "自己的或共享给我的都可启用")
    @PostMapping("/me/llm-configs/{id}/activate")
    public java.util.List<Map<String, Object>> activateLlmConfig(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id) {
        Long uid = AuthContext.require().userId();
        apiConfigService.activate(uid, id);
        return apiConfigService.list(uid);
    }

    /** 这套配置共享给了谁（仅所有者） */
    @Operation(summary = "共享名单", description = "这套配置共享给了谁（仅所有者）")
    @GetMapping("/me/llm-configs/{id}/shares")
    public java.util.List<Map<String, Object>> configShares(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id) {
        return apiConfigService.sharesOf(AuthContext.require().userId(), id);
    }

    /** 共享给指定用户（仅所有者；目标用户 id 来自选人搜索） */
    @Operation(summary = "共享给用户", description = "对方配置选择里会多一项「由 XXX 共享」")
    @PostMapping("/me/llm-configs/{id}/shares")
    public Map<String, Object> shareConfig(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id, @RequestBody ShareParam req) {
        return apiConfigService.shareTo(AuthContext.require().userId(), id, req.userId());
    }

    /** 取消共享（仅所有者） */
    @Operation(summary = "取消共享", description = "仅所有者可取消")
    @PostMapping("/me/llm-configs/{id}/shares/{targetUserId}/revoke")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void unshareConfig(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id, @Parameter(description = "被共享用户 ID") @PathVariable Long targetUserId) {
        apiConfigService.unshare(AuthContext.require().userId(), id, targetUserId);
    }

    /** 选人搜索（共享弹窗用，按用户名/昵称模糊匹配，不含自己） */
    @Operation(summary = "搜索用户", description = "按用户名/昵称模糊匹配，共享选人用")
    @GetMapping("/me/user-search")
    public java.util.List<Map<String, Object>> userSearch(@Parameter(description = "关键字（用户名/昵称模糊匹配）") @RequestParam("q") String q) {
        return apiConfigService.searchUsers(AuthContext.require().userId(), q);
    }

    /**
     * 求职总结：已缓存内容 + 任务状态。前端据 status 决定显示「生成中 / 已生成 / 失败」，
     * 状态来自数据库，刷新页面不会丢。
     */
    @Operation(summary = "求职总结状态", description = "内容 + 生成任务状态")
    @GetMapping("/me/summary")
    public Map<String, Object> summary() {
        return summaryView(userCache.currentCached());
    }

    /** 启动后台生成，立即返回当前状态；已有任务在跑返回 409，不重复提交 */
    @Operation(summary = "生成求职总结", description = "后台任务，重复提交返回 409")
    @PostMapping("/me/summary")
    public ResponseEntity<Map<String, Object>> generateSummary() {
        UserCache u = userCache.currentCached();
        boolean started = profileSummaryService.start(AuthContext.require(), userLlmService.snapshotFor(u.id()));
        // start 内部已两步同步缓存（状态已变），过滤器快照是旧的 → 直接反查最新值
        Map<String, Object> body = summaryView(userCache.readByUserId(u.id()).orElse(u));
        return started
                ? ResponseEntity.accepted().body(body)
                : ResponseEntity.status(409).body(Map.of("error", "总结正在生成中，请稍候"));
    }

    /* ---------- 内部 ---------- */

    private Map<String, Object> summaryView(UserCache u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", u.summaryStatus() == null ? "idle" : u.summaryStatus());
        m.put("error", u.summaryError());
        m.put("startedAt", u.summaryStartedAt());
        m.put("summary", com.review.vt.common.util.MappingsUtil.parseJson(u.summaryJson()));
        m.put("summaryAt", u.summaryAt());
        return m;
    }

    /** 修改流程里的 DB 读（读改写一体，属「修改用户时查库」）；读路径请走 userCache.currentCached() */
    private User loadDbUser() {
        return userMapper.findById(AuthContext.require().userId())
                .orElseThrow(() -> new com.review.vt.web.ex.NotFoundException("用户不存在"));
    }

    private Path avatarPath(Long userId, String ext) {
        return avatarDir.resolve(userId + "." + ext);
    }

    private static String contentTypeOf(String ext) {
        return switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            default -> "image/png";
        };
    }

    private static AuthUser dto(User u) {
        return UserCache.from(u).toAuthUser();   // 与缓存同构，消重
    }
}
