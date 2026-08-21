@echo off
chcp 65001 >nul
echo ====================================
echo 城市周末活动测试数据导入脚本
echo ====================================
echo.

set DB_HOST=localhost
set DB_PORT=3306
set DB_NAME=diet_db
set DB_USER=root
set DB_PASS=123456

echo 正在导入测试数据到数据库 %DB_NAME%...
echo.

mysql -h%DB_HOST% -P%DB_PORT% -u%DB_USER% -p%DB_PASS% %DB_NAME% < "%~dp0test_activity_data.sql"

if %errorlevel% equ 0 (
    echo.
    echo ✓ 数据导入成功！
    echo.
    echo 正在验证数据...
    mysql -h%DB_HOST% -P%DB_PORT% -u%DB_USER% -p%DB_PASS% -e "SELECT COUNT(*) as total_count FROM meal_item WHERE sourceType='PUBLIC';" %DB_NAME%
    echo.
    echo 按任意键查看各类型活动统计...
    pause >nul
    mysql -h%DB_HOST% -P%DB_PORT% -u%DB_USER% -p%DB_PASS% -e "SELECT JSON_UNQUOTE(JSON_EXTRACT(cuisine, '$[0]')) as activity_type, COUNT(*) as count FROM meal_item WHERE sourceType='PUBLIC' GROUP BY JSON_UNQUOTE(JSON_EXTRACT(cuisine, '$[0]'));" %DB_NAME%
) else (
    echo.
    echo ✗ 数据导入失败！请检查：
    echo   1. MySQL 服务是否启动
    echo   2. 数据库连接信息是否正确
    echo   3. test_activity_data.sql 文件是否存在
)

echo.
echo 按任意键退出...
pause >nul
