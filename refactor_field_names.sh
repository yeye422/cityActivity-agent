#!/bin/bash
# Java字段名批量重构脚本
# 将 mealTime、healthGoal、cuisine 改为 activityTime、budget、activityType

cd "$(dirname "$0")"

echo "开始批量重构Java字段名..."

# 定义替换规则
find src/main/java -name "*.java" -type f | while read file; do
    # 备份文件（如果还没备份）
    if [ ! -f "$file.bak2" ]; then
        cp "$file" "$file.bak2"
    fi

    # 字段名替换（注意大小写）
    sed -i 's/mealTime/activityTime/g' "$file"
    sed -i 's/MealTime/ActivityTime/g' "$file"
    sed -i 's/meal_time/activity_time/g' "$file"

    sed -i 's/healthGoal/budget/g' "$file"
    sed -i 's/HealthGoal/Budget/g' "$file"
    sed -i 's/health_goal/budget/g' "$file"

    sed -i 's/cuisine/activityType/g' "$file"
    sed -i 's/Cuisine/ActivityType/g' "$file"

    # 类名和表名替换
    sed -i 's/MealItem/ActivityItem/g' "$file"
    sed -i 's/meal_item/activity_item/g' "$file"

    # Service 类名
    sed -i 's/MealService/ActivityService/g' "$file"
    sed -i 's/MealSearchService/ActivitySearchService/g' "$file"
    sed -i 's/MealRankService/ActivityRankService/g' "$file"
    sed -i 's/MealPlanService/ActivityPlanService/g' "$file"

    # Mapper 类名
    sed -i 's/MealMapper/ActivityMapper/g' "$file"

    # 请求/响应对象
    sed -i 's/MealSearchRequest/ActivitySearchRequest/g' "$file"
    sed -i 's/MealRankRequest/ActivityRankRequest/g' "$file"
    sed -i 's/MealResponse/ActivityResponse/g' "$file"
    sed -i 's/RecommendedMealOption/RecommendedActivityOption/g' "$file"
    sed -i 's/PlannedMeal/PlannedActivity/g' "$file"

    # 变量名
    sed -i 's/\bmeal\b/activity/g' "$file"
    sed -i 's/\bmeals\b/activities/g' "$file"
    sed -i 's/\bmealId\b/activityId/g' "$file"
    sed -i 's/\bmealIds\b/activityIds/g' "$file"
    sed -i 's/excludeMealIds/excludeActivityIds/g' "$file"
    sed -i 's/lastRecommendations/lastRecommendedActivityIds/g' "$file"

    # Trace 事件名
    sed -i 's/MEAL_SEARCHED/ACTIVITY_SEARCHED/g' "$file"
    sed -i 's/MEAL_RANKED/ACTIVITY_RANKED/g' "$file"
    sed -i 's/MEAL_PLAN/ACTIVITY_PLAN/g' "$file"
    sed -i 's/NO_MEAL_MATCHED/NO_ACTIVITY_MATCHED/g' "$file"

    echo "处理完成: $file"
done

echo ""
echo "批量重构Java字段名完成！"
echo "备份文件已保存为 *.java.bak2"
echo ""
echo "⚠️  需要手动检查的地方："
echo "1. src/main/resources/mapper/*.xml - MyBatis XML需要手动修改"
echo "2. src/main/resources/application.yml - 配置文件"
echo "3. 前端代码 - 如果有的话"
