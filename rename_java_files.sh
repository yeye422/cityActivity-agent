#!/bin/bash
# Java文件重命名脚本
# 将所有 Meal* 类文件重命名为 Activity*

cd "$(dirname "$0")"

echo "开始重命名Java类文件..."

# 定义文件重命名映射
declare -A rename_map=(
    ["src/main/java/com/diet/controller/meal/MealController.java"]="src/main/java/com/diet/controller/meal/ActivityController.java"
    ["src/main/java/com/diet/mapper/MealMapper.java"]="src/main/java/com/diet/mapper/ActivityMapper.java"
    ["src/main/java/com/diet/model/MealItem.java"]="src/main/java/com/diet/model/ActivityItem.java"
    ["src/main/java/com/diet/model/MealItemRow.java"]="src/main/java/com/diet/model/ActivityItemRow.java"
    ["src/main/java/com/diet/model/MealRankRequest.java"]="src/main/java/com/diet/model/ActivityRankRequest.java"
    ["src/main/java/com/diet/model/MealRequest.java"]="src/main/java/com/diet/model/ActivityRequest.java"
    ["src/main/java/com/diet/model/MealResponse.java"]="src/main/java/com/diet/model/ActivityResponse.java"
    ["src/main/java/com/diet/model/MealSearchRequest.java"]="src/main/java/com/diet/model/ActivitySearchRequest.java"
    ["src/main/java/com/diet/model/RecommendedMealOption.java"]="src/main/java/com/diet/model/RecommendedActivityOption.java"
    ["src/main/java/com/diet/service/meal/MealRankService.java"]="src/main/java/com/diet/service/meal/ActivityRankService.java"
    ["src/main/java/com/diet/service/meal/MealSearchService.java"]="src/main/java/com/diet/service/meal/ActivitySearchService.java"
    ["src/main/java/com/diet/service/meal/MealService.java"]="src/main/java/com/diet/service/meal/ActivityService.java"
    ["src/main/java/com/diet/service/plan/MealPlanService.java"]="src/main/java/com/diet/service/plan/ActivityPlanService.java"
)

# 执行重命名
for old_file in "${!rename_map[@]}"; do
    new_file="${rename_map[$old_file]}"
    if [ -f "$old_file" ]; then
        mv "$old_file" "$new_file"
        echo "重命名: $old_file -> $new_file"
    else
        echo "⚠️  文件不存在: $old_file"
    fi
done

# 重命名目录
if [ -d "src/main/java/com/diet/service/meal" ]; then
    mv "src/main/java/com/diet/service/meal" "src/main/java/com/diet/service/activity"
    echo "重命名目录: service/meal -> service/activity"
fi

if [ -d "src/main/java/com/diet/controller/meal" ]; then
    mv "src/main/java/com/diet/controller/meal" "src/main/java/com/diet/controller/activity"
    echo "重命名目录: controller/meal -> controller/activity"
fi

# 重命名 Mapper XML
if [ -f "src/main/resources/mapper/MealMapper.xml" ]; then
    mv "src/main/resources/mapper/MealMapper.xml" "src/main/resources/mapper/ActivityMapper.xml"
    echo "重命名: MealMapper.xml -> ActivityMapper.xml"
fi

echo ""
echo "✅ Java文件重命名完成！"
