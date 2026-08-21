package com.city.mapper;

import com.city.model.ActivityItemRow;
import com.city.enums.SourceMode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ActivityMapper {
    int insert(ActivityItemRow row);

    int updatePersonal(ActivityItemRow row);

    int deletePersonal(@Param("id") Long id, @Param("userId") Long userId);

    ActivityItemRow findPersonalById(@Param("id") Long id, @Param("userId") Long userId);

    List<ActivityItemRow> findPersonalActivities(Long userId);

    List<ActivityItemRow> findPublicActivities();

    int countPersonalActivities(Long userId);

    List<ActivityItemRow> search(
            @Param("sourceMode") SourceMode sourceMode,
            @Param("userId") Long userId,
            @Param("cityJson") String cityJson,
            @Param("locationJson") String locationJson,
            @Param("activityTimeJson") String activityTimeJson,
            @Param("moodJson") String moodJson,
            @Param("sceneJson") String sceneJson,
            @Param("budgetJson") String budgetJson,
            @Param("activityTypeJson") String activityTypeJson,
            @Param("styleJson") String styleJson,
            @Param("durationJson") String durationJson,
            @Param("limit") int limit
    );
}


