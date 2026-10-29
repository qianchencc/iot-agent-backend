<template>
  <div class="login-page">
    <a-card class="login-card" :bordered="false">
      <div class="login-head">
        <h1 class="login-title">物联 Agent 管理控制台</h1>
        <p class="login-sub">管理员登录</p>
      </div>
      <a-form :model="form" layout="vertical" @submit-success="submit">
        <a-form-item field="password" hide-label>
          <a-input-password
            v-model="form.password"
            size="large"
            placeholder="管理员密码"
            allow-clear
            :disabled="busy"
            @keyup.enter="submit"
          />
        </a-form-item>
        <a-alert v-if="error" type="error" class="login-error">{{ error }}</a-alert>
        <a-button type="primary" size="large" long :loading="busy" html-type="submit">
          登录
        </a-button>
      </a-form>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from "vue";
import { useRouter, useRoute } from "vue-router";
import { useSessionStore } from "../stores/session";
import { errorOf } from "../api/admin";

const router = useRouter();
const route = useRoute();
const session = useSessionStore();

const form = reactive({ password: "" });
const busy = ref(false);
const error = ref("");

async function submit() {
  if (!form.password || busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    await session.login(form.password);
    const redirect = typeof route.query.redirect === "string" ? route.query.redirect : "/";
    await router.replace(redirect);
  } catch (e) {
    error.value = errorOf(e, "登录失败，请稍后重试").message;
  } finally {
    busy.value = false;
    form.password = "";
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}

.login-card {
  width: 360px;
}

.login-head {
  margin-bottom: 24px;
}

.login-title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: var(--color-text-1);
}

.login-sub {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--color-text-3);
}

.login-error {
  margin-bottom: 12px;
}
</style>
