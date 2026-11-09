<template>
  <div class="audit-page">
    <header class="page-header">
      <div>
        <h1 class="page-title">用户操作审计</h1>
        <p class="page-sub">左侧按成员索引，右侧查看该成员的公司／办公室操作：谁、何时、做了什么、结果如何。</p>
      </div>
    </header>

    <el-alert v-if="error" :title="error" type="error" show-icon closable class="error-alert" @close="error = ''" />

    <div class="split">
      <el-card shadow="never" class="member-card" :body-style="{ padding: '0' }">
        <div v-loading="membersLoading" class="member-toolbar">
          <el-select
            v-model="tenantCode"
            class="tenant-select"
            aria-label="园区"
            filterable
            allow-create
            default-first-option
            :reserve-keyword="false"
            placeholder="选择或输入园区码"
            :loading="tenantsLoading"
            @change="onTenantChange"
          >
            <el-option v-for="code in tenants" :key="code" :label="code" :value="code" />
          </el-select>
          <el-input
            v-model="memberSearch"
            class="member-search"
            placeholder="输入完整主体标识后查询"
            aria-label="按完整主体标识查询成员"
            clearable
            @keyup.enter="searchMembers"
            @clear="searchMembers"
          >
            <template #append><el-button @click="searchMembers">查询</el-button></template>
          </el-input>
          <button
            type="button"
            class="member-item"
            :class="{ active: selectedMember === null }"
            @click="selectMember(null)"
          >
            <span class="member-name">全部成员</span>
            <span class="muted">共 {{ membersTotal }} 名成员</span>
          </button>
          <button
            v-for="member in visibleMembers"
            :key="member.key"
            type="button"
            class="member-item"
            :class="{ active: selectedMember !== null && selectedMember.key === member.key }"
            @click="selectMember(member)"
          >
            <span class="member-name">{{ memberName(member) }}</span>
            <span class="member-sub" :title="member.identityRealm || ''">{{ memberMeta(member) }}</span>
            <span class="member-meta">
              <span class="muted">{{ sourceSummary(member) }}</span>
              <span class="muted">最近 {{ formatTime(member.lastAt) }}</span>
            </span>
          </button>
          <div v-if="!membersLoading && visibleMembers.length === 0" class="empty-member muted">
            {{ membersRequested ? "该园区暂无审计记录的成员" : "请选择园区后查看成员" }}
          </div>
        </div>

        <div class="member-pager">
          <el-pagination
            small
            layout="prev, pager, next"
            :page-size="MEMBER_PAGE_SIZE"
            :total="membersTotal"
            :current-page="memberPage"
            @current-change="onMemberPage"
          />
        </div>
      </el-card>

      <el-card shadow="never" class="events-card">
        <template #header>
          <div class="events-header">
            <span class="events-title">{{ scopeTitle }}</span>
            <div class="filters">
              <el-select v-model="filters.source" class="filter-sm" aria-label="操作来源" clearable placeholder="全部来源" @change="reloadEvents">
                <el-option label="公司 Agent" value="company" />
                <el-option label="办公助手" value="office" />
              </el-select>
              <el-select
                v-model="filters.eventType"
                class="filter-type"
                aria-label="操作类型"
                clearable
                filterable
                allow-create
                default-first-option
                :reserve-keyword="false"
                placeholder="全部类型"
                @change="reloadEvents"
              >
                <el-option v-for="item in eventTypeOptions" :key="item.type" :label="item.label" :value="item.type" />
              </el-select>
              <el-select v-model="filters.result" class="filter-sm" aria-label="操作结果" clearable placeholder="全部结果" @change="reloadEvents">
                <el-option v-for="item in RESULT_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
              <el-date-picker
                v-model="range"
                type="datetimerange"
                start-placeholder="开始时间"
                end-placeholder="结束时间"
                class="filter-range"
                :default-time="[new Date(0, 0, 0, 0, 0, 0), new Date(0, 0, 0, 23, 59, 59)]"
                @change="reloadEvents"
              />
              <el-button :disabled="!hasPrev" @click="prevPage">上一页</el-button>
              <el-button type="primary" :loading="eventsLoading" :disabled="!hasNext" @click="nextPage">下一页</el-button>
            </div>
          </div>
        </template>

        <el-table v-loading="eventsLoading" :data="events" empty-text="该条件下暂无审计记录">
          <el-table-column label="时间" width="170">
            <template #default="{ row }">{{ formatTime(row.at) }}</template>
          </el-table-column>
          <el-table-column label="来源" width="110">
            <template #default="{ row }">{{ sourceLabel(row) }}</template>
          </el-table-column>
          <el-table-column label="操作" min-width="240">
            <template #default="{ row }">
              {{ eventLabel(row.eventType, row.action) }}
              <div v-if="row.summary" class="muted summary-line">{{ row.summary }}</div>
            </template>
          </el-table-column>
          <el-table-column label="成员" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ eventMemberName(row) }}</template>
          </el-table-column>
          <el-table-column label="结果" width="120">
            <template #default="{ row }">
              <el-tag :type="statusType(row.result)" size="small">{{ statusLabel(row.result) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="详情" width="80">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetail(row)">查看</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>

    <AuditDetailDrawer
      v-model:detail="detail"
      :member="selectedMember"
      :event-type-options="eventTypeOptions"
    />
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from "vue";
import { adminApi, errorOf } from "../api/admin";
import AuditDetailDrawer from "../components/AuditDetailDrawer.vue";

const MEMBER_PAGE_SIZE = 20;
const EVENT_PAGE_SIZE = 20;

// Event type vocabulary comes from the backend catalog (types the system
// actually emits, past and present); row labels fall back to the raw code.
const eventTypeOptions = ref([]);

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

const RESULT_OPTIONS = [
  { value: "STARTED", label: "已发起" },
  { value: "COMPLETED", label: "已完成" },
  { value: "FAILED", label: "失败" },
  { value: "AWAITING_CONFIRMATION", label: "待确认" },
  { value: "ABORTED", label: "已中止" },
  { value: "RESOLVED", label: "草案已完成" },
  { value: "ACCEPTED", label: "草案已执行" },
  { value: "PARTIALLY_ACCEPTED", label: "草案部分执行" },
  { value: "REJECTED", label: "草案被拒绝" },
  { value: "EXPIRED", label: "草案已过期" },
  { value: "NO_CHANGE", label: "无变化" },
  { value: "CANCELLED", label: "已取消" },
  { value: "INTERRUPTED", label: "已中断" },
  { value: "PARTIAL", label: "检查点部分完成（系统）" },
  { value: "SATISFIED", label: "检查点已满足（系统）" },
  { value: "UNCONFIRMED", label: "检查点未确认（系统）" },
  { value: "MISSED", label: "检查点已错过（系统）" },
  { value: "DONE", label: "管理操作完成" },
  { value: "DENIED", label: "已拒绝" },
  { value: "UNKNOWN", label: "未知" }
];


const SOURCE_LABELS = { company: "公司 Agent", office: "办公助手" };
const ADMIN_ORIGIN_EVENTS = new Set([
  "PHONE_ALLOWLIST_ADDED", "PHONE_ALLOWLIST_REMOVED",
  "COMPANY_AGENT_ALLOWLIST_ADDED", "COMPANY_AGENT_ALLOWLIST_REMOVED"
]);

const tenants = ref([]);
const tenantCode = ref("");
const tenantsLoading = ref(false);
const members = ref([]);
const membersTotal = ref(0);
const memberPage = ref(1);
const membersLoading = ref(false);
const membersRequested = ref(false);
const memberSearch = ref("");
const memberQuery = ref("");
const selectedMember = ref(null);
const error = ref("");

const filters = reactive({ source: "", eventType: "", result: "" });
const range = ref(null);
const events = ref([]);
const eventsLoading = ref(false);
const nextCursor = ref(null);
const cursorStack = ref([]);
const currentCursor = ref(null);

const detail = ref(null);

const visibleMembers = computed(() => members.value.map((member) => ({
  ...member,
  key: `${member.tenantCode}|${member.identityRealm ?? ""}|${member.principalId ?? ""}|${member.identityResolved}`
})));

const scopeTitle = computed(() => selectedMember.value
  ? `${memberName(selectedMember.value)} 的操作记录`
  : `全部成员的操作记录（${tenantCode.value || "未选择园区"}）`);

const hasNext = computed(() => nextCursor.value !== null);
const hasPrev = computed(() => cursorStack.value.length > 0);

const eventTypeLabels = computed(() => {
  const map = {};
  for (const item of eventTypeOptions.value) map[item.type] = item.label;
  return map;
});

function eventMemberName(row) {
  const member = selectedMember.value;
  return row.displayName || row.mobileNumber
    || (member?.principalId === row.principalId ? member.displayName || member.mobileNumber : null)
    || row.principalId || row.actor || "未识别主体";
}


function memberName(member) {
  return member.displayName || member.mobileNumber
    || (member.identityResolved ? (member.principalId ?? "未知成员") : "未识别主体");
}

function memberMeta(member) {
  return [member.mobileNumber, member.identityRealm].filter(Boolean).join(" · ")
    || (member.identityResolved ? "身份已核验" : "身份域未知");
}
function sourceSummary(member) {
  const parts = [];
  if (member.sources?.company) parts.push(`公司 ${member.sources.company}`);
  if (member.sources?.office) parts.push(`办公 ${member.sources.office}`);
  if (member.sources?.unknown) parts.push(`其他 ${member.sources.unknown}`);
  return parts.length ? parts.join(" · ") : "无记录";
}

function eventLabel(eventType, action) {
  return eventTypeLabels.value[eventType] ?? action ?? eventType ?? "—";
}

function sourceLabel(row) {
  if (ADMIN_ORIGIN_EVENTS.has(row.eventType)) return "管理控制台";
  return SOURCE_LABELS[row.source] ?? row.source ?? "—";
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


const formatter = new Intl.DateTimeFormat("zh-CN", {
  year: "numeric", month: "2-digit", day: "2-digit",
  hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false
});

function formatTime(value) {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : formatter.format(date);
}

function memberFilters() {
  if (!selectedMember.value) return {};
  if (!selectedMember.value.identityResolved) return { unresolved: "true" };
  return {
    identityRealm: selectedMember.value.identityRealm,
    principalId: selectedMember.value.principalId
  };
}

async function loadTenants() {
  tenantsLoading.value = true;
  error.value = "";
  try {
    tenants.value = (await adminApi.auditTenants()).data.tenants ?? [];
  } catch (cause) {
    error.value = errorOf(cause, "无法读取园区列表").message;
    tenants.value = [];
  } finally {
    tenantsLoading.value = false;
  }
}

function onTenantChange() {
  selectedMember.value = null;
  memberPage.value = 1;
  void loadMembers();
  reloadEvents();
}

function searchMembers() {
  memberQuery.value = memberSearch.value.trim();
  memberPage.value = 1;
  void loadMembers();
}

async function loadMembers() {
  if (!tenantCode.value.trim()) {
    members.value = [];
    membersTotal.value = 0;
    membersRequested.value = false;
    return;
  }
  membersLoading.value = true;
  error.value = "";
  try {
    const { data } = await adminApi.auditMembers({
      tenantCode: tenantCode.value.trim(),
      ...(memberQuery.value ? { principalId: memberQuery.value } : {}),
      limit: MEMBER_PAGE_SIZE,
      offset: (memberPage.value - 1) * MEMBER_PAGE_SIZE
    });
    members.value = data.members ?? [];
    membersTotal.value = data.total ?? members.value.length;
    membersRequested.value = true;
  } catch (cause) {
    error.value = `${errorOf(cause, "无法读取成员索引").message}。请检查园区或连接后重新查询。`;
    members.value = [];
    membersTotal.value = 0;
  } finally {
    membersLoading.value = false;
  }
}

function onMemberPage(page) {
  memberPage.value = page;
  void loadMembers();
}

function selectMember(member) {
  if (selectedMember.value === member) return;
  selectedMember.value = member;
  reloadEvents();
}

function eventParams(cursor) {
  const params = { tenantCode: tenantCode.value.trim(), limit: EVENT_PAGE_SIZE };
  if (cursor) params.cursor = cursor;
  const scope = memberFilters();
  Object.assign(params, scope);
  if (filters.source) params.source = filters.source;
  if (filters.eventType) params.eventType = filters.eventType;
  if (filters.result) params.result = filters.result;
  if (Array.isArray(range.value) && range.value.length === 2) {
    const [from, to] = range.value;
    if (from) params.from = new Date(from).toISOString();
    if (to) params.to = new Date(to).toISOString();
  }
  return params;
}

function reloadEvents() {
  nextCursor.value = null;
  cursorStack.value = [];
  currentCursor.value = null;
  events.value = [];
  void loadEvents(null);
}

async function loadEvents(cursor) {
  if (!tenantCode.value.trim()) {
    events.value = [];
    nextCursor.value = null;
    return;
  }
  eventsLoading.value = true;
  error.value = "";
  try {
    const { data } = await adminApi.audit(eventParams(cursor));
    events.value = data.events ?? [];
    nextCursor.value = data.nextCursor ?? null;
  } catch (cause) {
    error.value = `${errorOf(cause, "无法读取审计记录").message}。请检查连接与筛选条件后重试。`;
    events.value = [];
    nextCursor.value = null;
  } finally {
    eventsLoading.value = false;
  }
}

async function nextPage() {
  if (nextCursor.value === null) return;
  cursorStack.value.push(currentCursor.value);
  currentCursor.value = nextCursor.value;
  await loadEvents(nextCursor.value);
}

async function prevPage() {
  if (cursorStack.value.length === 0) return;
  currentCursor.value = cursorStack.value.pop();
  await loadEvents(currentCursor.value);
}

async function openDetail(row) {
  detail.value = row;
}

async function loadEventTypes() {
  try {
    eventTypeOptions.value = (await adminApi.auditEventTypes()).data.eventTypes ?? [];
  } catch (cause) {
    // Labels degrade to raw type codes; filtering still works.
    error.value = errorOf(cause, "无法读取事件类型列表").message;
  }
}

onMounted(async () => {
  void loadEventTypes();
  await loadTenants();
  if (tenants.value.length > 0 && !tenantCode.value) {
    tenantCode.value = tenants.value[0];
  }
  if (tenantCode.value) {
    await loadMembers();
    await loadEvents(null);
  }
});
</script>

<style scoped>
.audit-page { max-width: 1280px; margin: 0 auto; padding: 24px; }
.page-header { margin-bottom: 16px; }
.page-title { margin: 0; color: var(--text-strong); font-size: 20px; line-height: 1.4; }
.page-sub { margin: 4px 0 0; color: var(--text-muted); font-size: 13px; }
.error-alert { margin-bottom: 16px; }
.split { display: flex; gap: 16px; align-items: stretch; }
.member-card { width: 320px; flex-shrink: 0; border: 1px solid var(--border-subtle); }
.events-card { flex: 1; min-width: 0; border: 1px solid var(--border-subtle); }
.member-toolbar { display: flex; flex-direction: column; gap: 8px; padding: 12px 12px 0; }
.tenant-select, .member-search { width: 100%; }
.member-list { min-height: 200px; max-height: 480px; overflow: auto; padding: 8px 4px; }
.member-item {
  display: flex; flex-direction: column; align-items: flex-start; gap: 2px;
  width: 100%; padding: 8px 10px; border: 0; border-radius: 6px; background: transparent;
  text-align: left; cursor: pointer; font: inherit; color: var(--text-strong);
}
.member-item:hover { background: var(--fill-light, #f5f7fa); }
.member-item.active { background: var(--el-color-primary-light-9, #ecf5ff); }
.member-name { font-size: 14px; font-weight: 600; max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.member-sub { font-size: 12px; color: var(--text-muted); max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.member-meta { display: flex; justify-content: space-between; gap: 8px; width: 100%; font-size: 12px; }
.empty-member { padding: 24px 12px; text-align: center; }
.member-pager { display: flex; justify-content: center; padding: 8px 0 12px; }
.events-header { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; justify-content: space-between; }
.events-title { font-size: 14px; font-weight: 600; color: var(--text-strong); }
.filters { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; min-width: 0; max-width: 100%; }
.filter-sm { width: 140px; }
.filters :deep(.filter-range) { width: 340px; max-width: 100%; min-width: 0; box-sizing: border-box; }
.filter-type { width: 190px; }
.muted { color: var(--text-muted); font-size: 12px; }
.summary-line { margin-top: 2px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
@media (max-width: 900px) {
  .audit-page { padding: 16px; }
  .split { flex-direction: column; }
  .member-card { width: 100%; }
  .member-list { max-height: 260px; }
  .filters :deep(.filter-range) { width: 100%; }
}
</style>
