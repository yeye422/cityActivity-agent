package com.city.mapper;

import com.city.model.EvaluationRunRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface EvaluationRunMapper {
    int insert(EvaluationRunRow row);
    EvaluationRunRow findLatest(@Param("userId") Long userId, @Param("evalSetVersion") String evalSetVersion);
}
