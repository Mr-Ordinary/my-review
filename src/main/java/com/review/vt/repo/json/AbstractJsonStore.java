package com.review.vt.repo.json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 本地 JSON 文件存储基类：一张表一个文件（JSON 数组，行字段名 = 实体字段名，
 * 与数据库表列 1:1 对应，后续换库可无损迁移）。
 *
 * 行为约定（个人规模）：
 * - 启动时一次性加载进内存（LinkedHashMap，保持文件顺序），此后读写都在内存进行
 * - 每次变更后整体 flush：写临时文件再原子移动，避免写坏
 * - 所有公开方法 synchronized（同 SettingsService 风格）
 * - save 按 id upsert（等价 JPA merge）；find* 返回活实例，调用方修改后需再 save
 * - 解析失败时把坏文件备份为 *.json.bad-epoch，再以空库启动，绝不静默覆盖
 * - 跨文件（级联删除）不做事务，崩溃中途可能留下孤儿行，个人规模可接受
 */
public abstract class AbstractJsonStore<T> {

    /** 容忍历史行里的残留字段（字段增删后旧数据仍可无损读入） */
    protected final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Path file;
    private final Function<T, String> idOf;
    private final TypeReference<List<T>> listType;

    /** id → 实体（插入顺序 = 文件顺序） */
    protected final Map<String, T> rows = new LinkedHashMap<>();

    protected AbstractJsonStore(Path file, Function<T, String> idOf, TypeReference<List<T>> listType) {
        this.file = file;
        this.idOf = idOf;
        this.listType = listType;
    }

    @PostConstruct
    synchronized void load() {
        if (!Files.exists(file)) return;
        try {
            List<T> list = mapper.readValue(file.toFile(), listType);
            for (T t : list) {
                String id = idOf.apply(t);
                if (id != null) rows.put(id, t);
            }
        } catch (IOException e) {
            backupCorruptFile();
            System.err.println("[myreview] 读取 " + file + " 失败，已备份原文件并以空库启动：" + e.getMessage());
        }
    }

    private void backupCorruptFile() {
        try {
            Path backup = file.resolveSibling(file.getFileName() + ".bad-" + System.currentTimeMillis());
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("[myreview] 备份损坏文件失败：" + e.getMessage());
        }
    }

    /** 按 id upsert 并落盘 */
    protected synchronized T save(T t) {
        rows.put(idOf.apply(t), t);
        flush();
        return t;
    }

    protected synchronized void delete(T t) {
        rows.remove(idOf.apply(t));
        flush();
    }

    protected synchronized void deleteAll(List<T> list) {
        for (T t : list) rows.remove(idOf.apply(t));
        flush();
    }

    protected synchronized List<T> all() {
        return new ArrayList<>(rows.values());
    }

    protected synchronized T get(String id) {
        return rows.get(id);
    }

    private void flush() {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling("." + file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), new ArrayList<>(rows.values()));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("保存 " + file + " 失败：" + e.getMessage(), e);
        }
    }
}
