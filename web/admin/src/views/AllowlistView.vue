<template>
  <div class="allowlist-page">
    <header class="page-header">
      <div>
        <h1 class="page-title">用户白名单</h1>
        <p class="page-sub">仅维护手机号与可选姓名备注；名单暂未接入任何业务，不影响公司、办公室与任务。</p>
      </div>
      <el-button type="primary" @click="dialogOpen = true">添加手机号</el-button>
    </header>

    <el-alert type="info" show-icon :closable="false" class="scope-alert"
      title="此名单当前只做记录，不控制谁能使用公司 Agent，也不触发任务取消。" />

    <el-alert v-if="error" :title="error" type="error" show-icon closable class="error-alert" @close="error = ''" />
    <el-card shadow="never" class="table-card">
      <el-table v-loading="loading" :data="users" :row-key="userKey">
        <el-table-column prop="mobileNumber" label="手机号" min-width="160" />
        <el-table-column label="姓名备注" min-width="160">
          <template #default="{ row }">{{ row.name || "—" }}</template>
        </el-table-column>
        <el-table-column label="添加时间" width="190">
          <template #default="{ row }">{{ formatTime(row.addedAt) }}</template>
        </el-table-column>
        <el-table-column prop="addedBy" label="操作者" width="120" />
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button link type="danger" @click="remove(row)">移除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无记录，点击右上角「添加手机号」登记第一条" :image-size="72" />
        </template>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogOpen" title="添加手机号" width="min(460px, calc(100vw - 32px))" @closed="resetForm">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="手机号" prop="mobileNumber">
          <el-input v-model="form.mobileNumber" placeholder="例如 13800138000" />
        </el-form-item>
        <el-form-item label="姓名备注（可选）" prop="name">
          <el-input v-model="form.name" placeholder="仅作备注，不参与验证" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="add">添加</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from "vue";
import { ElMessageBox } from "element-plus";
import { adminApi, errorOf } from "../api/admin";

const formatter = new Intl.DateTimeFormat("zh-CN", {
  year: "numeric", month: "2-digit", day: "2-digit",
  hour: "2-digit", minute: "2-digit", hour12: false
});

const users = ref([]);
const revision = ref(0);
const loading = ref(false);
const saving = ref(false);
const error = ref("");
const dialogOpen = ref(false);
const formRef = ref();
const form = reactive({ mobileNumber: "", name: "" });
const rules = {
  mobileNumber: [
    { required: true, message: "请输入手机号", trigger: "blur" },
    { pattern: /^\+?\d{5,20}$/, message: "仅支持 5-20 位数字与可选前置加号", trigger: "blur" }
  ]
};

function formatTime(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : formatter.format(date);
}

async function load() {
  loading.value = true;
  error.value = "";
  try {
    const { data } = await adminApi.allowlist();
    revision.value = data.revision;
    users.value = data.users;
  } catch (cause) {
    error.value = errorOf(cause, "无法读取白名单").message;
  } finally {
    loading.value = false;
  }
}

async function add() {
  if (!formRef.value || saving.value) return;
  try {
    await formRef.value.validate();
  } catch {
    return;
  }
  saving.value = true;
  try {
    await adminApi.addAllowlistedUser(revision.value, {
      mobileNumber: form.mobileNumber.trim(),
      name: form.name.trim() || null
    });
    dialogOpen.value = false;
    await load();
  } catch (cause) {
    const failure = errorOf(cause, "添加白名单记录失败");
    error.value = failure.message;
    if (failure.code === "ALLOWLIST_VERSION_CONFLICT") await load();
  } finally {
    saving.value = false;
  }
}

function userKey(user) {
  return user.mobileNumber;
}

async function remove(user) {
  try {
    await ElMessageBox.confirm(
      `将从名单移除 ${user.mobileNumber}。这只删除本名单记录，不影响任何业务与已执行任务。`,
      "确认移除？",
      { confirmButtonText: "移除", cancelButtonText: "取消", type: "warning" }
    );
  } catch {
    return;
  }
  try {
    await adminApi.removeAllowlistedUser(revision.value, { mobileNumber: user.mobileNumber });
    await load();
  } catch (cause) {
    const failure = errorOf(cause, "移除白名单记录失败");
    error.value = failure.message;
    if (failure.code === "ALLOWLIST_VERSION_CONFLICT") await load();
  }
}

function resetForm() {
  form.mobileNumber = "";
  form.name = "";
  formRef.value?.clearValidate();
}

onMounted(load);
</script>

<style scoped>
.allowlist-page { max-width: 1120px; margin: 0 auto; padding: 24px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; margin-bottom: 16px; }
.page-title { margin: 0; color: var(--text-strong); font-size: 20px; line-height: 1.4; }
.page-sub { margin: 4px 0 0; color: var(--text-muted); font-size: 13px; }
.scope-alert { margin-bottom: 16px; }
.error-alert { margin-bottom: 16px; }
.table-card { border: 1px solid var(--border-subtle); }
@media (max-width: 640px) {
  .allowlist-page { padding: 16px; }
  .page-header { flex-direction: column; }
}
</style>
