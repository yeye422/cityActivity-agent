package com.city.mapper;
import com.city.model.EvaluationCaseRow;
import org.apache.ibatis.annotations.Mapper; import org.apache.ibatis.annotations.Param;
import java.util.List;
@Mapper public interface EvaluationCaseMapper { int insert(EvaluationCaseRow row); List<EvaluationCaseRow> findBySetVersion(@Param("userId") Long userId,@Param("version") String version); }
