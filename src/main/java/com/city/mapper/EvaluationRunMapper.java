package com.city.mapper;

import com.city.model.EvaluationRunRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface EvaluationRunMapper {
    int insert(EvaluationRunRow row);

    EvaluationRunRow findByRunId(@Param("userId") Long userId,
                                 @Param("runId") String runId);

    EvaluationRunRow findBaseline(@Param("userId") Long userId,
                                  @Param("evalSetVersion") String evalSetVersion,
                                  @Param("evalSetHash") String evalSetHash);

    int clearBaseline(@Param("userId") Long userId,
                      @Param("evalSetVersion") String evalSetVersion,
                      @Param("evalSetHash") String evalSetHash);

    int markBaseline(@Param("userId") Long userId,
                     @Param("runId") String runId,
                     @Param("baselineName") String baselineName);
}
