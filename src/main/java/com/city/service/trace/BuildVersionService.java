package com.city.service.trace;

import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Properties;

/**
 * 读取 Maven 构建阶段生成的 git.properties，并缓存当前构建对应的 Git commit。
 * promptVersion/ruleVersion 表示人工维护的语义版本；gitCommit 用于精确定位实际运行代码。
 */
@Component
public class BuildVersionService {

    static final String UNKNOWN = "unknown";

    private final String gitCommit;

    public BuildVersionService() {
        this.gitCommit = loadGitCommit();
    }

    public String gitCommit() {
        return gitCommit;
    }

    private String loadGitCommit() {
        Properties properties = new Properties();
        try (InputStream input = BuildVersionService.class.getClassLoader().getResourceAsStream("git.properties")) {
            if (input == null) {
                return UNKNOWN;
            }
            properties.load(input);
            return resolveGitCommit(properties);
        } catch (Exception ignored) {
            return UNKNOWN;
        }
    }

    static String resolveGitCommit(Properties properties) {
        if (properties == null) {
            return UNKNOWN;
        }
        String abbreviated = normalize(properties.getProperty("git.commit.id.abbrev"));
        if (abbreviated != null) {
            return abbreviated;
        }
        String full = normalize(properties.getProperty("git.commit.id.full"));
        return full == null ? UNKNOWN : full;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
