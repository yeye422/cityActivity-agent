package com.city.service.trace;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BuildVersionServiceTest {

    @Test
    void shouldPreferAbbreviatedCommit() {
        Properties properties = new Properties();
        properties.setProperty("git.commit.id.abbrev", "abc1234");
        properties.setProperty("git.commit.id.full", "abc1234567890");
        assertEquals("abc1234", BuildVersionService.resolveGitCommit(properties));
    }

    @Test
    void shouldFallbackToFullCommit() {
        Properties properties = new Properties();
        properties.setProperty("git.commit.id.full", "abc1234567890");
        assertEquals("abc1234567890", BuildVersionService.resolveGitCommit(properties));
    }

    @Test
    void shouldReturnUnknownWhenGitMetadataIsMissing() {
        assertEquals(BuildVersionService.UNKNOWN, BuildVersionService.resolveGitCommit(new Properties()));
        assertEquals(BuildVersionService.UNKNOWN, BuildVersionService.resolveGitCommit(null));
    }
}
