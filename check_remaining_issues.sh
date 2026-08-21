#!/bin/bash
# 全面检查残留的旧类名引用

cd "$(dirname "$0")"

echo "=== 检查残留的旧类名引用 ==="
echo ""

echo "1. 检查 Meal* 相关类名..."
grep -r "class Meal\|interface Meal\|enum Meal" src/main/java --include="*.java" | grep -v ".bak"

echo ""
echo "2. 检查方法名中的 Meal..."
grep -r "findPersonalMeals\|findPublicMeals\|countPersonalMeals\|createPersonalMeal\|updatePersonalMeal\|deletePersonalMeal\|hasPersonalMeals\|planMeals\|toActivityItem" src/main/java --include="*.java" | grep -v ".bak" | head -20

echo ""
echo "3. 检查变量名中的 meal..."
grep -r "mealMapper\|mealService\|mealTime\|healthGoal\|cuisine" src/main/java --include="*.java" | grep -v ".bak" | grep -v "// " | head -20

echo ""
echo "4. 检查 MyBatis Mapper 方法名..."
grep -r "findPersonalMeals\|findPublicMeals\|countPersonalMeals" src/main/resources/mapper --include="*.xml"

echo ""
echo "=== 检查完成 ==="
