#!/bin/bash
# 更新 Java 代码中的槽位定义: taste→style, convenience→duration

cd "$(dirname "$0")"

echo "开始更新 Java 代码中的槽位定义..."

# 修改所有 Java 文件
find src/main/java -name "*.java" -type f | while read file; do
    # 替换字段名 taste → style
    sed -i 's/\btaste\b/style/g' "$file"

    # 替换字段名 convenience → duration
    sed -i 's/\bconvenience\b/duration/g' "$file"

    # 替换注释中的中文
    sed -i 's/口味/活动风格/g' "$file"
    sed -i 's/便捷性/活动时长/g' "$file"
    sed -i 's/便利性/活动时长/g' "$file"
done

echo "✅ Java 代码槽位定义更新完成！"
echo ""
echo "已更新的字段："
echo "  - taste → style (活动风格)"
echo "  - convenience → duration (活动时长)"
