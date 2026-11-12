package com.ehe.iotagent.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import com.ehe.iotagent.auth.PrincipalInfo;
import com.ehe.iotagent.conversation.ConversationFacade;
import com.ehe.iotagent.error.BusinessException;

/** Runtime task-confirmation recovery: ask for a decision, never reuse human authorization. */
public final class TaskConfirmationService {
    private final TaskFacade tasks;
    private final ConversationFacade conversations;
    private final ObjectMapper mapper;

    public TaskConfirmationService(TaskFacade tasks, ConversationFacade conversations, ObjectMapper mapper) {
        this.tasks = tasks;
        this.conversations = conversations;
        this.mapper = mapper;
    }

    public Map<String, Object> confirm(PrincipalInfo principal, String proposalId, long version, String source) {
        String requestedSource = source == null ? "office" : source;
        TaskProposalFact proposal = tasks.proposal(principal, proposalId, requestedSource);
        var payload = mapper.valueToTree(tasks.proposalView(proposal).get("payload"));
        String conversationId = payload.path("conversationId").asText();
        String turnId = payload.path("turnId").asText();
        conversations.requireIdle(principal, conversationId, requestedSource);
        Map<String, Object> result;
        try {
            result = tasks.confirmProposal(principal, proposalId, version, requestedSource);
        } catch (BusinessException failure) {
            String code = failure.code();
            boolean time = "TASK_TIME_PASSED".equals(code) || "TASK_EXPIRED".equals(code);
            boolean scope = "TASK_DEVICE_AUTHORIZATION_UNAVAILABLE".equals(code) && failure.httpStatus() == 403;
            if (time || scope) {
                String decisionCode = time ? code : "TASK_DEVICE_UNAVAILABLE";
                String message = time ? "原定时间已过，需要新的执行时间；原任务目标已保留"
                        : "原任务设备范围已变化，需要核实并重新确认；原目标已保留";
                conversations.continueTaskConfirmation(principal, conversationId, turnId, proposalId,
                        requestedSource, decisionCode, message);
                throw new BusinessException(decisionCode, message,
                        Map.of("kind", time ? "needs_new_time" : "needs_new_scope"), 409);
            }
            if (failure.httpStatus() >= 500 || failure.httpStatus() == 401 || failure.httpStatus() == 403) {
                conversations.recordTaskConfirmation(principal, conversationId, turnId, proposalId, requestedSource,
                        "blocked", "设备范围核实未完成，原提案和目标已保留", code);
            }
            throw failure;
        }
        conversations.recordTaskConfirmation(principal, conversationId, turnId, proposalId, requestedSource,
                "completed", "原任务已核实并创建", null);
        return result;
    }
}
