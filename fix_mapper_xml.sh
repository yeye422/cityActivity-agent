#!/bin/bash
# 修复所有 Mapper XML 文件中的表名和字段名

cd "$(dirname "$0")"

echo "开始修复 Mapper XML 文件..."

# 修复所有 Mapper XML 文件
find src/main/resources/mapper -name "*.xml" -type f | while read file; do
    echo "处理文件: $file"

    # 修复表名
    sed -i 's/diet_sessions/city_sessions/g' "$file"
    sed -i 's/diet_messages/city_messages/g' "$file"
    sed -i 's/diet_slot_option/city_slot_option/g' "$file"
    sed -i 's/diet_agent_trace/city_agent_trace/g' "$file"
    sed -i 's/diet_feedback/city_feedback/g' "$file"
    sed -i 's/recommend_feedback/city_feedback/g' "$file"
    sed -i 's/diet_request_trace/city_request_trace/g' "$file"
    sed -i 's/meal_item/activity_item/g' "$file"

    # 修复字段名
    sed -i 's/last_recommendations/last_recommended_activity_ids/g' "$file"
    sed -i 's/lastRecommendations/lastRecommendedActivityIds/g' "$file"

    # 修复列名
    sed -i 's/\bmeal_time\b/activity_time/g' "$file"
    sed -i 's/\bhealth_goal\b/budget/g' "$file"
    sed -i 's/\bcuisine\b/activity_type/g' "$file"
    sed -i 's/\btaste\b/style/g' "$file"
    sed -i 's/\bconvenience\b/duration/g' "$file"
done

echo "✅ Mapper XML 文件修复完成！"
