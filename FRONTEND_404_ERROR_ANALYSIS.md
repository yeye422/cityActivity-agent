# 🔍 前端 404 错误分析报告

## 问题现象
访问 `http://localhost:8080/#/city` 时出现 404 Not Found 错误

---

## 根本原因分析

### 1. **这是单页应用（SPA）的前端路由问题，不是后端 404**

`#/city` 是前端路由（Hash 路由），由前端 JavaScript 处理，不是后端 API 路由。

真正的问题可能是：
- ❌ 前端 JavaScript 中没有 `/city` 路由的处理逻辑
- ❌ 前端路由配置不完整
- ❌ app.js 加载失败

---

## 详细分析

### 问题1：前端路由缺失 `/city` 处理

**证据**：
```html
<!-- index.html 中的导航链接 -->
<a href="#/city" data-nav="/city">首页</a>
```

**问题**：
前端 JavaScript (app.js) 中可能没有定义 `/city` 路由的渲染函数。

**验证方法**：
1. 打开浏览器控制台（F12）
2. 查看 Console 是否有 JavaScript 错误
3. 查看 Network 标签，确认：
   - `app.js` 是否加载成功
   - `api.js` 是否加载成功
   - `app.css` 是否加载成功

---

### 问题2：可能的错误场景

#### 场景A：JavaScript 加载失败
```
GET http://localhost:8080/assets/js/app.js  404 Not Found
```

**原因**：
- Spring Boot 静态资源路径配置问题
- 文件路径不匹配

**解决方案**：
检查 `application.yml` 中的静态资源配置

---

#### 场景B：前端路由未定义
```javascript
// app.js 中可能缺少 /city 路由处理
function route() {
    const hash = window.location.hash.slice(1) || "/city";
    
    if (hash === "/city") {
        renderHome();  // ❌ 这个函数可能不存在或有问题
    }
}
```

**解决方案**：
检查 app.js 中的路由处理逻辑

---

#### 场景C：API 请求失败
```javascript
// 前端尝试加载数据时 API 失败
async function renderHome() {
    const data = await CityApi.listPersonalActivities();  // ❌ API 返回 404
}
```

**原因**：
- 后端控制器路径不匹配
- 数据库表不存在
- 字段名不匹配

---

## 排查步骤

### 第1步：检查静态资源是否加载成功

打开浏览器开发者工具（F12），查看 Network 标签：

```
✅ 正常情况：
GET http://localhost:8080/                          200 OK
GET http://localhost:8080/assets/css/app.css        200 OK
GET http://localhost:8080/assets/js/api.js          200 OK
GET http://localhost:8080/assets/js/app.js          200 OK

❌ 异常情况：
GET http://localhost:8080/assets/js/app.js          404 Not Found
```

---

### 第2步：检查 Console 错误

打开浏览器开发者工具（F12），查看 Console 标签：

```javascript
// 可能的错误信息：
❌ Uncaught ReferenceError: CityApi is not defined
❌ Uncaught TypeError: Cannot read property 'innerHTML' of null
❌ Failed to load resource: the server responded with a status of 404
```

---

### 第3步：检查 API 请求

在 Network 标签中，筛选 XHR 请求：

```
可能失败的 API 请求：
❌ GET /api/v1/city/activities/personal  404 Not Found
❌ GET /api/v1/city/slot-options         404 Not Found
```

---

## 可能的具体原因

### 原因1：数据库表名未更新 ✅ 最可能

**问题**：
后端 Mapper XML 还在查询旧表名：
```sql
SELECT * FROM diet_sessions  -- ❌ 表不存在
```

**症状**：
- 前端加载页面成功
- 但 API 请求失败（500 错误或数据为空）
- 控制台显示数据库错误

**解决方案**：
执行数据库迁移脚本（已生成）

---

### 原因2：字段名不匹配 ✅ 很可能

**问题**：
前端使用新字段名，但后端还返回旧字段名：

```javascript
// 前端期望：
{
    activityTime: "周六上午",
    budget: "100元内",
    activityType: "运动"
}

// 后端实际返回：
{
    mealTime: "周六上午",      // ❌ 字段名不匹配
    healthGoal: "100元内",     // ❌ 字段名不匹配
    cuisine: "运动"            // ❌ 字段名不匹配
}
```

**症状**：
- API 请求成功（200 OK）
- 但前端无法正确显示数据
- 页面显示空白或异常

**解决方案**：
确保前后端字段名一致

---

### 原因3：静态资源路径配置问题 ⚠️ 可能

**问题**：
Spring Boot 静态资源路径配置不正确

**检查**：
```yaml
# application.yml
spring:
  web:
    resources:
      static-locations: classpath:/static/
  mvc:
    static-path-pattern: /**
```

---

## 快速诊断命令

### 1. 检查前端文件是否存在
```bash
ls -la src/main/resources/static/
ls -la src/main/resources/static/assets/js/
ls -la src/main/resources/static/assets/css/
```

### 2. 检查后端是否启动成功
```bash
# 查看日志
tail -f logs/application.log

# 或者访问
curl http://localhost:8080/api/v1/city/slot-options
```

### 3. 测试 API 是否正常
```bash
# 测试槽位选项 API
curl -X GET http://localhost:8080/api/v1/city/slot-options \
     -H "X-User-Id: 1"

# 测试活动列表 API
curl -X GET http://localhost:8080/api/v1/city/activities/personal \
     -H "X-User-Id: 1"
```

---

## 解决方案

### 方案1：清除浏览器缓存
```
1. 打开开发者工具（F12）
2. 右键点击刷新按钮
3. 选择"清空缓存并硬性重新加载"
```

### 方案2：检查数据库表是否存在
```sql
USE diet_db;
SHOW TABLES;

-- 期望结果：
-- city_sessions
-- city_messages
-- city_slot_option
-- activity_item
-- city_feedback
-- city_request_trace
```

### 方案3：执行数据库迁移
```bash
mysql diet_db < database_rename_columns.sql
```

### 方案4：重启后端服务
```bash
# 停止
Ctrl + C

# 重新编译
mvn clean package -DskipTests

# 启动
java -jar target/city-agent-0.0.1-SNAPSHOT.jar
```

---

## 需要你提供的信息

为了准确诊断问题，请提供：

1. **浏览器控制台截图**（F12 → Console 标签）
2. **Network 标签截图**（筛选 Failed 请求）
3. **后端启动日志**（最后50行）
4. **数据库表列表**
   ```sql
   SHOW TABLES;
   ```

---

## 总结

**最可能的原因**：
1. 数据库表名或列名未更新 ✅
2. 前后端字段名不匹配 ✅
3. API 路径不匹配 ⚠️

**下一步行动**：
1. 执行 `database_rename_columns.sql` 
2. 重启后端服务
3. 清除浏览器缓存
4. 重新访问 `http://localhost:8080/#/city`

---

**报告生成时间**: 2026-08-20  
**分析者**: Claude (Kiro AI Assistant)
