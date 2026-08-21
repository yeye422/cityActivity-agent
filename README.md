# City Activity Agent

城市活动智能推荐 Agent。

## 项目介绍

City Activity Agent 是一个基于 AI 的城市活动推荐系统，根据用户偏好、时间、场景和预算生成活动建议。

## 技术栈

- Java
- Spring Boot
- MyBatis
- MySQL
- LLM Agent

## 核心功能

- 城市活动推荐
- 活动搜索
- 活动排序
- 用户偏好匹配
- Agent 编排

## 数据库初始化

首次部署执行：

```bash
mysql < database_init_final.sql
```

## 本地运行

```bash
mvn spring-boot:run
```

## 配置

主要配置文件：

```
src/main/resources/application.yml
```

## 项目结构

```
src/
├── main/java        # 后端代码
├── main/resources   # 配置与 Mapper

database_init_final.sql # 最终数据库初始化脚本
```
