package com.city.mapper;

import com.city.model.ActivityEmbeddingRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ActivityEmbeddingMapper {

    @Select({
            "<script>",
            "SELECT activity_id, source_hash, model, dimensions, embedding_json, updated_at",
            "FROM activity_embedding",
            "WHERE activity_id IN",
            "<foreach collection='activityIds' item='id' open='(' separator=',' close=')'>",
            "#{id}",
            "</foreach>",
            "</script>"
    })
    List<ActivityEmbeddingRow> findByActivityIds(@Param("activityIds") List<Long> activityIds);

    @Insert("""
            INSERT INTO activity_embedding
                (activity_id, source_hash, model, dimensions, embedding_json, updated_at)
            VALUES
                (#{activityId}, #{sourceHash}, #{model}, #{dimensions}, #{embeddingJson}, NOW())
            ON DUPLICATE KEY UPDATE
                source_hash = VALUES(source_hash),
                model = VALUES(model),
                dimensions = VALUES(dimensions),
                embedding_json = VALUES(embedding_json),
                updated_at = NOW()
            """)
    int upsert(ActivityEmbeddingRow row);
}
