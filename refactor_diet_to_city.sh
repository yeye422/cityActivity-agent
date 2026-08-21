#!/bin/bash
# 全面重构 diet → city
# 包括：包名、类名、表名、配置

cd "$(dirname "$0")"

echo "=========================================="
echo "开始全面重构 diet → city"
echo "=========================================="
echo ""

# 备份当前状态
echo "步骤1: 创建备份..."
timestamp=$(date +%Y%m%d_%H%M%S)
backup_dir="../diet-agent-backup-before-city-rename-$timestamp"
cp -r . "$backup_dir"
echo "✅ 备份完成: $backup_dir"
echo ""

# 步骤2: 重命名所有 Java 文件中的包声明和 import
echo "步骤2: 修改包声明和 import 语句..."
find src/main/java -name "*.java" -type f | while read file; do
    # 修改 package 声明
    sed -i 's/^package com\.diet\./package com.city./g' "$file"

    # 修改 import 语句
    sed -i 's/^import com\.diet\./import com.city./g' "$file"

    # 修改类名前缀 Diet → City
    sed -i 's/\bDietApplication\b/CityApplication/g' "$file"
    sed -i 's/\bDietChatController\b/CityChatController/g' "$file"
    sed -i 's/\bDietOrchestratorService\b/CityOrchestratorService/g' "$file"
    sed -i 's/\bDietAgentScopeConfig\b/CityAgentScopeConfig/g' "$file"
    sed -i 's/\bDietConstants\b/CityConstants/g' "$file"
    sed -i 's/\bDietException\b/CityException/g' "$file"
done
echo "✅ Java 文件包声明和 import 修改完成"
echo ""

# 步骤3: 重命名目录结构
echo "步骤3: 重命名目录结构..."
if [ -d "src/main/java/com/diet" ]; then
    mv src/main/java/com/diet src/main/java/com/city
    echo "✅ 主目录重命名: com/diet → com/city"
fi

if [ -d "src/test/java/com/diet" ]; then
    mv src/test/java/com/diet src/test/java/com/city
    echo "✅ 测试目录重命名: com/diet → com/city"
fi
echo ""

# 步骤4: 重命名 Java 类文件
echo "步骤4: 重命名 Java 类文件..."
declare -A class_rename_map=(
    ["src/main/java/com/city/DietApplication.java"]="src/main/java/com/city/CityApplication.java"
    ["src/main/java/com/city/controller/chat/DietChatController.java"]="src/main/java/com/city/controller/chat/CityChatController.java"
    ["src/main/java/com/city/service/orchestrator/DietOrchestratorService.java"]="src/main/java/com/city/service/orchestrator/CityOrchestratorService.java"
    ["src/main/java/com/city/config/DietAgentScopeConfig.java"]="src/main/java/com/city/config/CityAgentScopeConfig.java"
    ["src/main/java/com/city/constants/DietConstants.java"]="src/main/java/com/city/constants/CityConstants.java"
    ["src/main/java/com/city/exception/DietException.java"]="src/main/java/com/city/exception/CityException.java"
)

for old_file in "${!class_rename_map[@]}"; do
    new_file="${class_rename_map[$old_file]}"
    if [ -f "$old_file" ]; then
        mv "$old_file" "$new_file"
        echo "  重命名: $(basename $old_file) → $(basename $new_file)"
    fi
done
echo "✅ Java 类文件重命名完成"
echo ""

# 步骤5: 修改 resources 文件
echo "步骤5: 修改 MyBatis Mapper namespace..."
find src/main/resources/mapper -name "*.xml" -type f | while read file; do
    sed -i 's/com\.diet\./com.city./g' "$file"
done
echo "✅ Mapper namespace 修改完成"
echo ""

# 步骤6: 修改配置文件
echo "步骤6: 修改配置文件..."
if [ -f "src/main/resources/application.yml" ]; then
    sed -i 's/diet\./city./g' src/main/resources/application.yml
    sed -i 's/com\.diet/com.city/g' src/main/resources/application.yml
    echo "✅ application.yml 修改完成"
fi

if [ -f "src/main/resources/application-dev.yml" ]; then
    sed -i 's/diet\./city./g' src/main/resources/application-dev.yml
    sed -i 's/com\.diet/com.city/g' src/main/resources/application-dev.yml
    echo "✅ application-dev.yml 修改完成"
fi

if [ -f "src/main/resources/application-prod.yml" ]; then
    sed -i 's/diet\./city./g' src/main/resources/application-prod.yml
    sed -i 's/com\.diet/com.city/g' src/main/resources/application-prod.yml
    echo "✅ application-prod.yml 修改完成"
fi
echo ""

# 步骤7: 修改 pom.xml
echo "步骤7: 修改 pom.xml..."
if [ -f "pom.xml" ]; then
    sed -i 's/<artifactId>diet-agent<\/artifactId>/<artifactId>city-agent<\/artifactId>/g' pom.xml
    sed -i 's/<name>diet-agent<\/name>/<name>city-agent<\/name>/g' pom.xml
    sed -i 's/<description>.*<\/description>/<description>City Activity Agent<\/description>/g' pom.xml
    echo "✅ pom.xml 修改完成"
fi
echo ""

echo "=========================================="
echo "✅ 全面重构完成！"
echo "=========================================="
echo ""
echo "⚠️  后续步骤："
echo "1. 执行数据库表重命名 SQL (见 database_rename_tables.sql)"
echo "2. 测试编译: mvn clean compile"
echo "3. 运行测试: mvn test"
echo ""
echo "📦 备份位置: $backup_dir"
