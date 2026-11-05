<template>
  <main class="login-page">
    <el-card class="login-card" shadow="never">
      <div class="login-head">
        <h1 class="login-title">物联 Agent</h1>
        <p class="login-sub">管理控制台</p>
      </div>
      <el-form :model="form" label-position="top" @submit.prevent="submit">
        <el-form-item label="管理员密码" for="admin-password">
          <el-input
            ref="passwordInput"
            id="admin-password"
            name="password"
            v-model="form.password"
            type="password"
            size="large"
            placeholder="输入部署管理员密码"
            show-password
            autocomplete="current-password"
            :disabled="busy"
            :aria-invalid="Boolean(error)"
            :aria-describedby="error ? 'login-error' : 'login-help'"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-alert v-if="error" id="login-error" :title="error" type="error" show-icon :closable="false" class="login-error" />
        <el-button native-type="submit" type="primary" size="large" :loading="busy" class="login-submit">
          登录
        </el-button>
      </el-form>
      <p id="login-help" class="login-help">使用部署配置中的独立管理员密码；业务用户账号不能登录此控制台。</p>
    </el-card>
  </main>
</template>

<script setup>
import { reactive, ref } from "vue";
import { useRouter, useRoute } from "vue-router";
import { useSessionStore } from "../stores/session";
import { errorOf } from "../api/admin";

const router = useRouter();
const route = useRoute();
const session = useSessionStore();
const form = reactive({ password: "" });
const passwordInput = ref(null);
const busy = ref(false);
const error = ref("");

async function submit() {
  if (busy.value) return;
  if (!form.password) {
    error.value = "请输入部署管理员密码后重试";
    passwordInput.value?.focus();
    return;
  }
  busy.value = true;
  error.value = "";
  try {
    await session.login(form.password);
    const redirect = typeof route.query.redirect === "string" ? route.query.redirect : "/";
    await router.replace(redirect);
  } catch (cause) {
    error.value = errorOf(cause, "登录失败，请稍后重试").message;
  } finally {
    busy.value = false;
    form.password = "";
  }
}
</script>

<style scoped>
.login-page {
  box-sizing: border-box;
  min-height: 100%;
  display: grid;
  place-items: center;
  padding: 24px;
  background: var(--surface-page);
}
.login-card {
  width: min(100%, 360px);
}
.login-head {
  margin-bottom: 24px;
}
.login-title {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  color: var(--text-strong);
}
.login-sub {
  margin: 6px 0 0;
  color: var(--text-muted);
}
.login-error {
  margin-bottom: 16px;
}
.login-help {
  margin-block: 16px 0;
  color: var(--text-muted);
  font-size: 13px;
}
.login-submit {
  width: 100%;
}
</style>
