#!/bin/bash
# 修复包路径和import引用

cd "$(dirname "$0")"

echo "开始修复包路径引用..."

# 修复所有 Java 文件中的 import 和包路径
find src/main/java -name "*.java" -type f | while read file; do
    # 修复 package 声明
    sed -i 's/package com\.diet\.controller\.meal;/package com.diet.controller.activity;/g' "$file"
    sed -i 's/package com\.diet\.service\.meal;/package com.diet.service.activity;/g' "$file"

    # 修复 import 语句
    sed -i 's/import com\.diet\.controller\.meal\./import com.diet.controller.activity./g' "$file"
    sed -i 's/import com\.diet\.service\.meal\./import com.diet.service.activity./g' "$file"
    sed -i 's/import com\.diet\.mapper\.MealMapper;/import com.diet.mapper.ActivityMapper;/g' "$file"
    sed -i 's/import com\.diet\.model\.MealItem;/import com.diet.model.ActivityItem;/g' "$file"
    sed -i 's/import com\.diet\.model\.MealItemRow;/import com.diet.model.ActivityItemRow;/g' "$file"
    sed -i 's/import com\.diet\.model\.MealRequest;/import com.diet.model.ActivityRequest;/g' "$file"
    sed -i 's/import com\.diet\.model\.MealResponse;/import com.diet.model.ActivityResponse;/g' "$file"
    sed -i 's/import com\.diet\.model\.MealSearchRequest;/import com.diet.model.ActivitySearchRequest;/g' "$file"
    sed -i 's/import com\.diet\.model\.MealRankRequest;/import com.diet.model.ActivityRankRequest;/g' "$file"
    sed -i 's/import com\.diet\.model\.RecommendedMealOption;/import com.diet.model.RecommendedActivityOption;/g' "$file"
done

echo "✅ 包路径引用修复完成！"
