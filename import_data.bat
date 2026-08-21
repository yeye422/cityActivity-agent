@echo off
REM Import test activity data to diet_db

SET DB_HOST=localhost
SET DB_PORT=3306
SET DB_NAME=diet_db
SET DB_USER=root
SET DB_PASS=123456
SET SQL_FILE=%~dp0src\main\resources\db\test_activity_data.sql

echo Importing test data...
echo Database: %DB_NAME%
echo SQL File: %SQL_FILE%
echo.

mysql -h%DB_HOST% -P%DB_PORT% -u%DB_USER% -p%DB_PASS% %DB_NAME% < "%SQL_FILE%"

if %errorlevel% equ 0 (
    echo.
    echo Import successful!
    echo.
    mysql -h%DB_HOST% -P%DB_PORT% -u%DB_USER% -p%DB_PASS% -e "SELECT COUNT(*) as total_public_activities FROM meal_item WHERE sourceType='PUBLIC';" %DB_NAME%
) else (
    echo.
    echo Import failed! Error code: %errorlevel%
    echo Please check:
    echo   1. MySQL service is running
    echo   2. Database credentials are correct
    echo   3. SQL file exists at: %SQL_FILE%
)

echo.
pause
