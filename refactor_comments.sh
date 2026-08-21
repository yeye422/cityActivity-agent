#!/bin/bash
# 批量重构注释脚本 - 将"餐食"相关注释改为"活动"相关

cd "$(dirname "$0")"

echo "开始批量重构注释..."

# 定义替换规则（使用 sed）
find src/main/java -name "*.java" -type f | while read file; do
    # 备份文件
    cp "$file" "$file.bak"

    # 执行批量替换
    sed -i 's/饮食推荐/城市活动推荐/g' "$file"
    sed -i 's/饮食助手/城市活动助手/g' "$file"
    sed -i 's/餐食推荐/活动推荐/g' "$file"
    sed -i 's/餐食卡片/活动卡片/g' "$file"
    sed -i 's/餐食检索/活动检索/g' "$file"
    sed -i 's/餐食重排/活动重排/g' "$file"
    sed -i 's/餐食服务/活动服务/g' "$file"
    sed -i 's/餐次标签/活动时间标签/g' "$file"
    sed -i 's/心情标签/活动状态标签/g' "$file"
    sed -i 's/场景标签/同行人标签/g' "$file"
    sed -i 's/健康目标标签/预算标签/g' "$file"
    sed -i 's/菜系标签/活动类型标签/g' "$file"
    sed -i 's/口味标签/活动风格标签/g' "$file"
    sed -i 's/用餐时间/活动时间/g' "$file"
    sed -i 's/就餐意向/活动意向/g' "$file"
    sed -i 's/多餐规划/多时段规划/g' "$file"
    sed -i 's/按餐次/按时段/g' "$file"
    sed -i 's/早餐、午餐、晚餐/上午、下午、晚上/g' "$file"
    sed -i 's/极端节食/极端天气/g' "$file"
    sed -i 's/医疗诊断、治疗承诺/深夜独行、偏远地点/g' "$file"
    sed -i 's/与饮食无关/与城市活动无关/g' "$file"
    sed -i 's/健康风险/安全风险/g' "$file"
    sed -i 's/普通饮食推荐/普通活动推荐/g' "$file"

    # 示例相关替换
    sed -i 's/晚饭推荐清淡一点的/周六想看展览/g' "$file"
    sed -i 's/清淡点或快一点/降低预算或改成室内/g' "$file"

    echo "处理完成: $file"
done

echo "批量重构完成！"
echo "备份文件已保存为 *.java.bak"
