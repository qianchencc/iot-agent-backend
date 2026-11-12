<template>
  <el-drawer v-model="drawerOpen" title="操作详情" size="min(560px, 100vw)">
    <div v-loading="detailLoading">
      <el-alert v-if="detailError" :title="detailError" type="error" show-icon class="drawer-alert" />
      <template v-if="detail?.payload">
        <el-alert
          v-if="detail.payload.payloadUnreadable"
          title="原始终端载荷不可读取，仅显示可投影字段"
          type="warning"
          show-icon
          class="drawer-alert"
        />

        <section class="conversation-detail">
          <div class="conversation-meta">
            <span>{{ formatTime(detail.createdAt ?? detail.payload.at) }}</span>
            <span>{{ memberName(detail.payload) }}</span>
            <el-tag :type="statusType(resultOf(detail.payload, detail.eventType))" size="small">
              {{ statusLabel(resultOf(detail.payload, detail.eventType)) }}
            </el-tag>
          </div>

          <div v-if="userBlockText" class="message-block">
            <div class="message-label">{{ userBlockLabel }}</div>
            <p class="message-text">{{ userBlockText }}</p>
          </div>

          <div v-if="replyBlockText" class="message-block assistant-message">
            <div class="message-label">模型回答</div>
            <p class="message-text">{{ replyBlockText }}</p>
          </div>

          <div v-if="questionBlockText" class="message-block">
            <div class="message-label">{{ questionBlockLabel }}</div>
            <p class="message-text">{{ questionBlockText }}</p>
          </div>

          <div v-if="summaryBlockText" class="message-block">
            <div class="message-label">摘要</div>
            <p class="message-text">{{ summaryBlockText }}</p>
          </div>

          <div v-if="proposalBlock" class="message-block">
            <div class="message-label">任务内容</div>
            <div class="proposal-card">
              <div class="proposal-title">{{ proposalBlock.label || proposalBlock.title || "未命名任务" }}</div>
              <div class="proposal-meta">
                <span v-if="proposalBlock.operation">{{ operationLabel(proposalBlock.operation) }}</span>
                <span v-if="proposalBlock.targetLabel">目标：{{ proposalBlock.targetLabel }}</span>
                <span v-if="proposalSchedule(proposalBlock)">{{ proposalSchedule(proposalBlock) }}</span>
                <span v-if="proposalBlock.taskKind">{{ taskKindLabel(proposalBlock.taskKind) }}</span>
              </div>
              <el-table v-if="proposalBlock.actions?.length" :data="proposalBlock.actions" size="small" class="detail-table">
                <el-table-column label="时间" width="150">
                  <template #default="{ row }">{{ row.at ? formatTime(row.at) : "—" }}</template>
                </el-table-column>
                <el-table-column label="设备" min-width="140" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.deviceName || row.name || row.deviceId || "—" }}</template>
                </el-table-column>
                <el-table-column label="位置" min-width="110" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.location || "—" }}</template>
                </el-table-column>
                <el-table-column label="指令" min-width="90">
                  <template #default="{ row }">{{ commandLabel(row) }}</template>
                </el-table-column>
              </el-table>
            </div>
          </div>
        </section>

        <el-collapse class="advanced-details">
          <el-collapse-item name="facts" title="审计详情">
            <el-descriptions :column="1" bordered size="small">
              <el-descriptions-item label="时间">{{ formatTime(detail.createdAt ?? detail.payload.at) }}</el-descriptions-item>
              <el-descriptions-item label="成员">{{ memberName(detail.payload) }}</el-descriptions-item>
              <el-descriptions-item label="操作者">{{ detail.payload.actor || "成员本人（未另记操作者）" }}</el-descriptions-item>
              <el-descriptions-item label="身份域">{{ detail.payload.identityRealm || "未知" }}</el-descriptions-item>
              <el-descriptions-item label="来源">{{ sourceLabel(detail) }}</el-descriptions-item>
              <el-descriptions-item label="操作">{{ eventLabel(detail.eventType, detail.payload.action) }}</el-descriptions-item>
              <el-descriptions-item label="结果">{{ statusLabel(resultOf(detail.payload, detail.eventType)) }}</el-descriptions-item>
              <el-descriptions-item v-if="detail.payload.result" label="执行结果">{{ resultSummary }}</el-descriptions-item>
              <el-descriptions-item label="创建者姓名">{{ detail.payload.creatorName || "未记录" }}</el-descriptions-item>
              <el-descriptions-item label="确认时间">{{ formatTime(detail.payload.confirmedAt) === "—" ? "未记录" : formatTime(detail.payload.confirmedAt) }}</el-descriptions-item>
              <el-descriptions-item label="上游下发">{{ dispatchLabel(detail.payload.dispatchStarted) }}</el-descriptions-item>
              <el-descriptions-item label="记录 ID">{{ detail.eventId }}</el-descriptions-item>
            </el-descriptions>

            <section class="detail-section">
              <h4 class="detail-heading">关联业务事实（审计快照）</h4>
              <el-descriptions :column="1" bordered size="small">
                <el-descriptions-item
                  v-for="item in relatedFacts"
                  :key="item.label"
                  :label="item.label"
                >
                  <span :class="{ muted: item.absent }">{{ item.value }}</span>
                </el-descriptions-item>
              </el-descriptions>
              <p class="muted related-note">以上事实取自本条审计快照，仅作只读核对，不跳转成员业务页面。</p>
            </section>

            <section v-if="detail.payload.summary" class="detail-section">
              <h4 class="detail-heading">摘要</h4>
              <p class="detail-text">{{ detail.payload.summary }}</p>
            </section>

            <template v-if="detailActions.length">
              <h4 class="detail-heading">动作（{{ detailActions.length }}）</h4>
              <el-table :data="detailActions" size="small" class="detail-table">
                <el-table-column label="设备" min-width="140" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.name || row.deviceName || row.deviceId || "—" }}</template>
                </el-table-column>
                <el-table-column label="位置" min-width="110" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.location || row.roomName || "—" }}</template>
                </el-table-column>
                <el-table-column label="指令" min-width="90">
                  <template #default="{ row }">{{ commandLabel(row) }}</template>
                </el-table-column>
                <el-table-column label="结果" width="80">
                  <template #default="{ row }">{{ actionOutcome(row) }}</template>
                </el-table-column>
              </el-table>
            </template>

            <section v-if="detail.payload.result" class="detail-section">
              <h4 class="detail-heading">上游受理回执</h4>
              <el-table v-if="detailReceipts.length" :data="detailReceipts" size="small" class="detail-table">
                <el-table-column label="动作 / 设备" min-width="180" show-overflow-tooltip>
                  <template #default="{ row }">{{ deviceLabelOf(row.actionId) }}</template>
                </el-table-column>
                <el-table-column label="受理结果" width="110">
                  <template #default="{ row }">{{ receiptOutcome(row.outcome) }}</template>
                </el-table-column>
                <el-table-column prop="reason" label="说明" min-width="140" show-overflow-tooltip />
              </el-table>
              <p v-else class="muted">无上游回执记录（上游未回执或未保留）</p>
            </section>

            <section v-if="detail.payload.result" class="detail-section">
              <h4 class="detail-heading">状态回读</h4>
              <el-table v-if="readbackRows.length" :data="readbackRows" size="small" class="detail-table">
                <el-table-column label="动作 / 设备" min-width="180" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.device }}</template>
                </el-table-column>
                <el-table-column label="回读值" min-width="140">
                  <template #default="{ row }">{{ row.value }}</template>
                </el-table-column>
              </el-table>
              <p v-else class="muted">无状态回读记录（未回读或未保留）</p>
            </section>
          </el-collapse-item>
        </el-collapse>
      </template>
    </div>
  </el-drawer>
</template>

<script setup>
import { computed, ref, watch } from "vue";
import { adminApi, errorOf } from "../api/admin";

const props = defineProps({
  detail: { type: Object, default: null },
  member: { type: Object, default: null },
  eventTypeOptions: { type: Array, default: () => [] }
});
const emit = defineEmits(["update:detail"]);

const STATUS_LABELS = {
  STARTED: "已发起",
  COMPLETED: "已完成",
  FAILED: "失败",
  ABORTED: "已中止",
  AWAITING_CONFIRMATION: "待确认",
  PENDING_CONFIRMATION: "待确认",
  PARTIALLY_ACCEPTED: "部分完成",
  EXPIRED: "已过期",
  PARTIAL: "部分完成",
  SATISFIED: "已满足",
  UNCONFIRMED: "未确认",
  MISSED: "已错过",
  ACCEPTED: "已接受",
  CONFIRMED: "已确认",
  RESOLVED: "已完成",
  CANCELLED: "已取消",
  INTERRUPTED: "已中断",
  REJECTED: "已拒绝",
  DENIED: "已拒绝",
  NO_CHANGE: "无变化",
  DONE: "已完成",
  UNKNOWN: "未知"
};

const STATUS_TYPES = {
  STARTED: "info",
  COMPLETED: "success",
  FAILED: "danger",
  ABORTED: "warning",
  AWAITING_CONFIRMATION: "warning",
  PENDING_CONFIRMATION: "warning",
  PARTIALLY_ACCEPTED: "warning",
  EXPIRED: "info",
  PARTIAL: "warning",
  SATISFIED: "success",
  UNCONFIRMED: "warning",
  MISSED: "info",
  ACCEPTED: "success",
  CONFIRMED: "success",
  RESOLVED: "success",
  CANCELLED: "info",
  INTERRUPTED: "warning",
  REJECTED: "danger",
  DENIED: "danger",
  NO_CHANGE: "info",
  DONE: "success",
  UNKNOWN: "info"
};

const OUTCOME_LABELS = {
  SUCCESS: "成功", FAILED: "失败", REJECTED: "拒绝", UNKNOWN: "未知", SKIPPED: "跳过",
  ALREADY_SATISFIED: "无需操作", ACCEPTED: "已受理"
};

const SOURCE_LABELS = { company: "公司 Agent", office: "办公助手" };
const ADMIN_ORIGIN_EVENTS = new Set([
  "PHONE_ALLOWLIST_ADDED", "PHONE_ALLOWLIST_REMOVED",
  "COMPANY_AGENT_ALLOWLIST_ADDED", "COMPANY_AGENT_ALLOWLIST_REMOVED"
]);

const detailLoading = ref(false);
const detailError = ref("");
const drawerDetail = ref(null);
const drawerOpen = computed({
  get: () => props.detail !== null,
  set: (open) => { if (!open) emit("update:detail", null); }
});
const detail = computed(() => drawerDetail.value ?? props.detail);

// Conversation runs carry request/reply; draft and task events carry a
// question/summary instead. Show only the blocks the payload actually has —
// never a misleading "not recorded" placeholder on non-conversation events.
const CONVERSATION_RUN_EVENTS = new Set([
  "AGENT_REQUESTED", "AGENT_RUN_COMPLETED", "AGENT_RUN_FAILED",
  "AGENT_RUN_ABORTED", "AGENT_RUN_AWAITING_CONFIRMATION"
]);
const isConversationRun = computed(() => CONVERSATION_RUN_EVENTS.has(detail.value?.eventType));
const payloadText = (value) => (typeof value === "string" && value.trim() ? value : null);
const userBlockText = computed(() => {
  const payload = detail.value?.payload;
  if (!payload) return null;
  return payloadText(payload.request) ?? payloadText(payload.question);
});
const userBlockLabel = computed(() => payloadText(detail.value?.payload?.request) ? "用户" : "问题");
const replyBlockText = computed(() => {
  const payload = detail.value?.payload;
  if (!payload) return null;
  const reply = payloadText(payload.reply);
  if (reply) return reply;
  // A finished run without a reply is meaningful; other events never carry one.
  return isConversationRun.value ? "未记录模型回答" : null;
});
const questionBlockText = computed(() => {
  const payload = detail.value?.payload;
  if (!payload || payloadText(payload.request)) return null;
  return payloadText(payload.question);
});
const questionBlockLabel = computed(() => isConversationRun.value ? "待确认问题" : "关联问题");

const summaryBlockText = computed(() => {
  // Events without a question/answer pair (drafts, tasks, admin actions) still
  // deserve one readable line up front; the full snapshot stays collapsed.
  // Task proposals carry `label` (the task name) instead of `summary`.
  if (userBlockText.value || replyBlockText.value || questionBlockText.value) return null;
  const payload = detail.value?.payload;
  return payloadText(payload?.summary) ?? payloadText(payload?.label);
});

const proposalBlock = computed(() => detail.value?.proposal ?? null);
const PROPOSAL_OPERATION_LABELS = { create: "新建", edit: "修改", skip: "跳过", cancel: "取消" };
const TASK_KIND_LABELS = { interval: "间隔任务", conditional: "条件任务", recurring: "周期任务" };
const REPEAT_FREQUENCY_LABELS = { once: "单次", daily: "每天", weekdays: "工作日", weekend: "周末", weekly: "每周", monthly: "每月" };
function operationLabel(op) { return PROPOSAL_OPERATION_LABELS[op] ?? op ?? "—"; }
function taskKindLabel(kind) { return TASK_KIND_LABELS[kind] ?? kind ?? "—"; }
function proposalSchedule(proposal) {
  if (!proposal) return null;
  const parts = [];
  if (proposal.interval?.startsAt) parts.push(`${formatTime(proposal.interval.startsAt)} 起`);
  if (proposal.interval?.endsAt) parts.push(`${formatTime(proposal.interval.endsAt)} 止`);
  if (proposal.repeat?.frequency) parts.push(REPEAT_FREQUENCY_LABELS[proposal.repeat.frequency] ?? proposal.repeat.frequency);
  return parts.length ? parts.join(" · ") : null;
}

const detailActions = computed(() => {
  const actions = detail.value?.payload?.actions;
  return Array.isArray(actions) ? actions : [];
});
const detailReceipts = computed(() => {
  const receipts = detail.value?.payload?.result?.receipts;
  return Array.isArray(receipts) ? receipts : [];
});
const detailResult = computed(() => detail.value?.payload?.result ?? null);
const READBACK_VALUE_LABELS = { confirmed: "已确认", unconfirmed: "未确认" };
const readbackRows = computed(() => {
  const readback = detailResult.value?.readback;
  if (!readback || typeof readback !== "object") return [];
  return Object.entries(readback).map(([actionId, value]) => ({
    actionId,
    device: deviceLabelOf(actionId),
    value: value === null || value === undefined || value === ""
      ? "—"
      : (READBACK_VALUE_LABELS[value] ?? String(value))
  }));
});
const resultSummary = computed(() => {
  const result = detailResult.value;
  if (!result) return "未记录";
  const label = statusLabel(result.status);
  return result.message ? `${label}（${result.message}）` : label;
});
const executionFinished = computed(() => Boolean(detailResult.value));
const relatedFacts = computed(() => {
  const payload = detail.value?.payload ?? {};
  const fact = (label, value) => ({
    label,
    value: value === null || value === undefined || value === "" ? "未记录" : String(value),
    absent: value === null || value === undefined || value === ""
  });
  return [
    fact("草案 ID", payload.draftId),
    fact("任务 ID", payload.taskId),
    fact("提案 ID", payload.proposalId),
    fact("计划 ID", payload.planId),
    fact("阶段 ID", payload.phaseId),
    fact("取消提案 ID", payload.cancellationId),
    fact("报告 ID", payload.reportId),
    fact("会话 ID", payload.conversationId),
    fact("轮次 ID", payload.turnId),
    fact("运行 ID", payload.runId),
    fact("业务标签", payload.label),
    fact("类别", payload.kind),
    fact("操作类别", payload.operation),
    fact("修订号", payload.revision),
    fact("归属键（系统执行）", payload.ownerKey),
    fact("到期时间", timeOrUnrecorded(payload.expiresAt)),
    fact("结束时间", timeOrUnrecorded(payload.endedAt)),
    fact("观察时间", timeOrUnrecorded(payload.observedAt)),
    ...countFacts(payload.counts)
  ];
});

const COUNT_LABELS = { devices: "设备数", verified: "已验证", unconfirmed: "未确认" };

function countFacts(counts) {
  if (!counts || typeof counts !== "object") return [];
  return Object.entries(counts).map(([key, value]) => ({
    label: COUNT_LABELS[key] ?? key,
    value: typeof value === "number" || typeof value === "boolean" ? String(value) : "未记录",
    absent: !(typeof value === "number" || typeof value === "boolean")
  }));
}

function memberName(row) {
  const member = props.member;
  return row.displayName || row.mobileNumber
    || (member?.principalId === row.principalId ? member.displayName || member.mobileNumber : null)
    || row.principalId || row.actor || "未识别主体";
}

function eventLabel(eventType, action) {
  const labels = {};
  for (const item of props.eventTypeOptions) labels[item.type] = item.label;
  return labels[eventType] ?? action ?? eventType ?? "—";
}

function sourceLabel(detail) {
  if (ADMIN_ORIGIN_EVENTS.has(detail.eventType)) return "管理控制台";
  return SOURCE_LABELS[detail.payload?.source] ?? detail.payload?.source ?? "—";
}

function statusLabel(value) {
  return STATUS_LABELS[value] ?? value ?? "未知";
}

function statusType(value) {
  return STATUS_TYPES[value] ?? "info";
}

function resultOf(payload, eventType) {
  const status = payload?.status;
  if (status === "run.completed") return "COMPLETED";
  if (status === "run.failed") return "FAILED";
  if (status === "run.aborted") return "ABORTED";
  if (status === "run.awaiting_confirmation") return "AWAITING_CONFIRMATION";
  if (status === "started") return "STARTED";
  return status ?? resultOfEventType(eventType);
}

function resultOfEventType(eventType) {
  switch (eventType) {
    case "COMPANY_AGENT_DENIED": return "DENIED";
    case "PHONE_ALLOWLIST_ADDED":
    case "PHONE_ALLOWLIST_REMOVED":
    case "COMPANY_AGENT_ALLOWLIST_ADDED":
    case "COMPANY_AGENT_ALLOWLIST_REMOVED": return "DONE";
    case "IMMEDIATE_TASK_RECORD_FAILED":
    case "AGENT_RUN_FAILED": return "FAILED";
    case "AGENT_REQUESTED": return "STARTED";
    case "AGENT_RUN_COMPLETED": return "COMPLETED";
    case "AGENT_RUN_AWAITING_CONFIRMATION": return "AWAITING_CONFIRMATION";
    case "AGENT_RUN_ABORTED": return "ABORTED";
    case "TASK_PROPOSED":
    case "TASK_CHANGE_PROPOSED":
    case "TASK_CANCELLATION_PROPOSED": return "PENDING_CONFIRMATION";
    default: return "UNKNOWN";
  }
}

function commandLabel(row) {
  const command = row.command;
  if (!command || typeof command !== "object") return row.kind || "—";
  if ("closeOrOpen" in command) return Number(command.closeOrOpen) === 1 ? "开" : "关";
  return Object.entries(command).map(([key, value]) => `${key}=${value}`).join(" ");
}

function actionOutcome(row) {
  const receipt = detailReceipts.value.find((item) => item.actionId === row.id);
  if (receipt) return receiptOutcome(receipt.outcome);
  if (executionFinished.value) return "无回执（未记录）";
  return row.satisfied ? "无需操作" : "待执行";
}

function deviceLabelOf(actionId) {
  const action = detailActions.value.find((item) => item.id === actionId);
  const device = action ? (action.name || action.deviceName || action.deviceId) : null;
  return device ? `${device}（${actionId}）` : actionId || "—";
}

function dispatchLabel(dispatchStarted) {
  if (dispatchStarted === true) return "已开始下发";
  if (dispatchStarted === false) return "未开始下发";
  return "未记录";
}

function timeOrUnrecorded(value) {
  return value ? formatTime(value) : null;
}

function receiptOutcome(outcome) {
  return OUTCOME_LABELS[outcome] ?? outcome ?? "—";
}

const formatter = new Intl.DateTimeFormat("zh-CN", {
  year: "numeric", month: "2-digit", day: "2-digit",
  hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false
});

function formatTime(value) {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : formatter.format(date);
}

let requestSequence = 0;
watch(() => props.detail?.eventId, async (eventId) => {
  const requestId = ++requestSequence;
  detailLoading.value = Boolean(eventId);
  detailError.value = "";
  drawerDetail.value = props.detail;
  if (!eventId) return;
  try {
    const response = await adminApi.auditDetail(eventId);
    if (requestId === requestSequence) drawerDetail.value = response.data;
  } catch (cause) {
    if (requestId === requestSequence) detailError.value = errorOf(cause, "无法读取详情").message;
  } finally {
    if (requestId === requestSequence) detailLoading.value = false;
  }
}, { immediate: true });
</script>

<style scoped>
.drawer-alert { margin-bottom: 12px; }
.conversation-detail { padding-top: 4px; }
.conversation-meta { display: flex; flex-wrap: wrap; gap: 8px 12px; align-items: center; margin-bottom: 20px; color: var(--text-muted); font-size: 12px; }
.message-block { margin-top: 18px; padding-top: 18px; border-top: 1px solid var(--border-subtle); }
.message-label { margin-bottom: 8px; color: var(--text-muted); font-size: 12px; font-weight: 600; }
.assistant-message .message-label { color: var(--el-color-primary); }
.message-text { margin: 0; color: var(--text-strong); font-size: 14px; line-height: 1.7; white-space: pre-wrap; word-break: break-word; }
.advanced-details { margin-top: 24px; border-top: 1px solid var(--border-subtle); }
.advanced-details :deep(.el-collapse-item__header) { height: 44px; padding: 0 4px; font-size: 13px; font-weight: 600; color: var(--text-strong); }
.advanced-details :deep(.el-collapse-item__content) { padding-bottom: 4px; }
.muted { color: var(--text-muted); font-size: 12px; }
.related-note { margin: 8px 0 0; }
.detail-section { margin-top: 16px; }
.detail-heading { margin: 16px 0 8px; color: var(--text-strong); font-size: 14px; }
.detail-text { margin: 0; color: var(--text-strong); font-size: 13px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
.detail-table { margin-bottom: 4px; }
.proposal-card { padding: 12px 14px; background: var(--surface-subtle, #f7f8fa); border: 1px solid var(--border-subtle); border-radius: 8px; }
.proposal-title { font-size: 14px; font-weight: 600; color: var(--text-strong); margin-bottom: 6px; }
.proposal-meta { display: flex; flex-wrap: wrap; gap: 4px 14px; font-size: 12px; color: var(--text-muted); margin-bottom: 8px; }
</style>
