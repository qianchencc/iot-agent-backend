<template>
  <a-layout class="shell">
    <a-layout-header class="shell-header">
      <div class="shell-brand">物联 Agent 管理控制台</div>
      <a-space>
        <a-tag color="green">已登录</a-tag>
        <a-button size="small" :loading="busy" @click="logout">退出登录</a-button>
      </a-space>
    </a-layout-header>
    <a-layout-content class="shell-content">
      <a-card title="控制台骨架" :bordered="false">
        <a-typography-paragraph>
          会话闭环已接通：登录 / 会话续存 / 退出。配置、变更历史、提示词等页面按模块逐个补齐。
        </a-typography-paragraph>
        <a-typography-paragraph type="secondary">
          后端契约：<code>/api/admin/*</code>（cookie <code>runtime_admin</code> + <code>x-admin-csrf</code>）。
        </a-typography-paragraph>
      </a-card>
    </a-layout-content>
  </a-layout>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { useSessionStore } from "../stores/session";

const router = useRouter();
const session = useSessionStore();
const busy = ref(false);

async function logout() {
  busy.value = true;
  try {
    await session.logout();
    await router.replace({ name: "login" });
  } finally {
    busy.value = false;
  }
}
</script>

<style scoped>
.shell {
  min-height: 100%;
}

.shell-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  height: 56px;
  background: var(--color-bg-2);
  border-bottom: 1px solid var(--color-border-2);
}

.shell-brand {
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text-1);
}

.shell-content {
  padding: 20px;
}
</style>
