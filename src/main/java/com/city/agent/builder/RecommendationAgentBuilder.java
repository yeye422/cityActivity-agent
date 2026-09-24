package com.city.agent.builder;

import com.city.agent.loader.PromptLoader;
import com.city.model.context.VerifiedRequestContext;
import com.city.service.evidence.CandidateEvidenceRegistry;
import com.city.service.harness.AgentBusinessGuardMiddleware;
import com.city.service.trace.AgentTraceService;
import com.city.tool.RetrievalTool;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;

/** 构建单次执行使用的 RecommendationAgent。 */
@Component
public class RecommendationAgentBuilder {
    private final Model mainModel;
    private final PromptLoader promptLoader;
    private final RetrievalTool retrievalTool;
    private final AgentTraceService traceService;

    /** 保留现有纯单测构造方式。 */
    public RecommendationAgentBuilder(
            Model mainModel,
            PromptLoader promptLoader,
            RetrievalTool retrievalTool
    ) {
        this(mainModel, promptLoader, retrievalTool, null);
    }

    @Autowired
    public RecommendationAgentBuilder(
            @Qualifier("CityMainChatModel") Model mainModel,
            PromptLoader promptLoader,
            RetrievalTool retrievalTool,
            AgentTraceService traceService
    ) {
        this.mainModel = mainModel;
        this.promptLoader = promptLoader;
        this.retrievalTool = retrievalTool;
        this.traceService = traceService;
    }

    public ReActAgent build(VerifiedRequestContext verifiedContext,
                            CandidateEvidenceRegistry evidenceRegistry) {
        Objects.requireNonNull(verifiedContext, "verifiedContext");
        Objects.requireNonNull(evidenceRegistry, "evidenceRegistry");

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(retrievalTool);

        ToolExecutionContext toolContext = ToolExecutionContext.builder()
                .register(verifiedContext)
                .register(evidenceRegistry)
                .build();

        AgentBusinessGuardMiddleware guardMiddleware = new AgentBusinessGuardMiddleware(
                "city_recommendation_agent",
                Set.of("search_activities"),
                2,
                3,
                traceService,
                verifiedContext.traceId()
        );

        return ReActAgent.builder()
                .name("city_recommendation_agent")
                .model(mainModel)
                .sysPrompt(promptLoader.load("city-prompts/recommendation-decision.txt"))
                .toolkit(toolkit)
                .toolExecutionContext(toolContext)
                .memory(new InMemoryMemory())
                .hook(guardHook)
                .maxIters(4)
                .build();
    }
}
