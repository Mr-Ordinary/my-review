package com.review.vt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.review.vt.auth.AuthContext;
import com.review.vt.pojo.entity.Note;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

/** note 表 Mapper（SQL 见 resources/mapper/NoteMapper.xml，全部带 user_id 隔离）。 */
@Mapper
public interface NoteMapper extends BaseMapper<Note> {

    Note selectByIdScoped(Long id, Long userId);

    default Optional<Note> findById(Long id) {
        return Optional.ofNullable(selectByIdScoped(id, AuthContext.require().userId()));
    }

    default Note save(Note note) {
        if (note.getUserId() == null) note.setUserId(AuthContext.require().userId());
        if (note.getId() == null) {
            insert(note);
        } else {
            updateById(note);
        }
        return note;
    }



    List<Note> selectByPracticeDesc(Long userId, Long practiceId);

    default List<Note> findByPracticeIdOrderByCreatedAtDesc(Long practiceId) {
        return selectByPracticeDesc(AuthContext.require().userId(), practiceId);
    }

    void deleteByIds(Long userId, List<Long> ids);

    default void deleteAll(List<Note> notes) {
        java.util.List<Long> ids = notes.stream().map(Note::getId).toList();
        if (ids.isEmpty()) return;   // 空列表：IN () 会语法错（沿用旧 deleteByIds 守卫）
        deleteByIds(AuthContext.require().userId(), ids);
    }
}
