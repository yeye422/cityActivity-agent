package com.city;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 城市活动推荐 Agent 应用启动入口。
 * <p>
 * 与 {@code com.agent.AgentApplication} 相互独立：本类只扫描 {@code com.city} 包下的组件，
 * 运行时使用 {@code com.city.CityApplication} 作为主类即可启动城市活动推荐服务。
 * </p>
 */
@MapperScan("com.city.mapper")
@SpringBootApplication
public class CityApplication {

    public static void main(String[] args) {
        SpringApplication.run(CityApplication.class, args);
    }
}
