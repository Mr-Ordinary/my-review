package com.review.vt.service;

import com.review.vt.pojo.entity.ApiConfig;
import com.review.vt.pojo.param.ApiConfigParam;
import com.review.vt.pojo.entity.ApiShare;
import com.review.vt.pojo.entity.User;
import com.review.vt.mapper.ApiConfigMapper;
import com.review.vt.mapper.ApiShareMapper;
import com.review.vt.mapper.UserMapper;
import com.review.vt.security.AesGcmCrypto;
import com.review.vt.security.MasterKeyService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.review.vt.common.util.MappingsUtil;
import com.review.vt.web.ex.NotFoundException;

/**
 * 多套 API 配置 + 用户间共享。
 *
 * <ul>
 *   <li>一个用户可配多套配置（各带独立密钥/地址/模型），指定其中一套「启用」</li>
 *   <li>所有者可把配置共享给其他用户：对方配置选择里多一项，标注「由 XXX 共享」，
 *       只读可用（能启用、不能改删），所有者可随时取消</li>
 *   <li>密钥 AES-256-GCM 加密落库，明文只在生成快照的瞬间解密，绝不出后端</li>
 * </ul>
 */
@Service
public class ApiConfigService {

    /** 选人搜索的返回上限（共享弹窗用） */
    static final int USER_SEARCH_LIMIT = 10;

    private final ApiConfigMapper apiConfigMapper;
    private final ApiShareMapper apiShareMapper;
    private final UserMapper userMapper;
    private final UserCacheService userCache;
    private final AesGcmCrypto crypto;

    public ApiConfigService(ApiConfigMapper apiConfigMapper, ApiShareMapper apiShareMapper,
                            UserMapper userMapper, UserCacheService userCache, MasterKeyService masterKey) {
        this.apiConfigMapper = apiConfigMapper;
        this.apiShareMapper = apiShareMapper;
        this.userMapper = userMapper;
        this.userCache = userCache;
        this.crypto = new AesGcmCrypto(masterKey.key());
    }

    /* ================= 解析（快照用） ================= */

    /**
     * 当前启用的配置。只认「自己的，或共享给我的」——共享被取消后自动失效，
     * 回落到服务端默认（调用方兜底）。
     */
    public Optional<ApiConfig> activeConfigFor(Long userId) {
        Long id = userMapper.activeLlmConfigId(userId);
        if (id == null) return Optional.empty();
        return apiConfigMapper.findById(id).filter(c ->
                userId.equals(c.getUserId())
                        || apiShareMapper.findByConfigIdAndTargetUserId(id, userId).isPresent());
    }

    /** 解密配置的 API Key；未配置返回空串。明文只在快照生成瞬间出密文 */
    public String decryptKey(ApiConfig c) {
        if (c.getApiKeyCipher() == null || c.getApiKeyCipher().isBlank()
                || c.getApiKeyNonce() == null || c.getApiKeyNonce().isBlank()) {
            return "";
        }
        return crypto.decrypt(c.getApiKeyNonce(), c.getApiKeyCipher());
    }

    /* ================= 列表 / 读写 ================= */

    /** 我的全部配置 + 共享给我的配置（每条带来源标注与启用状态） */
    public List<Map<String, Object>> list(Long userId) {
        Long activeId = userMapper.activeLlmConfigId(userId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ApiConfig c : apiConfigMapper.findByUserId(userId)) {
            out.add(dto(c, null, c.getId().equals(activeId)));
        }
        for (ApiShare s : apiShareMapper.findByTargetUserId(userId)) {
            apiConfigMapper.findById(s.getConfigId()).ifPresent(c -> {
                com.review.vt.pojo.dto.UserCache owner = userCache.loadForRead(s.getOwnerUserId()).orElse(null);
                out.add(dto(c, owner, c.getId().equals(activeId)));
            });
        }
        return out;
    }

    /** 新建一套配置（对象入参，别再排长参数表） */
    public Map<String, Object> create(Long userId, ApiConfigParam param) {
        String clean = param.name() == null ? "" : param.name().trim();
        if (clean.isBlank()) throw new IllegalArgumentException("配置名不能为空");
        if (clean.length() > 64) throw new IllegalArgumentException("配置名太长（上限 64 字）");
        long now = System.currentTimeMillis();
        ApiConfig c = new ApiConfig();
        c.setUserId(userId);
        c.setName(clean);
        c.setApiBase(blankToNull(param.apiBase()));
        c.setModel(blankToNull(param.model()));
        c.setApiFormat(normalizeFormat(param.apiFormat()));
        c.setTemperature(param.temperature() == null ? null : clamp(param.temperature()));
        applyKey(c, param.apiKey(), false);
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        apiConfigMapper.insert(c);
        return dto(c, null, false);
    }

    /**
     * 更新自己的配置（对象入参）。apiKey 规则：留空或含 '*'（掩码回显）= 不动密文；
     * clearKey = true 显式清除；其余 = 换新 key。
     */
    public Map<String, Object> update(Long userId, Long configId, ApiConfigParam param) {
        ApiConfig c = requireOwn(userId, configId);
        String clean = param.name() == null ? "" : param.name().trim();
        if (clean.isBlank()) throw new IllegalArgumentException("配置名不能为空");
        if (clean.length() > 64) throw new IllegalArgumentException("配置名太长（上限 64 字）");
        c.setName(clean);
        c.setApiBase(blankToNull(param.apiBase()));
        c.setModel(blankToNull(param.model()));
        c.setApiFormat(normalizeFormat(param.apiFormat()));
        c.setTemperature(param.temperature() == null ? null : clamp(param.temperature()));
        if (Boolean.TRUE.equals(param.clearKey())) {
            c.setApiKeyCipher(null);
            c.setApiKeyNonce(null);
            c.setApiKeyVersion(0);
            c.setApiKeyLast4(null);
        } else if (param.apiKey() != null && !param.apiKey().isBlank() && !param.apiKey().contains("*")) {
            applyKey(c, param.apiKey(), true);
        }
        c.setUpdatedAt(System.currentTimeMillis());
        apiConfigMapper.updateById(c);
        return dto(c, null, c.getId().equals(userMapper.activeLlmConfigId(userId)));
    }

    /** 删除自己的配置（级联清共享；若正启用则回落默认） */
    public void delete(Long userId, Long configId) {
        requireOwn(userId, configId);
        if (configId.equals(userMapper.activeLlmConfigId(userId))) {
            userMapper.updateActiveLlmConfig(userId, null);
        }
        apiShareMapper.deleteByConfigId(configId);
        apiConfigMapper.deleteById(configId);
    }

    /** 启用某套配置：自己的或共享给我的都行 */
    public void activate(Long userId, Long configId) {
        boolean owned = apiConfigMapper.findById(configId).map(c -> userId.equals(c.getUserId())).orElse(false);
        boolean shared = apiShareMapper.findByConfigIdAndTargetUserId(configId, userId).isPresent();
        if (!owned && !shared) throw new NotFoundException("配置不存在");
        userMapper.updateActiveLlmConfig(userId, configId);
    }

    /* ================= 共享 ================= */

    /** 这套配置共享给了谁（仅所有者可看） */
    public List<Map<String, Object>> sharesOf(Long userId, Long configId) {
        requireOwn(userId, configId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ApiShare s : apiShareMapper.findByConfigId(configId)) {
            com.review.vt.pojo.dto.UserCache target = userCache.loadForRead(s.getTargetUserId()).orElse(null);
            if (target == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("shareId", MappingsUtil.id(s.getId()));
            m.put("userId", MappingsUtil.id(target.id()));
            m.put("username", target.username());
            m.put("nickname", target.nickname());
            m.put("createdAt", s.getCreatedAt());
            out.add(m);
        }
        return out;
    }

    /** 共享给指定用户（按用户 id，来自选人搜索）。不能共享给自己，不能重复 */
    public Map<String, Object> shareTo(Long userId, Long configId, Long targetUserId) {
        requireOwn(userId, configId);
        if (targetUserId == null || targetUserId.equals(userId)) {
            throw new IllegalArgumentException("不能共享给自己");
        }
        com.review.vt.pojo.dto.UserCache target = userCache.loadForRead(targetUserId)
                .orElseThrow(() -> new NotFoundException("用户不存在"));
        if (apiShareMapper.findByConfigIdAndTargetUserId(configId, targetUserId).isPresent()) {
            throw new IllegalArgumentException("已经共享给这个用户了");
        }
        long now = System.currentTimeMillis();
        ApiShare s = new ApiShare();
        s.setConfigId(configId);
        s.setOwnerUserId(userId);
        s.setTargetUserId(targetUserId);
        s.setCreatedAt(now);
        apiShareMapper.insert(s);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("shareId", MappingsUtil.id(s.getId()));
        m.put("userId", MappingsUtil.id(target.id()));
        m.put("username", target.username());
        m.put("nickname", target.nickname());
        m.put("createdAt", now);
        return m;
    }

    /** 取消共享（仅所有者） */
    public void unshare(Long userId, Long configId, Long targetUserId) {
        requireOwn(userId, configId);
        ApiShare s = apiShareMapper.findByConfigIdAndTargetUserId(configId, targetUserId)
                .orElseThrow(() -> new NotFoundException("共享记录不存在"));
        apiShareMapper.deleteById(s.getId());
    }

    /** 选人搜索：按用户名/昵称模糊找（不含自己），空串返回空 */
    public List<Map<String, Object>> searchUsers(Long userId, String q) {
        String query = q == null ? "" : q.trim();
        if (query.isEmpty()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (User u : userMapper.searchByUsernameOrNickname(query, USER_SEARCH_LIMIT + 1)) {
            if (u.getId().equals(userId)) continue;
            if (out.size() >= USER_SEARCH_LIMIT) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", MappingsUtil.id(u.getId()));
            m.put("username", u.getUsername());
            m.put("nickname", u.getNickname());
            out.add(m);
        }
        return out;
    }

    /* ================= 内部 ================= */

    private ApiConfig requireOwn(Long userId, Long configId) {
        ApiConfig c = apiConfigMapper.findById(configId)
                .orElseThrow(() -> new NotFoundException("配置不存在"));
        if (!userId.equals(c.getUserId())) throw new NotFoundException("配置不存在");
        return c;
    }

    private void applyKey(ApiConfig c, String plainKey, boolean replace) {
        String trimmed = plainKey == null ? "" : plainKey.trim();
        if (trimmed.isEmpty()) {
            if (!replace) {
                c.setApiKeyCipher(null);
                c.setApiKeyNonce(null);
                c.setApiKeyVersion(0);
                c.setApiKeyLast4(null);
            }
            return;
        }
        AesGcmCrypto.Encrypted enc = crypto.encrypt(trimmed);
        c.setApiKeyCipher(enc.cipherText());
        c.setApiKeyNonce(enc.nonce());
        c.setApiKeyVersion(AesGcmCrypto.VERSION);
        c.setApiKeyLast4(last4(trimmed));
    }

    private Map<String, Object> dto(ApiConfig c, com.review.vt.pojo.dto.UserCache owner, boolean active) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", MappingsUtil.id(c.getId()));
        m.put("name", c.getName());
        m.put("active", active);
        // 来源标注：共享的配置注明「由谁共享」，自己的为 null
        if (owner != null) {
            Map<String, Object> by = new LinkedHashMap<>();
            by.put("userId", MappingsUtil.id(owner.id()));
            by.put("username", owner.username());
            by.put("nickname", owner.nickname());
            m.put("sharedBy", by);
        } else {
            m.put("sharedBy", null);
        }
        m.put("apiBase", c.getApiBase());
        m.put("model", c.getModel());
        m.put("apiFormat", c.getApiFormat());
        m.put("temperature", c.getTemperature());
        boolean hasKey = c.getApiKeyCipher() != null && !c.getApiKeyCipher().isBlank();
        m.put("hasApiKey", hasKey);
        m.put("masked", UserLlmService.mask(c.getApiKeyLast4()));
        m.put("createdAt", c.getCreatedAt());
        return m;
    }

    private static String last4(String key) {
        return key.length() <= 4 ? key : key.substring(key.length() - 4);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String normalizeFormat(String apiFormat) {
        if (apiFormat == null || apiFormat.isBlank()) return null;
        String v = apiFormat.trim().toLowerCase();
        if (!v.equals("anthropic") && !v.equals("openai")) {
            throw new IllegalArgumentException("接口协议只支持 anthropic 或 openai");
        }
        return v;
    }

    private static double clamp(double t) {
        return t < 0 ? 0 : Math.min(t, 1);
    }
}
