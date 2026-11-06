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
    public record ApiKeyRequest(String apiKey) {}
    public record LlmSettingsRequest(String apiBase, String model, String apiFormat, Double temperature) {}

    /** 头像允许的扩展名（内容不重编码，仅限图片扩展） */
    private static final Set<String> AVATAR_EXTS = Set.of("png", "jpg", "jpeg", "webp", "gif");
    private static final long AVATAR_MAX_BYTES = 2L * 1024 * 1024;

    private final UserRepository users;
    private final UserLlmService userLlm;
    private final ProfileSummaryService profileSummary;
    private final Path avatarDir;

    public MeController(UserRepository users, UserLlmService userLlm,
                        ProfileSummaryService profileSummary,
                        @Value("${myreview.data-dir:E:/m_review}") String dataDir) {
        this.users = users;
        this.userLlm = userLlm;
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

    /** 当前用户的 LLM 配置（apiBase / model / apiFormat / temperature / key 掩码） */
    @GetMapping("/me/llm-settings")
    public Map<String, Object> llmSettings() {
        return userLlm.llmSettingsOf(AuthContext.require().userId());
    }

    /** 更新 LLM 配置（apiBase / model / apiFormat / temperature） */
    @PutMapping("/me/llm-settings")
    public Map<String, Object> saveLlmSettings(@RequestBody LlmSettingsRequest req) {
        userLlm.setLlmSettings(AuthContext.require().userId(),
                req.apiBase(), req.model(), req.apiFormat(), req.temperature());
        return userLlm.llmSettingsOf(AuthContext.require().userId());
    }

    /** API Key 状态：只回显掩码（明文永远不出后端） */
    @GetMapping("/me/api-key")
    public Map<String, Object> apiKeyStatus() {
        User u = currentUser();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hasApiKey", u.getApiKeyCipher() != null && !u.getApiKeyCipher().isBlank());
        m.put("masked", UserLlmService.mask(u.getApiKeyLast4()));
        return m;
    }

    /**
     * 保存 API Key（AES-256-GCM 加密落库）。空串 = 清除；
     * 前端把掩码原样回传时（含 '*'）视为未修改，保持原密文不动。
     */
    @PutMapping("/me/api-key")
    public Map<String, Object> saveApiKey(@RequestBody ApiKeyRequest req) {
        String key = req.apiKey() == null ? "" : req.apiKey().trim();
        if (key.contains("*")) return apiKeyStatus(); // 掩码回写，不动
        User u = userLlm.setApiKey(AuthContext.require().userId(), key);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hasApiKey", u.getApiKeyCipher() != null && !u.getApiKeyCipher().isBlank());
        m.put("masked", UserLlmService.mask(u.getApiKeyLast4()));
        return m;
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
