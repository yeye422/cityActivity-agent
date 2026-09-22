package com.city.mapper;

import com.city.model.ActivitySessionRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Mapper
public interface ActivitySessionMapper {
    List<ActivitySessionRow> findAvailableByActivityId(@Param("activityId") Long activityId,
                                                        @Param("date") LocalDate date);

    /** 候选活动中哪些配置过具体场次。 */
    List<Long> findActivityIdsWithSessions(@Param("activityIds") List<Long> activityIds);

    /** 在目标日期/时段内存在可报名场次的活动 ID。 */
    List<Long> findAvailableActivityIds(@Param("activityIds") List<Long> activityIds,
                                        @Param("dateStart") LocalDate dateStart,
                                        @Param("dateEnd") LocalDate dateEnd,
                                        @Param("timeStart") LocalTime timeStart,
                                        @Param("timeEnd") LocalTime timeEnd);
}
