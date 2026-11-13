package com.review.vt.web;

import com.review.vt.auth.AuthContext;
import com.review.vt.auth.AuthController.AuthUser;
import com.review.vt.domain.User;
import com.review.vt.repo.UserRepository;
import com.review.vt.service.ProfileSummaryService;
import com.review.vt.service.UserLlmService;
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

/**
 * 个人中心接口：昵称 / 头像 / API Key（加密，只回显掩码）/ 求职总结。
 * 全部操作当前登录用户自己的数据（AuthContext）。
 */
@RestController
@RequestMapping("/api")
public class MeController {

    public record ProfileRequest(String nickname) {}
    /** 一套 API 配置的写入请求。clearKey=true 显式清 key；否则 apiKey 留空/含 '*' 都不动密文 */
    public record ApiConfigRequest(String name, String apiBase, String apiKey, String model,
                                   String apiFormat, Double temperature, Boolean clearKey) {}
    public record ShareRequest(Long userId) {}

    /** 头像允许的扩展名（内容不重编码，仅限图片扩展） */
    private static final Set<String> AVATAR_EXTS = Set.of("png", "jpg", "jpeg", "webp", "gif");
    private static final long AVATAR_MAX_BYTES = 2L * 1024 * 1024;

    private final UserRepository users;
    private final UserLlmService userLlm;
    private final com.review.vt.service.ApiConfigService apiConfigs;
    private final ProfileSummaryService profileSummary;
    private final Path avatarDir;

    public MeController(UserRepository users, UserLlmService userLlm,
                        com.review.vt.service.ApiConfigService apiConfigs,
                        ProfileSummaryService profileSummary,
                        @Value("${myreview.data-dir:E:/m_review}") String dataDir) {
        this.users = users;
        this.userLlm = userLlm;
        this.apiConfigs = apiConfigs;
        this.profileSummary = profileSummary;
        this.avatarDir = Path.of(dataDir, "avatars");
    }

    /** 当前用户信息（与 /api/auth/me 同构） */
    @GetMapping("/me")
    public AuthUser me() {
        return dto(currentUser());
    }

    /** 改昵称（3-24 字，不允许只填空白） */
    @PutMapping("/me/profile")
    public AuthUser updateProfile(@RequestBody ProfileRequest req) {
        User u = currentUser();
        String nickname = req.nickname() == null ? "" : req.nickname().trim();
        if (nickname.length() < 1 || nickname.length() > 24) {
            throw new IllegalArgumentException("昵称长度需在 1-24 字之间");
        }
        u.setNickname(nickname);
        u.setUpdatedAt(System.currentTimeMillis());
        users.update(u);
        return dto(u);
    }

    /** 上传头像（multipart，≤2MB，png/jpg/jpeg/webp/gif）；旧头像文件被替换 */
    @PostMapping("/me/avatar")
    public AuthUser uploadAvatar(@RequestParam("file") MultipartFile file) {
        User u = currentUser();
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
        users.update(u);
        return dto(u);
    }

    /** 读取用户头像（仅登录后可见；无头像 404） */
    @GetMapping("/avatars/{userId}")
    public ResponseEntity<byte[]> avatar(@PathVariable Long userId) {
        User u = users.findById(userId).orElse(null);
        if (u == null || u.getAvatarExt() == null) return ResponseEntity.notFound().build();
        Path file = avatarPath(userId, u.getAvatarExt());
        if (!Files.isRegularFile(file)) return ResponseEntity.notFound().build();
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentTypeOf(u.getAvatarExt())))
                    .body(Files.readAllBytes(file));
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /* ---------- API 配置：多套 + 启用 + 共享 ---------- */

    /** 配置列表：我的全部 + 共享给我的（每条带来源标注与启用状态） */
    @GetMapping("/me/llm-configs")
    public java.util.List<Map<String, Object>> llmConfigs() {
        return apiConfigs.list(AuthContext.require().userId());
    }

    /** 新建配置（自己的） */
    @PostMapping("/me/llm-configs")
    public Map<String, Object> createLlmConfig(@RequestBody ApiConfigRequest req) {
        return apiConfigs.create(AuthContext.require().userId(), req.name(), req.apiBase(),
                req.apiKey(), req.model(), req.apiFormat(), req.temperature());
    }

    /** 更新自己的配置（clearKey=true 显式清 key，否则留空/掩码不动密文） */
    @PutMapping("/me/llm-configs/{id}")
    public Map<String, Object> updateLlmConfig(@PathVariable Long id, @RequestBody ApiConfigRequest req) {
        return apiConfigs.update(AuthContext.require().userId(), id, req.name(), req.apiBase(),
                req.apiKey(), Boolean.TRUE.equals(req.clearKey()), req.model(), req.apiFormat(),
                req.temperature());
    }

    /** 删除自己的配置（级联清共享；正启用的会回落默认） */
    @org.springframework.web.bind.annotation.DeleteMapping("/me/llm-configs/{id}")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void deleteLlmConfig(@PathVariable Long id) {
        apiConfigs.delete(AuthContext.require().userId(), id);
    }

    /** 启用某套配置：自己的或共享给我的都行。返回最新列表 */
    @PostMapping("/me/llm-configs/{id}/activate")
    public java.util.List<Map<String, Object>> activateLlmConfig(@PathVariable Long id) {
        Long uid = AuthContext.require().userId();
        apiConfigs.activate(uid, id);
        return apiConfigs.list(uid);
    }

    /** 这套配置共享给了谁（仅所有者） */
    @GetMapping("/me/llm-configs/{id}/shares")
    public java.util.List<Map<String, Object>> configShares(@PathVariable Long id) {
        return apiConfigs.sharesOf(AuthContext.require().userId(), id);
    }

    /** 共享给指定用户（仅所有者；目标用户 id 来自选人搜索） */
    @PostMapping("/me/llm-configs/{id}/shares")
    public Map<String, Object> shareConfig(@PathVariable Long id, @RequestBody ShareRequest req) {
        return apiConfigs.shareTo(AuthContext.require().userId(), id, req.userId());
    }

    /** 取消共享（仅所有者） */
    @org.springframework.web.bind.annotation.DeleteMapping("/me/llm-configs/{id}/shares/{targetUserId}")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void unshareConfig(@PathVariable Long id, @PathVariable Long targetUserId) {
        apiConfigs.unshare(AuthContext.require().userId(), id, targetUserId);
    }

    /** 选人搜索（共享弹窗用，按用户名/昵称模糊匹配，不含自己） */
    @GetMapping("/me/user-search")
    public java.util.List<Map<String, Object>> userSearch(@RequestParam("q") String q) {
        return apiConfigs.searchUsers(AuthContext.require().userId(), q);
    }

    /**
     * 求职总结：已缓存内容 + 任务状态。前端据 status 决定显示「生成中 / 已生成 / 失败」，
     * 状态来自数据库，刷新页面不会丢。
     */
    @GetMapping("/me/summary")
    public Map<String, Object> summary() {
        return summaryView(currentUser());
    }

    /** 启动后台生成，立即返回当前状态；已有任务在跑返回 409，不重复提交 */
    @PostMapping("/me/summary")
    public ResponseEntity<Map<String, Object>> generateSummary() {
        User u = currentUser();
        boolean started = profileSummary.start(AuthContext.require(), userLlm.snapshotFor(u.getId()));
        Map<String, Object> body = summaryView(currentUser());
        return started
                ? ResponseEntity.accepted().body(body)
                : ResponseEntity.status(409).body(Map.of("error", "总结正在生成中，请稍候"));
    }

    /* ---------- 内部 ---------- */

    private Map<String, Object> summaryView(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", u.getSummaryStatus() == null ? "idle" : u.getSummaryStatus());
        m.put("error", u.getSummaryError());
        m.put("startedAt", u.getSummaryStartedAt());
        m.put("summary", profileSummary.summary(u.getId()));
        m.put("summaryAt", u.getSummaryAt());
        return m;
    }

    private User currentUser() {
        return users.findById(AuthContext.require().userId())
                .orElseThrow(() -> new com.review.vt.service.NotFoundException("用户不存在"));
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
        boolean hasKey = u.getApiKeyCipher() != null && !u.getApiKeyCipher().isBlank();
        boolean hasSummary = u.getSummaryJson() != null && !u.getSummaryJson().isBlank();
        return new AuthUser(String.valueOf(u.getId()), u.getUsername(), u.getNickname(), u.getAvatarExt(),
                u.getApiKeyLast4(), hasKey, hasSummary, u.getSummaryAt());
    }
}
