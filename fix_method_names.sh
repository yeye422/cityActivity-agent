#!/bin/bash
# 修复所有残留的方法名和变量名

cd "$(dirname "$0")"

echo "开始修复残留的方法名和变量名..."

# 修复所有 Java 文件
find src/main/java -name "*.java" -type f | while read file; do
    # 修复方法名
    sed -i 's/findPersonalMeals/findPersonalActivities/g' "$file"
    sed -i 's/findPublicMeals/findPublicActivities/g' "$file"
    sed -i 's/countPersonalMeals/countPersonalActivities/g' "$file"
    sed -i 's/createPersonalMeal/createPersonalActivity/g' "$file"
    sed -i 's/updatePersonalMeal/updatePersonalActivity/g' "$file"
    sed -i 's/deletePersonalMeal/deletePersonalActivity/g' "$file"
    sed -i 's/hasPersonalMeals/hasPersonalActivities/g' "$file"
    sed -i 's/planMeals/planActivities/g' "$file"
    sed -i 's/validateMealRequest/validateActivityRequest/g' "$file"

    # 修复变量名
    sed -i 's/\bmealMapper\b/activityMapper/g' "$file"
    sed -i 's/\bmealService\b/activityService/g' "$file"
    sed -i 's/\bmealSearchService\b/activitySearchService/g' "$file"
    sed -i 's/\bmealRankService\b/activityRankService/g' "$file"
    sed -i 's/\bmealPlanService\b/activityPlanService/g' "$file"
done

# 修复 MyBatis Mapper XML
find src/main/resources/mapper -name "*.xml" -type f | while read file; do
    sed -i 's/findPersonalMeals/findPersonalActivities/g' "$file"
    sed -i 's/findPublicMeals/findPublicActivities/g' "$file"
    sed -i 's/countPersonalMeals/countPersonalActivities/g' "$file"
done

echo "✅ 修复完成！"
