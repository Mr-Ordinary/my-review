package com.review.vt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.review.vt.pojo.entity.Source;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

/**
 * source 表 Mapper（SQL 见 resources/mapper/SourceMapper.xml）。
 * 资料缓存**全局共享、无 user_id**——URL 抓取缓存是公开网页内容，多用户复用省抓取。
 */
@Mapper
public interface SourceMapper extends BaseMapper<Source> {

    Source selectByUrl(String url);

    default Optional<Source> findByUrl(String url) {
        return Optional.ofNullable(selectByUrl(url));
    }

    default Source save(Source source) {
        if (source.getId() == null) {
            insert(source);
        } else {
            updateById(source);
        }
        return source;
    }



    List<Source> selectAll();

    default List<Source> findAll() {
        return selectAll();
    }
}
