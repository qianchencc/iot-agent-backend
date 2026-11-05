<template>
  <div class="shell">
    <a class="skip-link" href="#main-content">跳至主要内容</a>
    <aside class="shell-sider">
      <div class="side-brand">
        <div class="side-brand-title">物联 Agent</div>
        <div class="side-brand-sub">管理控制台</div>
      </div>
      <nav class="side-nav" aria-label="控制台导航">
        <router-link
          v-for="item in MENU"
          :key="item.name"
          class="side-item"
          :class="{ 'side-item--active': route.name === item.name }"
          :to="{ name: item.name }"
        >
          {{ item.label }}
        </router-link>
      </nav>
      <div v-if="route.name === 'config'" class="side-sections">
        <div class="side-sections-title">本页分区</div>
        <router-link
          v-for="section in CONFIG_SECTIONS"
          :key="section.hash"
          class="side-section"
          :to="{ name: 'config', hash: section.hash }"
        >
          {{ section.label }}
        </router-link>
      </div>
    </aside>

    <el-drawer v-model="menuOpen" direction="ltr" size="248px" :with-header="false" class="sider-drawer">
      <div class="side-brand">
        <div class="side-brand-title">物联 Agent</div>
        <div class="side-brand-sub">管理控制台</div>
      </div>
      <nav class="side-nav" aria-label="移动端控制台导航" @click="menuOpen = false">
        <router-link
          v-for="item in MENU"
          :key="item.name"
          class="side-item"
          :class="{ 'side-item--active': route.name === item.name }"
          :to="{ name: item.name }"
        >
          {{ item.label }}
        </router-link>
      </nav>
    </el-drawer>

    <div class="shell-body">
      <header class="shell-header">
        <div class="header-left">
          <el-button
            class="menu-trigger"
            text
            aria-label="打开导航菜单"
            @click="menuOpen = true"
          >
            <svg width="18" height="18" viewBox="0 0 18 18" fill="none" aria-hidden="true">
              <path d="M2 4h14M2 9h14M2 14h14" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
            </svg>
          </el-button>
          <div class="header-title">{{ pageTitle }}</div>
        </div>
        <div class="header-actions">
          <slot name="header-actions" />
          <el-button size="small" :loading="logoutBusy" @click="requestLogout">退出登录</el-button>
        </div>
      </header>
      <main id="main-content" class="shell-content" tabindex="-1">
        <router-view v-slot="{ Component }">
          <keep-alive :include="['HomeView']">
            <component :is="Component" @dirty-change="dirty = $event" />
          </keep-alive>
        </router-view>
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { useSessionStore } from "../stores/session";
import { errorOf } from "../api/admin";

const MENU = [
  { name: "config", label: "参数配置" },
  { name: "allowlist", label: "用户白名单" },
  { name: "audit", label: "用户操作审计" }
];

const CONFIG_SECTIONS = [
  { hash: "#overview", label: "配置概览" },
  { hash: "#model", label: "模型与指引" },
  { hash: "#generation", label: "输出兼容" },
  { hash: "#limits", label: "执行预算" },
  { hash: "#instructions", label: "业务指令" },
  { hash: "#state", label: "生效状态" },
  { hash: "#history", label: "配置变更" }
];

const route = useRoute();
const router = useRouter();
const session = useSessionStore();
const menuOpen = ref(false);
const logoutBusy = ref(false);
const dirty = ref(0);

const pageTitle = computed(() => route.meta?.title ?? "");

async function logout() {
  logoutBusy.value = true;
  try {
    await session.logout();
    await router.replace({ name: "login" });
  } catch (cause) {
    ElMessage.error({ message: errorOf(cause, "退出未完成，请检查连接后重试").message, duration: 0, showClose: true });
  } finally {
    logoutBusy.value = false;
  }
}

function requestLogout() {
  if (logoutBusy.value) return;
  if (dirty.value === 0) {
    void logout();
    return;
  }
  void ElMessageBox.confirm("本地草稿尚未保存，退出后将丢失。", "放弃未保存修改并退出登录？", {
    confirmButtonText: "退出登录",
    cancelButtonText: "取消",
    type: "warning"
  })
    .then(() => logout())
    .catch(() => undefined);
}
</script>

<style scoped>
.shell {
  display: flex;
  min-height: 100%;
}

.shell-sider {
  position: sticky;
  top: 0;
  flex-shrink: 0;
  width: 216px;
  height: 100vh;
  overflow-y: auto;
  background: var(--sider-bg);
}

.side-brand {
  padding: 18px 20px 14px;
}

.side-brand-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--sider-text-strong);
}

.side-brand-sub {
  margin-top: 2px;
  font-size: 12px;
  color: var(--sider-text);
}

.side-nav {
  display: flex;
  flex-direction: column;
  padding: 4px 8px;
  gap: 2px;
}

.side-item {
  display: block;
  padding: 9px 12px;
  border-radius: 6px;
  font-size: 14px;
  color: var(--sider-text);
  text-decoration: none;
}

.side-item:hover {
  color: var(--sider-text-strong);
  background: var(--sider-bg-active);
}

.side-item--active {
  color: var(--sider-text-strong);
  background: var(--sider-bg-active);
  box-shadow: inset 3px 0 0 var(--accent);
}

.side-sections {
  margin-top: 12px;
  padding: 12px 8px 4px;
  border-top: 1px solid var(--sider-border);
}

.side-sections-title {
  padding: 0 12px 6px;
  font-size: 12px;
  color: var(--sider-text);
}

.side-section {
  display: block;
  padding: 6px 12px 6px 22px;
  border-radius: 6px;
  font-size: 13px;
  color: var(--sider-text);
  text-decoration: none;
}

.side-section:hover {
  color: var(--sider-text-strong);
  background: var(--sider-bg-active);
}

.sider-drawer :deep(.el-drawer__body) {
  padding: 0;
  background: var(--sider-bg);
}

.shell-body {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}

.shell-header {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px 12px;
  min-height: 56px;
  padding: 8px 24px;
  box-sizing: border-box;
  background: var(--surface-card);
  border-bottom: 1px solid var(--border-subtle);
}

.header-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.menu-trigger {
  display: none;
}

.header-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-strong);
}

.header-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

.shell-content {
  flex: 1;
  min-width: 0;
}

@media (max-width: 900px) {
  .shell-sider {
    display: none;
  }
  .menu-trigger {
    display: inline-flex;
  }
  .shell-header {
    padding: 8px 16px;
  }
}
</style>
