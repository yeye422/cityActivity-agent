package com.city.service.evaluation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReActEvaluationProfileTest {

    @Test
    void reactEvalProfileShouldEnableRecommendationAndPlanningReactRoutes() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
                "react-eval",
                new ClassPathResource("application-react-eval.yml")
        );
        PropertySource<?> source = sources.getFirst();

        assertEquals(Boolean.TRUE, source.getProperty("city.agent.recommendation-react.enabled"));
        assertEquals(Boolean.TRUE, source.getProperty("city.agent.planning-react.enabled"));
    }
}
