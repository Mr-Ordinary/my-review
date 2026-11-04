package com.review.vt.auth;

import com.review.vt.domain.User;
import com.review.vt.repo.UserRepository;
import com.review.vt.service.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 注册 / 登录 / 登出 / 改密码。
 *
 * <p>口令一律 Argon2id 哈希（编码串自带盐与参数，无单独盐列）。
 * 登录带防爆破：同一用户名连续失败 {@value #MAX_FAILS} 次后锁定 {@value #FAIL_WINDOW_MS}ms。
 */
@Service
public class AuthService {

    /** 连续失败上限 */
    static final int MAX_FAILS = 5;
    /** 锁定窗口 */
    static final long FAIL_WINDOW_MS = 60_000;

    private final UserRepository users;
    private final PasswordEncoder encoder;

    /** 登录防爆破：username → [连续失败次数, 窗口起点(ms)] */
    private final Map<String, long[]> loginFails = new ConcurrentHashMap<>();

    public AuthService(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    public User register(String username, String password) {
        String name = normalizeUsername(username);
        validatePassword(password);
        if (users.existsByUsername(name)) throw new IllegalArgumentException("用户名已被占用");
        long now = System.currentTimeMillis();
        User u = new User();
        u.setId(UUID.randomUUID().toString());
        u.setUsername(name);
        u.setNickname(name);
        u.setPasswordHash(encoder.encode(password));
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        users.insert(u);
        return u;
    }

    public User login(String username, String password, HttpServletRequest req) {
        String name = normalizeUsername(username);
        checkRate(name);
        Optional<User> found = users.findByUsername(name);
        boolean ok = found.isPresent() && encoder.matches(password, found.get().getPasswordHash());
        if (!ok) {
            bump(name);
            throw new IllegalArgumentException("用户名或密码错误");
        }
        loginFails.remove(name);
        // 防会话固定：作废旧会话再建新会话（等效 changeSessionId，且兼容所有 Servlet 版本）
        HttpSession old = req.getSession(false);
        if (old != null) old.invalidate();
        HttpSession session = req.getSession(true);
        session.setAttribute("user", new AuthContext.CurrentUser(found.get().getId(), found.get().getUsername()));
        return found.get();
    }

    public void logout(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session != null) session.invalidate();
    }

    public User changePassword(String userId, String oldPassword, String newPassword) {
        User u = me(userId);
        if (!encoder.matches(oldPassword, u.getPasswordHash())) {
            throw new IllegalArgumentException("原密码不正确");
        }
        validatePassword(newPassword);
        u.setPasswordHash(encoder.encode(newPassword));
        u.setUpdatedAt(System.currentTimeMillis());
        users.update(u);
        return u;
    }

    public User me(String userId) {
        return users.findById(userId)
                .orElseThrow(() -> new NotFoundException("用户不存在"));
    }

    /* ================= 防爆破 ================= */

    private void checkRate(String name) {
        long[] st = loginFails.get(name);
        if (st == null || st[0] < MAX_FAILS) return;
        if (System.currentTimeMillis() - st[1] < FAIL_WINDOW_MS) {
            throw new IllegalArgumentException("尝试次数过多，请 1 分钟后再试");
        }
        loginFails.remove(name);
    }

    private void bump(String name) {
        loginFails.compute(name, (k, st) -> {
            if (st == null || System.currentTimeMillis() - st[1] > FAIL_WINDOW_MS) {
                return new long[]{1, System.currentTimeMillis()};
            }
            st[0]++;
            return st;
        });
    }

    /* ================= 校验 ================= */

    /** 用户名 3-32 位：字母、数字、下划线、中文 */
    private static String normalizeUsername(String username) {
        if (username == null || username.isBlank()) throw new IllegalArgumentException("用户名不能为空");
        String s = username.trim();
        if (s.length() < 3 || s.length() > 32) throw new IllegalArgumentException("用户名长度需在 3-32 位之间");
        if (!s.matches("[\\w\\u4e00-\\u9fa5-]+")) {
            throw new IllegalArgumentException("用户名只能包含字母、数字、下划线或中文");
        }
        return s;
    }

    /** 密码 ≥8 位且同时含字母与数字 */
    static void validatePassword(String password) {
        if (password == null || password.length() < 8) throw new IllegalArgumentException("密码至少 8 位");
        boolean letter = password.chars().anyMatch(Character::isLetter);
        boolean digit = password.chars().anyMatch(Character::isDigit);
        if (!letter || !digit) throw new IllegalArgumentException("密码需同时包含字母和数字");
    }
}
