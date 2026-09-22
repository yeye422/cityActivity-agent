package com.city.mapper;

import com.city.enums.PreferencePolarity;
import com.city.model.PreferenceFact;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PreferenceFactMapper {
    List<PreferenceFact> findActiveByUserId(@Param("userId") Long userId);

    PreferenceFact findByNaturalKey(
            @Param("userId") Long userId,
            @Param("slotName") String slotName,
            @Param("slotValue") String slotValue,
            @Param("polarity") PreferencePolarity polarity
    );

    int upsert(
            @Param("userId") Long userId,
            @Param("slotName") String slotName,
            @Param("slotValue") String slotValue,
            @Param("polarity") PreferencePolarity polarity,
            @Param("source") String source
    );

    int softDelete(
            @Param("userId") Long userId,
            @Param("id") Long id,
            @Param("expectedVersion") Integer expectedVersion
    );
}
