package com.city.service.worker;

import com.city.agent.builder.PlanningAgentBuilder;
import com.city.model.PlanCandidate;
import com.city.model.agent.PlanNotebook;
import com.city.model.agent.PlanProposal;
import com.city.model.agent.PlanValidationResult;
import com.city.model.agent.PlanningDecision;
import com.city.model.context.PlanningToolContext;
import com.city.service.plan.PlanningConstraintParser;
import com.city.service.trace.AgentTraceService;
import com.city.tool.PlanValidationTool;
import com.city.tool.PlanningDiscoveryTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlanningAgentWorkerTest {

    @Test
    void shouldLateValidatePrematureStructuredResponse() {
        PlanValidationTool validationTool = mock(PlanValidationTool.class);
        PlanningAgentWorker worker = worker(validationTool);
        PlanningToolContext context = mock(PlanningToolContext.class);
        PlanNotebook notebook = mock(PlanNotebook.class);
        when(context.notebook()).thenReturn(notebook);
        when(notebook.validated()).thenReturn(false);

        PlanProposal proposal = proposal();
        PlanningDecision decision = new PlanningDecision(proposal, "validated late", 0.8);
        when(validationTool.validate(proposal, context))
                .thenReturn(PlanValidationResult.valid(mock(PlanCandidate.class)));

        assertDoesNotThrow(() -> worker.ensureToolValidated(decision, context));
        verify(validationTool).validate(proposal, context);
    }

    @Test
    void shouldNotDuplicateValidationWhenAgentAlreadyValidatedPlan() {
        PlanValidationTool validationTool = mock(PlanValidationTool.class);
        PlanningAgentWorker worker = worker(validationTool);
        PlanningToolContext context = mock(PlanningToolContext.class);
        PlanNotebook notebook = mock(PlanNotebook.class);
        when(context.notebook()).thenReturn(notebook);
        when(notebook.validated()).thenReturn(true);

        worker.ensureToolValidated(new PlanningDecision(proposal(), "already valid", 0.9), context);

        verifyNoInteractions(validationTool);
    }

    @Test
    void shouldRejectPrematureStructuredResponseWhenLateValidationFails() {
        PlanValidationTool validationTool = mock(PlanValidationTool.class);
        PlanningAgentWorker worker = worker(validationTool);
        PlanningToolContext context = mock(PlanningToolContext.class);
        PlanNotebook notebook = mock(PlanNotebook.class);
        when(context.notebook()).thenReturn(notebook);
        when(notebook.validated()).thenReturn(false);

        PlanProposal proposal = proposal();
        PlanningDecision decision = new PlanningDecision(proposal, "invalid", 0.5);
        PlanValidationResult invalid = PlanValidationResult.invalid(List.of(
                new PlanValidationResult.Violation(
                        "TIME_CONFLICT",
                        "afternoon",
                        101L,
                        null,
                        "时间冲突",
                        "更换候选"
                )
        ));
        when(validationTool.validate(proposal, context)).thenReturn(invalid);

        assertThrows(IllegalStateException.class,
                () -> worker.ensureToolValidated(decision, context));
        verify(validationTool).validate(proposal, context);
    }

    private PlanningAgentWorker worker(PlanValidationTool validationTool) {
        return new PlanningAgentWorker(
                mock(PlanningAgentBuilder.class),
                mock(PlanningConstraintParser.class),
                validationTool,
                mock(PlanningDiscoveryTool.class),
                mock(AgentTraceService.class)
        );
    }

    private PlanProposal proposal() {
        return new PlanProposal(List.of(
                new PlanProposal.Item("afternoon", 101L, null)
        ));
    }
}
