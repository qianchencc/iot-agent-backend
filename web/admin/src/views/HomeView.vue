<template>
  <a-layout class="shell">
    <a-layout-sider class="shell-sider" :width="232" :style="{ background: 'var(--color-bg-2)' }">
      <div class="side-brand">
        <div class="side-brand-title">物联 Agent</div>
        <div class="side-brand-sub">管理控制台</div>
      </div>
      <a-menu
        class="side-menu"
        theme="light"
        mode="vertical"
        :selected-keys="[activeSection]"
        @menu-item-click="jumpTo"
      >
        <a-menu-item key="overview">运行状态</a-menu-item>
        <a-menu-item key="model">模型连接</a-menu-item>
        <a-menu-item key="generation">输出与兼容</a-menu-item>
        <a-menu-item key="limits">执行预算</a-menu-item>
        <a-menu-item key="instructions">业务指令</a-menu-item>
        <a-menu-item key="state">生效状态</a-menu-item>
        <a-menu-item key="audit">变更审计</a-menu-item>
      </a-menu>
      <div v-if="view" class="side-meta">
        <div class="side-meta-title">部署环境</div>
        <div class="side-meta-line">{{ view.deployment.environment }}</div>
        <div class="side-meta-line">{{ view.deployment.host }}:{{ view.deployment.port }}</div>
        <div class="side-meta-line">版本 {{ view.deployment.version }}</div>
        <div class="side-meta-line">{{ view.deployment.timezone }}</div>
      </div>
    </a-layout-sider>

    <a-layout class="shell-main">
      <a-layout-header class="shell-header">
        <div class="header-title">配置维护</div>
        <div class="header-actions">
          <a-tag v-if="session.authenticated" color="green">已登录</a-tag>
          <a-tag v-if="view">修订 #{{ view.revision }}</a-tag>
          <a-tag v-if="dirty" color="orange">未保存 {{ dirtyCount }}</a-tag>
          <a-button
            size="small"
            :loading="loading"
            :disabled="busy"
            @click="requestRefresh"
          >
            刷新
          </a-button>
          <a-button
            size="small"
            :loading="logoutBusy"
            :disabled="busy || logoutBusy"
            @click="requestLogout"
          >
            退出登录
          </a-button>
        </div>
      </a-layout-header>

      <a-layout-content class="shell-content">
        <div v-if="!view && loading" class="page-content">
          <a-card :bordered="false">
            <a-skeleton :animation="true">
              <a-skeleton-line :rows="8" />
            </a-skeleton>
          </a-card>
        </div>

        <div v-else-if="!view" class="page-content">
          <a-result
            status="error"
            title="配置加载失败"
            :subtitle="configError || '暂时无法读取配置，请稍后重试。'"
          >
            <template #extra>
              <a-button type="primary" :loading="loading" @click="reloadAll(true)">重试</a-button>
            </template>
          </a-result>
        </div>

        <div v-else class="page-content">
          <div class="page-heading">
            <div class="page-heading-text">
              <h1 class="page-title">模型与业务配置</h1>
              <p class="page-subtitle">
                模型、业务指令、通用预算与变更审计。保存后由下一轮对话读取新快照，执行中的轮次保持旧快照。
              </p>
            </div>
            <div class="page-status">
              <a-tag :color="dirty ? 'orange' : 'green'">{{ dirty ? "草稿未保存" : "已同步" }}</a-tag>
              <a-tag :color="stateHasPending ? 'orange' : 'green'">
                {{ stateHasPending ? "有待应用字段" : "下一轮生效" }}
              </a-tag>
            </div>
          </div>

          <a-alert v-if="configError" type="error" class="notice-block">
            <div>{{ configError }}</div>
            <div class="notice-actions">
              <a-button size="small" html-type="button" @click="requestRefresh">重新加载</a-button>
            </div>
          </a-alert>

          <a-alert v-if="configConflict" type="warning" class="notice-block">
            <div>配置已被其他操作修改，保存已暂停；你的本地草稿仍然保留。</div>
            <div>
              载入最新修订并保留草稿后可以重新保存，但可能覆盖服务端的新值，请先核对。放弃草稿会丢失当前全部未保存修改。
            </div>
            <div class="notice-actions">
              <a-button
                size="small"
                type="primary"
                html-type="button"
                :loading="loading"
                @click="rebaseKeepingDraft"
              >
                载入最新修订（保留草稿）
              </a-button>
              <a-button size="small" html-type="button" :disabled="busy" @click="requestRefresh">
                放弃草稿并刷新
              </a-button>
            </div>
          </a-alert>

          <a-alert v-if="saveError && !configConflict" type="error" class="notice-block">
            <div>{{ saveError }}</div>
            <div v-if="saveIssues.length" class="notice-issues">
              需要检查的字段：{{ issueLabels(saveIssues) }}
            </div>
          </a-alert>

          <a-alert v-if="saveNotice" type="success" class="notice-block" closable @close="clearSaveNotice">
            {{ saveNotice }}
          </a-alert>

          <a-alert v-if="validationMessage" type="warning" class="notice-block">
            {{ validationMessage }}
          </a-alert>

          <div id="overview" class="overview-grid section-anchor">
            <div class="overview-card">
              <div class="overview-label">已保存修订</div>
              <div class="overview-value">#{{ view.revision }}</div>
              <div class="overview-sub">{{ dirty ? `含 ${dirtyCount} 项本地修改` : "与服务端一致" }}</div>
            </div>
            <div class="overview-card">
              <div class="overview-label">下一轮生效修订</div>
              <div class="overview-value">#{{ view.activeRevision }}</div>
              <div class="overview-sub">下一轮读取的配置快照基线</div>
            </div>
            <div class="overview-card">
              <div class="overview-label">草稿状态</div>
              <div class="overview-value">{{ dirty ? `${dirtyCount} 项` : "无修改" }}</div>
              <div class="overview-sub">{{ configConflict ? "存在版本冲突" : dirty ? "尚未保存到 Java / MySQL" : "可安全离开" }}</div>
            </div>
            <div class="overview-card">
              <div class="overview-label">模型密钥</div>
              <div class="overview-value">{{ apiKeyConfigured ? "已配置" : "未配置" }}</div>
              <div class="overview-sub">{{ draft.modelApiKey.length > 0 ? "有新密钥待保存" : "页面不回显已保存密钥" }}</div>
            </div>
          </div>

          <a-form
            ref="formRef"
            :model="draft"
            :rules="rules"
            layout="vertical"
            :disabled="busy"
            scroll-to-first-error
            @submit-success="save"
            @submit-failed="onValidationFailed"
          >
            <a-card id="model" title="模型连接" :bordered="false" class="section-card">
              <template #extra>
                <div class="header-actions">
                  <a-tag :color="apiKeyConfigured ? 'green' : 'red'">
                    {{ apiKeyConfigured ? "密钥已配置" : "密钥未配置" }}
                  </a-tag>
                  <a-tag v-if="draft.modelApiKey.length > 0" color="orange">新密钥待保存</a-tag>
                </div>
              </template>
              <div class="form-grid">
                <a-form-item field="modelBaseUrl" label="模型接口根地址">
                  <a-input v-model="draft.modelBaseUrl" allow-clear placeholder="https://api.example.com/v1" />
                  <div class="field-note">
                    OpenAI 兼容接口根地址；必须是 http(s) 地址，不能带登录信息、查询参数或片段。
                  </div>
                </a-form-item>
                <a-form-item field="modelId" label="模型 ID">
                  <a-input v-model="draft.modelId" allow-clear placeholder="例如 gpt-4.1-mini" />
                </a-form-item>
                <a-form-item
                  field="modelApiKey"
                  label="模型 API Key（替换）"
                  class="span-2"
                  :required="!apiKeyConfigured"
                >
                  <a-input-password
                    v-model="draft.modelApiKey"
                    allow-clear
                    autocomplete="new-password"
                    :placeholder="apiKeyConfigured ? '已配置，留空保持当前密钥' : '尚未配置，请输入 API Key'"
                  />
                  <div class="field-note">
                    已保存密钥：{{ apiKeyConfigured ? "已配置（不回显）" : "未配置" }}；下一轮生效快照：{{
                      apiKeyActiveConfigured ? "已配置" : "未配置"
                    }}。{{
                      apiKeyConfigured
                        ? "只有填写新值才会替换，留空表示保持当前密钥。"
                        : "当前没有可用密钥，必须填写后才能保存或测试。"
                    }}
                  </div>
                </a-form-item>
              </div>
              <div class="inline-actions">
                <a-button html-type="button" :loading="testing" :disabled="busy" @click="testModel">
                  测试模型连接
                </a-button>
                <span class="field-note">
                  测试会调用 Java 管理接口，使用服务端已保存密钥加当前草稿中的地址、模型 ID 与新密钥；不会保存配置，也不会回显密钥。
                </span>
              </div>
              <div v-if="testOutcome" class="notice-block" aria-live="polite">
                <a-alert :type="testOutcome.type === 'success' ? 'success' : 'error'">
                  {{ testOutcome.message }}
                </a-alert>
              </div>
            </a-card>

            <a-card id="generation" title="输出、上下文与推理兼容" :bordered="false" class="section-card">
              <div class="form-grid">
                <a-form-item field="contextWindow" label="上下文窗口">
                  <a-input-number
                    v-model="draft.contextWindow"
                    class="full-width"
                    :min="8192"
                    :max="2000000"
                    :precision="0"
                    :step="1024"
                    allow-clear
                  />
                  <div class="field-note">范围 8,192–2,000,000 tokens。</div>
                </a-form-item>
                <a-form-item field="maxOutputTokens" label="最大输出 tokens">
                  <a-input-number
                    v-model="draft.maxOutputTokens"
                    class="full-width"
                    :min="256"
                    :max="128000"
                    :precision="0"
                    :step="256"
                    allow-clear
                  />
                  <div class="field-note">范围 256–128,000，且必须小于上下文窗口。</div>
                </a-form-item>
                <a-form-item field="reasoning" label="启用推理">
                  <div class="switch-line">
                    <a-switch v-model="draft.reasoning" />
                    <span>{{ draft.reasoning ? "开启" : "关闭" }}</span>
                  </div>
                  <div class="field-note">关闭推理时，思考级别必须为“关闭”。</div>
                </a-form-item>
                <a-form-item field="thinkingLevel" label="思考级别">
                  <a-select v-model="draft.thinkingLevel" class="full-width" :disabled="!draft.reasoning">
                    <a-option v-for="item in THINKING_OPTIONS" :key="item.value" :value="item.value">
                      {{ item.label }}
                    </a-option>
                  </a-select>
                  <div class="field-note">off / low / medium / high，随模型能力选择。</div>
                </a-form-item>
                <a-form-item field="maxTokensField" label="输出上限字段">
                  <a-select v-model="draft.maxTokensField" class="full-width">
                    <a-option v-for="item in MAX_TOKENS_OPTIONS" :key="item.value" :value="item.value">
                      {{ item.label }}
                    </a-option>
                  </a-select>
                  <div class="field-note">与服务商兼容；不确定时保留 max_completion_tokens。</div>
                </a-form-item>
                <a-form-item field="supportsDeveloperRole" label="支持 developer 角色">
                  <div class="switch-line">
                    <a-switch v-model="draft.supportsDeveloperRole" />
                    <span>{{ draft.supportsDeveloperRole ? "支持" : "不支持" }}</span>
                  </div>
                </a-form-item>
                <a-form-item field="supportsReasoningEffort" label="支持推理强度参数">
                  <div class="switch-line">
                    <a-switch v-model="draft.supportsReasoningEffort" />
                    <span>{{ draft.supportsReasoningEffort ? "支持" : "不支持" }}</span>
                  </div>
                </a-form-item>
                <a-form-item field="supportsUsageInStreaming" label="流式返回用量统计">
                  <div class="switch-line">
                    <a-switch v-model="draft.supportsUsageInStreaming" />
                    <span>{{ draft.supportsUsageInStreaming ? "支持" : "不支持" }}</span>
                  </div>
                </a-form-item>
              </div>
            </a-card>

            <a-card id="limits" title="通用执行预算" :bordered="false" class="section-card">
              <div class="form-grid form-grid--three">
                <a-form-item field="maxAgentTurns" label="单轮最大模型回合数">
                  <a-input-number
                    v-model="draft.maxAgentTurns"
                    class="full-width"
                    :min="1"
                    :max="30"
                    :precision="0"
                    allow-clear
                  />
                  <div class="field-note">范围 1–30。</div>
                </a-form-item>
                <a-form-item field="maxToolCalls" label="单轮最大工具调用次数">
                  <a-input-number
                    v-model="draft.maxToolCalls"
                    class="full-width"
                    :min="1"
                    :max="1000"
                    :precision="0"
                    allow-clear
                  />
                  <div class="field-note">范围 1–1000。</div>
                </a-form-item>
                <a-form-item field="runTimeoutMs" label="单轮执行超时（毫秒）">
                  <a-input-number
                    v-model="draft.runTimeoutMs"
                    class="full-width"
                    :min="1000"
                    :max="600000"
                    :precision="0"
                    :step="1000"
                    allow-clear
                  />
                  <div class="field-note">范围 1,000–600,000 毫秒。</div>
                </a-form-item>
              </div>
              <a-alert type="info">
                限额在一轮对话开始时读取；正在执行的轮次继续使用开始时的预算，不会在新旧配置之间切换。
              </a-alert>
            </a-card>

            <a-card id="instructions" title="业务指令" :bordered="false" class="section-card">
              <a-form-item field="officeInstructions" label="办公助手角色与业务指令">
                <a-textarea
                  v-model="draft.officeInstructions"
                  allow-clear
                  :auto-size="{ minRows: 4, maxRows: 12 }"
                  placeholder="描述办公助手的角色、业务范围、可用数据与输出约束。"
                />
                <div class="field-note">保存后下一轮对话生效；正在执行的轮次保持旧指令。</div>
              </a-form-item>
              <a-form-item field="companyInstructions" label="公司助手角色与业务指令">
                <a-textarea
                  v-model="draft.companyInstructions"
                  allow-clear
                  :auto-size="{ minRows: 4, maxRows: 12 }"
                  placeholder="描述公司助手的角色、业务范围、可用数据与输出约束。"
                />
              </a-form-item>
              <a-form-item field="replyInstructions" label="回复风格指令">
                <a-textarea
                  v-model="draft.replyInstructions"
                  allow-clear
                  :auto-size="{ minRows: 3, maxRows: 10 }"
                  placeholder="描述回复语气、结构、语言与拒绝边界。"
                />
              </a-form-item>
            </a-card>

            <a-card id="state" title="生效状态与完整快照" :bordered="false" class="section-card">
              <a-alert v-if="stateHasPending" type="warning" class="notice-block">
                <div>以下字段的已保存值与下一轮生效快照不同：{{ pendingFieldLabels }}。</div>
                <div v-if="view?.pendingRestart">后端待应用标记：是。</div>
                <div>
                  这些字段不在热更新范围，需由部署层完成应用或重启；本控制台不会重启 Node 或后端进程。模型、生成参数、业务指令与执行预算等热字段保存后直接进入下一轮快照。可在下方“完整字段快照对照”查看差异。
                </div>
              </a-alert>
              <a-alert v-else type="success" class="notice-block">
                已保存配置与下一轮生效快照一致。正在执行的轮次仍使用其开始时的快照。
              </a-alert>

              <a-descriptions :column="2" bordered size="medium" class="state-descriptions">
                <a-descriptions-item label="已保存修订">#{{ view?.revision }}</a-descriptions-item>
                <a-descriptions-item label="下一轮生效修订">#{{ view?.activeRevision }}</a-descriptions-item>
                <a-descriptions-item label="模型">
                  {{ view?.saved.modelId || "（未配置）" }}
                </a-descriptions-item>
                <a-descriptions-item label="执行预算">
                  {{ view?.saved.maxAgentTurns }} 回合 / {{ view?.saved.maxToolCalls }} 次工具 /
                  {{ view?.saved.runTimeoutMs }} 毫秒
                </a-descriptions-item>
                <a-descriptions-item label="环境锁定字段">
                  <div v-if="lockedFieldLabels.length" class="field-tags">
                    <a-tag v-for="label in lockedFieldLabels" :key="label" size="small">{{ label }}</a-tag>
                  </div>
                  <span v-else>无</span>
                </a-descriptions-item>
                <a-descriptions-item label="配置差异">
                  {{ stateHasPending ? "存在待应用字段" : "无" }}
                </a-descriptions-item>
                <a-descriptions-item label="自动应用能力">
                  {{ view?.applyEnabled ? "部署声明可用（本页不触发进程重启）" : "未接入（本页不重启进程）" }}
                </a-descriptions-item>
                <a-descriptions-item label="部署地址">
                  {{ view?.deployment.host }}:{{ view?.deployment.port }} · {{ view?.deployment.environment }}
                </a-descriptions-item>
              </a-descriptions>

              <div class="state-note">
                保存只会生成新的配置修订，不执行任何进程重启。模型、生成参数、业务指令与执行预算是热字段，编辑区显示已保存值，保存后直接进入下一轮快照；完整快照中的指令字段只显示字符数，避免长文本重复。进程级参数在本页只读展示，修改需按部署维护流程处理。
              </div>

              <a-collapse :bordered="false" class="snapshot-collapse">
                <a-collapse-item header="完整字段快照对照（不含密钥）" key="snapshot">
                  <div class="snapshot-wrap">
                    <table class="snapshot-table">
                      <caption class="visually-hidden">
                        已保存配置与下一轮生效快照字段对照
                      </caption>
                      <thead>
                        <tr>
                          <th scope="col">字段</th>
                          <th scope="col">已保存</th>
                          <th scope="col">下一轮生效</th>
                        </tr>
                      </thead>
                      <tbody>
                        <tr v-for="row in snapshotRows" :key="row.field">
                          <td>
                            <div class="snapshot-field">
                              <span>{{ row.label }}</span>
                              <code>{{ row.field }}</code>
                              <a-tag v-if="row.changed" size="small" color="orange">差异</a-tag>
                            </div>
                          </td>
                          <td>{{ row.saved }}</td>
                          <td>{{ row.active }}</td>
                        </tr>
                      </tbody>
                    </table>
                  </div>
                </a-collapse-item>
              </a-collapse>
            </a-card>

            <a-card id="audit" title="变更审计与恢复" :bordered="false" class="section-card">
              <template #extra>
                <a-button
                  size="small"
                  html-type="button"
                  :loading="historyLoading"
                  :disabled="saving || testing || restoringRevision !== null"
                  @click="loadHistory"
                >
                  刷新审计
                </a-button>
              </template>
              <a-alert type="info" class="notice-block">
                审计只记录修订号、时间、操作者与变更字段名，不记录配置值、密钥或供应商凭证。
              </a-alert>

              <a-alert v-if="historyError" type="error" class="notice-block">
                <div>{{ historyError }}</div>
                <div class="notice-actions">
                  <a-button size="small" html-type="button" :loading="historyLoading" @click="loadHistory">
                    重试
                  </a-button>
                </div>
              </a-alert>

              <a-alert v-if="restoreError" type="error" class="notice-block">
                <div>{{ restoreError }}</div>
                <div v-if="restoreIssues.length" class="notice-issues">
                  需要检查的字段：{{ issueLabels(restoreIssues) }}
                </div>
              </a-alert>

              <div class="audit-wrap">
                <a-spin :loading="historyLoading" class="table-spin">
                  <table v-if="historyRecords.length" class="audit-table">
                    <caption class="visually-hidden">配置变更审计记录</caption>
                    <thead>
                      <tr>
                        <th scope="col">修订</th>
                        <th scope="col">时间</th>
                        <th scope="col">操作者</th>
                        <th scope="col">变更字段（仅字段名）</th>
                        <th scope="col">操作</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="row in historyRecords" :key="row.revision">
                        <td class="mono">#{{ row.revision }}</td>
                        <td class="no-wrap">{{ formatTime(row.at) }}</td>
                        <td>{{ row.actor }}</td>
                        <td>
                          <div v-if="row.changedFields.length" class="field-tags">
                            <a-tag v-for="field in row.changedFields" :key="field" size="small">
                              {{ fieldLabel(field) }}
                            </a-tag>
                          </div>
                          <span v-else class="muted">初始配置</span>
                        </td>
                        <td>
                          <a-button
                            size="small"
                            html-type="button"
                            :loading="restoringRevision === row.revision"
                            :disabled="busy || configConflict || row.revision === view?.revision"
                            @click="requestRestore(row)"
                          >
                            {{ row.revision === view?.revision ? "当前版本" : "恢复" }}
                          </a-button>
                        </td>
                      </tr>
                    </tbody>
                  </table>
                  <a-empty v-else-if="!historyLoading" description="暂无配置变更记录" />
                </a-spin>
              </div>
            </a-card>

            <div class="save-bar">
              <div class="save-bar-inner">
                <div class="save-status">
                  <strong v-if="configConflict">配置冲突：请先载入最新修订</strong>
                  <strong v-else-if="saving">正在保存配置…</strong>
                  <strong v-else-if="testing">正在测试模型连接…</strong>
                  <strong v-else-if="restoringRevision !== null">正在恢复历史配置…</strong>
                  <strong v-else-if="saveError && !configConflict">保存失败：{{ saveError }}</strong>
                  <strong v-else-if="configError">刷新失败：{{ configError }}</strong>
                  <strong v-else-if="validationMessage">请先修正标红字段</strong>
                  <strong v-else-if="dirty">未保存修改 {{ dirtyCount }} 项</strong>
                  <strong v-else>所有修改已保存</strong>
                  <span class="field-note">仅提交差异字段，并携带当前修订 #{{ view?.revision }} 防止并发覆盖。</span>
                </div>
                <div class="save-actions">
                  <a-button
                    html-type="button"
                    :disabled="!dirty || busy || configConflict"
                    @click="requestDiscard"
                  >
                    放弃修改
                  </a-button>
                  <a-button
                    type="primary"
                    html-type="submit"
                    :loading="saving"
                    :disabled="!dirty || busy || configConflict"
                  >
                    保存到下一轮
                  </a-button>
                </div>
              </div>
            </div>
          </a-form>
        </div>
      </a-layout-content>
    </a-layout>
  </a-layout>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { Message, Modal, type FieldRule, type FormInstance } from "@arco-design/web-vue";
import {
  adminApi,
  errorOf,
  type AdminChangeRecord,
  type AdminConfigValues,
  type AdminConfigView,
  type ConfigChangeSet
} from "../api/admin";
import { useSessionStore } from "../stores/session";

interface ConfigDraft {
  modelBaseUrl: string;
  modelId: string;
  /** Replacement input only: never populated from the server. */
  modelApiKey: string;
  contextWindow: number | undefined;
  maxOutputTokens: number | undefined;
  reasoning: boolean;
  thinkingLevel: string;
  supportsDeveloperRole: boolean;
  supportsReasoningEffort: boolean;
  supportsUsageInStreaming: boolean;
  maxTokensField: string;
  maxAgentTurns: number | undefined;
  maxToolCalls: number | undefined;
  officeInstructions: string;
  companyInstructions: string;
  replyInstructions: string;
  runTimeoutMs: number | undefined;
}

interface SnapshotRow {
  field: string;
  label: string;
  saved: string;
  active: string;
  changed: boolean;
}

interface TestOutcome {
  type: "success" | "error";
  message: string;
}

const THINKING_OPTIONS = [
  { label: "关闭（off）", value: "off" },
  { label: "低（low）", value: "low" },
  { label: "中（medium）", value: "medium" },
  { label: "高（high）", value: "high" }
];

const MAX_TOKENS_OPTIONS = [
  { label: "max_completion_tokens（推荐）", value: "max_completion_tokens" },
  { label: "max_tokens（兼容）", value: "max_tokens" }
];

const FIELD_LABELS: Record<string, string> = {
  modelBaseUrl: "模型接口根地址",
  modelId: "模型 ID",
  modelApiKey: "模型 API Key",
  contextWindow: "上下文窗口",
  maxOutputTokens: "最大输出 tokens",
  reasoning: "启用推理",
  thinkingLevel: "思考级别",
  supportsDeveloperRole: "developer 角色",
  supportsReasoningEffort: "推理强度参数",
  supportsUsageInStreaming: "流式用量统计",
  maxTokensField: "输出上限字段",
  maxAgentTurns: "最大模型回合数",
  maxToolCalls: "最大工具调用次数",
  officeInstructions: "办公助手角色与业务指令",
  companyInstructions: "公司助手角色与业务指令",
  replyInstructions: "回复风格指令",
  runTimeoutMs: "单轮执行超时",
  draftTtlMs: "动作草稿有效期",
  heartbeatMs: "心跳间隔",
  readbackWaitMs: "状态回读等待",
  controlTimeoutMs: "控制调用超时",
  scanIntervalMs: "扫描间隔",
  catalogTtlMs: "设备目录缓存",
  authoritativeStateTtlMs: "权威状态缓存",
  actionIdTtlMs: "动作 ID 有效期",
  energyReadTtlMs: "能耗读取缓存",
  langfuseEnabled: "Langfuse 启用",
  langfuseBaseUrl: "Langfuse 地址",
  langfusePublicKey: "Langfuse 公钥",
  langfuseSecretKey: "Langfuse 密钥"
};

const SNAPSHOT_FIELDS = [
  "modelBaseUrl",
  "modelId",
  "contextWindow",
  "maxOutputTokens",
  "reasoning",
  "thinkingLevel",
  "supportsDeveloperRole",
  "supportsReasoningEffort",
  "supportsUsageInStreaming",
  "maxTokensField",
  "maxAgentTurns",
  "maxToolCalls",
  "officeInstructions",
  "companyInstructions",
  "replyInstructions",
  "runTimeoutMs",
  "draftTtlMs",
  "heartbeatMs",
  "readbackWaitMs",
  "controlTimeoutMs",
  "scanIntervalMs",
  "catalogTtlMs",
  "authoritativeStateTtlMs",
  "actionIdTtlMs",
  "energyReadTtlMs",
  "langfuseEnabled",
  "langfuseBaseUrl"
] as const;

const SECRET_FIELDS = new Set(["modelApiKey", "langfusePublicKey", "langfuseSecretKey"]);
const INSTRUCTION_FIELDS = new Set(["officeInstructions", "companyInstructions", "replyInstructions"]);

const formatter = new Intl.DateTimeFormat("zh-CN", {
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
  hour12: false
});

const router = useRouter();
const session = useSessionStore();

const view = ref<AdminConfigView | null>(null);
const draft = reactive<ConfigDraft>(emptyDraft());
const activeSection = ref("overview");
const loading = ref(false);
const saving = ref(false);
const testing = ref(false);
const restoringRevision = ref<number | null>(null);
const historyLoading = ref(false);
const logoutBusy = ref(false);
const configError = ref("");
const historyError = ref("");
const saveError = ref("");
const saveIssues = ref<string[]>([]);
const restoreError = ref("");
const restoreIssues = ref<string[]>([]);
const validationMessage = ref("");
const configConflict = ref(false);
const saveNotice = ref("");
const testOutcome = ref<TestOutcome | null>(null);
const historyRecords = ref<AdminChangeRecord[]>([]);
const formRef = ref<FormInstance>();
let historyRequest = 0;

const busy = computed(
  () => loading.value || saving.value || testing.value || restoringRevision.value !== null
);

const apiKeyConfigured = computed(() => view.value?.secrets.modelApiKey?.configured === true);
const apiKeyActiveConfigured = computed(
  () => view.value?.secrets.modelApiKey?.activeConfigured === true
);

const dirtyFields = computed<string[]>(() => {
  const current = view.value;
  if (!current) return [];
  const saved = current.saved;
  const fields: string[] = [];
  if (draft.modelBaseUrl !== saved.modelBaseUrl) fields.push("modelBaseUrl");
  if (draft.modelId !== saved.modelId) fields.push("modelId");
  if (draft.contextWindow !== saved.contextWindow) fields.push("contextWindow");
  if (draft.maxOutputTokens !== saved.maxOutputTokens) fields.push("maxOutputTokens");
  if (draft.reasoning !== saved.reasoning) fields.push("reasoning");
  if (draft.thinkingLevel !== saved.thinkingLevel) fields.push("thinkingLevel");
  if (draft.supportsDeveloperRole !== saved.supportsDeveloperRole) fields.push("supportsDeveloperRole");
  if (draft.supportsReasoningEffort !== saved.supportsReasoningEffort) {
    fields.push("supportsReasoningEffort");
  }
  if (draft.supportsUsageInStreaming !== saved.supportsUsageInStreaming) {
    fields.push("supportsUsageInStreaming");
  }
  if (draft.maxTokensField !== saved.maxTokensField) fields.push("maxTokensField");
  if (draft.maxAgentTurns !== saved.maxAgentTurns) fields.push("maxAgentTurns");
  if (draft.maxToolCalls !== saved.maxToolCalls) fields.push("maxToolCalls");
  if (draft.officeInstructions !== saved.officeInstructions) fields.push("officeInstructions");
  if (draft.companyInstructions !== saved.companyInstructions) fields.push("companyInstructions");
  if (draft.replyInstructions !== saved.replyInstructions) fields.push("replyInstructions");
  if (draft.runTimeoutMs !== saved.runTimeoutMs) fields.push("runTimeoutMs");
  if (draft.modelApiKey.length > 0) fields.push("modelApiKey");
  return fields;
});

const dirtyCount = computed(() => dirtyFields.value.length);
const dirty = computed(() => dirtyCount.value > 0);

const snapshotRows = computed<SnapshotRow[]>(() => {
  const current = view.value;
  if (!current) return [];
  return SNAPSHOT_FIELDS.filter((field) => !SECRET_FIELDS.has(field)).map((field) => {
    const saved = fieldValue(current.saved, field);
    const active = fieldValue(current.active, field);
    return {
      field,
      label: fieldLabel(field),
      saved: formatFieldValue(field, saved),
      active: formatFieldValue(field, active),
      changed: !Object.is(saved, active)
    };
  });
});

/** Union of the backend's pending list and the visible snapshot diff. */
const stateChangedFields = computed<string[]>(() => {
  const current = view.value;
  if (!current) return [];
  const fields = new Set(current.changedFields);
  for (const row of snapshotRows.value) if (row.changed) fields.add(row.field);
  return Array.from(fields);
});

const stateHasPending = computed(
  () => stateChangedFields.value.length > 0 || view.value?.pendingRestart === true
);
const pendingFieldLabels = computed(() => {
  if (stateChangedFields.value.length > 0) {
    return stateChangedFields.value.map((field) => fieldLabel(field)).join("、");
  }
  return view.value?.pendingRestart ? "后端标记待应用（未提供字段明细）" : "无";
});
const lockedFieldLabels = computed(() =>
  view.value ? view.value.locked.map((field) => fieldLabel(field)) : []
);

const rules: Record<string, FieldRule[]> = {
  modelBaseUrl: [
    { required: true, message: "请填写模型接口根地址" },
    {
      validator: (value, callback) => {
        const text = typeof value === "string" ? value : "";
        callback(modelUrlError(text) ?? undefined);
      }
    }
  ],
  modelId: [
    { required: true, message: "请填写模型 ID" },
    { maxLength: 200, message: "模型 ID 不能超过 200 个字符" },
    {
      validator: (value, callback) => {
        const text = typeof value === "string" ? value : "";
        callback(text.trim().length > 0 ? undefined : "请填写模型 ID");
      }
    }
  ],
  modelApiKey: [
    { maxLength: 4096, message: "模型 API Key 不能超过 4096 个字符" },
    {
      validator: (value, callback) => {
        const text = typeof value === "string" ? value : "";
        if (text.length === 0) {
          callback(apiKeyConfigured.value ? undefined : "尚未配置模型密钥，请填写 API Key");
          return;
        }
        if (text !== text.trim() || text.trim().length === 0) {
          callback("密钥首尾不能有空白字符");
          return;
        }
        callback();
      }
    }
  ],
  contextWindow: [
    {
      required: true,
      type: "number",
      min: 8192,
      max: 2000000,
      message: "上下文窗口需在 8,192–2,000,000 之间"
    }
  ],
  maxOutputTokens: [
    {
      required: true,
      type: "number",
      min: 256,
      max: 128000,
      message: "最大输出需在 256–128,000 之间"
    },
    {
      validator: (value, callback) => {
        if (
          typeof value === "number" &&
          typeof draft.contextWindow === "number" &&
          value >= draft.contextWindow
        ) {
          callback("最大输出必须小于上下文窗口");
          return;
        }
        callback();
      }
    }
  ],
  maxAgentTurns: [
    { required: true, type: "number", min: 1, max: 30, message: "模型回合数需在 1–30 之间" }
  ],
  maxToolCalls: [
    { required: true, type: "number", min: 1, max: 1000, message: "工具调用次数需在 1–1000 之间" }
  ],
  runTimeoutMs: [
    {
      required: true,
      type: "number",
      min: 1000,
      max: 600000,
      message: "执行超时需在 1,000–600,000 毫秒之间"
    }
  ],
  thinkingLevel: [
    { required: true, message: "请选择思考级别" },
    {
      validator: (value, callback) => {
        const text = typeof value === "string" ? value : "";
        if (!THINKING_OPTIONS.some((item) => item.value === text)) {
          callback("请选择有效的思考级别");
          return;
        }
        if (!draft.reasoning && text !== "off") {
          callback("关闭推理时，思考级别必须为 off");
          return;
        }
        callback();
      }
    }
  ],
  maxTokensField: [
    { required: true, message: "请选择输出上限字段" },
    {
      validator: (value, callback) => {
        const text = typeof value === "string" ? value : "";
        callback(
          MAX_TOKENS_OPTIONS.some((item) => item.value === text)
            ? undefined
            : "请选择有效的输出上限字段"
        );
      }
    }
  ]
};

watch(
  () => draft.reasoning,
  (enabled) => {
    if (!enabled && draft.thinkingLevel !== "off") draft.thinkingLevel = "off";
  }
);

watch([() => draft.modelBaseUrl, () => draft.modelId, () => draft.modelApiKey], () => {
  testOutcome.value = null;
});

watch(
  draft,
  () => {
    validationMessage.value = "";
  },
  { deep: true }
);

onMounted(() => {
  window.addEventListener("beforeunload", handleBeforeUnload);
  void loadConfig(true);
  void loadHistory();
});

onBeforeUnmount(() => {
  window.removeEventListener("beforeunload", handleBeforeUnload);
});

function emptyDraft(): ConfigDraft {
  return {
    modelBaseUrl: "",
    modelId: "",
    modelApiKey: "",
    contextWindow: undefined,
    maxOutputTokens: undefined,
    reasoning: true,
    thinkingLevel: "low",
    supportsDeveloperRole: false,
    supportsReasoningEffort: true,
    supportsUsageInStreaming: true,
    maxTokensField: "max_completion_tokens",
    maxAgentTurns: undefined,
    maxToolCalls: undefined,
    officeInstructions: "",
    companyInstructions: "",
    replyInstructions: "",
    runTimeoutMs: undefined
  };
}

function settingsToDraft(settings: AdminConfigValues): ConfigDraft {
  return {
    modelBaseUrl: settings.modelBaseUrl,
    modelId: settings.modelId,
    modelApiKey: "",
    contextWindow: settings.contextWindow,
    maxOutputTokens: settings.maxOutputTokens,
    reasoning: settings.reasoning,
    thinkingLevel: settings.thinkingLevel,
    supportsDeveloperRole: settings.supportsDeveloperRole,
    supportsReasoningEffort: settings.supportsReasoningEffort,
    supportsUsageInStreaming: settings.supportsUsageInStreaming,
    maxTokensField: settings.maxTokensField,
    maxAgentTurns: settings.maxAgentTurns,
    maxToolCalls: settings.maxToolCalls,
    officeInstructions: settings.officeInstructions,
    companyInstructions: settings.companyInstructions,
    replyInstructions: settings.replyInstructions,
    runTimeoutMs: settings.runTimeoutMs
  };
}

function resetDraftFromSaved() {
  const current = view.value;
  if (!current) return;
  Object.assign(draft, settingsToDraft(current.saved));
  testOutcome.value = null;
  validationMessage.value = "";
  void nextTick(() => formRef.value?.clearValidate());
}

function applyView(next: AdminConfigView, resetDraft: boolean) {
  view.value = next;
  configError.value = "";
  configConflict.value = false;
  if (resetDraft) resetDraftFromSaved();
}

async function loadConfig(resetDraft: boolean): Promise<boolean> {
  if (loading.value) return false;
  loading.value = true;
  configError.value = "";
  try {
    const { data } = await adminApi.config();
    applyView(data, resetDraft);
    if (resetDraft) {
      saveError.value = "";
      saveIssues.value = [];
      saveNotice.value = "";
      restoreError.value = "";
      restoreIssues.value = [];
      validationMessage.value = "";
    }
    return true;
  } catch (error) {
    configError.value = errorOf(error, "加载配置失败，请稍后重试").message;
    return false;
  } finally {
    loading.value = false;
  }
}

async function loadHistory() {
  const request = ++historyRequest;
  historyLoading.value = true;
  historyError.value = "";
  try {
    const { data } = await adminApi.changeHistory();
    if (request === historyRequest) historyRecords.value = data.changes;
  } catch (error) {
    if (request === historyRequest) {
      historyError.value = errorOf(error, "加载审计记录失败，请稍后重试").message;
    }
  } finally {
    if (request === historyRequest) historyLoading.value = false;
  }
}

async function reloadAll(resetDraft = true) {
  await Promise.all([loadConfig(resetDraft), loadHistory()]);
}

function requestRefresh() {
  if (busy.value) return;
  if (!dirty.value) {
    void reloadAll(true);
    return;
  }
  Modal.confirm({
    title: "放弃未保存修改并刷新？",
    content: "刷新会从服务端重新读取配置和审计记录，本地草稿与未保存的密钥输入将丢失。",
    okText: "刷新",
    cancelText: "取消",
    okButtonProps: { status: "danger" },
    onOk: () => {
      void reloadAll(true);
    }
  });
}

async function rebaseKeepingDraft() {
  if (busy.value) return;
  const loaded = await loadConfig(false);
  if (!loaded) return;
  saveError.value = "";
  saveIssues.value = [];
  saveNotice.value = "";
  restoreError.value = "";
  restoreIssues.value = [];
  testOutcome.value = null;
  validationMessage.value = "";
  void nextTick(() => formRef.value?.clearValidate());
  Message.info("已载入最新修订，本地草稿已保留；请核对后重新保存");
  void loadHistory();
}

function handleBeforeUnload(event: BeforeUnloadEvent) {
  if (!dirty.value) return;
  event.preventDefault();
  event.returnValue = "";
}

function jumpTo(key: string) {
  if (!["overview", "model", "generation", "limits", "instructions", "state", "audit"].includes(key)) {
    return;
  }
  activeSection.value = key;
  document.getElementById(key)?.scrollIntoView({ behavior: "smooth", block: "start" });
}

function fieldLabel(field: string): string {
  return FIELD_LABELS[field] ?? field;
}

function fieldValue(settings: AdminConfigValues, field: string): unknown {
  return (settings as unknown as Record<string, unknown>)[field];
}

function formatFieldValue(field: string, value: unknown): string {
  if (value === undefined || value === null) return "—";
  if (typeof value === "boolean") {
    if (field.startsWith("supports")) return value ? "支持" : "不支持";
    return value ? "开启" : "关闭";
  }
  if (typeof value === "number") return String(value);
  if (typeof value === "string") {
    if (INSTRUCTION_FIELDS.has(field)) {
      return value.length > 0 ? `${value.length} 字` : "（空）";
    }
    return value.length > 0 ? value : "（空）";
  }
  try {
    return JSON.stringify(value) ?? "—";
  } catch {
    return "—";
  }
}

function formatTime(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : formatter.format(date);
}

function issueLabels(issues: string[]): string {
  return issues.map((field) => fieldLabel(field)).join("、");
}

function modelUrlError(value: string): string | null {
  if (value.trim().length === 0) return "请填写模型接口根地址";
  if (value !== value.trim()) return "模型接口地址首尾不能有空白字符";
  if (value.length > 4096) return "模型接口地址不能超过 4096 个字符";
  try {
    const url = new URL(value);
    if (url.protocol !== "http:" && url.protocol !== "https:") {
      return "模型接口地址必须以 http:// 或 https:// 开头";
    }
    if (url.hostname.length === 0) return "模型接口地址缺少主机名";
    if (url.username.length > 0 || url.password.length > 0) {
      return "模型接口地址不能包含登录信息";
    }
    if (url.search.length > 0 || url.hash.length > 0) {
      return "模型接口地址不能包含查询参数或片段";
    }
  } catch {
    return "请输入有效的模型接口地址";
  }
  return null;
}

function onValidationFailed() {
  validationMessage.value = "请修正标红的配置字段后再保存。";
}

function collectChanges(current: AdminConfigView): ConfigChangeSet {
  const saved = current.saved;
  const payload: ConfigChangeSet = {};
  if (draft.modelBaseUrl !== saved.modelBaseUrl) payload.modelBaseUrl = draft.modelBaseUrl;
  if (draft.modelId !== saved.modelId) payload.modelId = draft.modelId;
  if (draft.contextWindow !== undefined && draft.contextWindow !== saved.contextWindow) {
    payload.contextWindow = draft.contextWindow;
  }
  if (draft.maxOutputTokens !== undefined && draft.maxOutputTokens !== saved.maxOutputTokens) {
    payload.maxOutputTokens = draft.maxOutputTokens;
  }
  if (draft.reasoning !== saved.reasoning) payload.reasoning = draft.reasoning;
  if (draft.thinkingLevel !== saved.thinkingLevel) payload.thinkingLevel = draft.thinkingLevel;
  if (draft.supportsDeveloperRole !== saved.supportsDeveloperRole) {
    payload.supportsDeveloperRole = draft.supportsDeveloperRole;
  }
  if (draft.supportsReasoningEffort !== saved.supportsReasoningEffort) {
    payload.supportsReasoningEffort = draft.supportsReasoningEffort;
  }
  if (draft.supportsUsageInStreaming !== saved.supportsUsageInStreaming) {
    payload.supportsUsageInStreaming = draft.supportsUsageInStreaming;
  }
  if (draft.maxTokensField !== saved.maxTokensField) payload.maxTokensField = draft.maxTokensField;
  if (draft.maxAgentTurns !== undefined && draft.maxAgentTurns !== saved.maxAgentTurns) {
    payload.maxAgentTurns = draft.maxAgentTurns;
  }
  if (draft.maxToolCalls !== undefined && draft.maxToolCalls !== saved.maxToolCalls) {
    payload.maxToolCalls = draft.maxToolCalls;
  }
  if (draft.officeInstructions !== saved.officeInstructions) {
    payload.officeInstructions = draft.officeInstructions;
  }
  if (draft.companyInstructions !== saved.companyInstructions) {
    payload.companyInstructions = draft.companyInstructions;
  }
  if (draft.replyInstructions !== saved.replyInstructions) {
    payload.replyInstructions = draft.replyInstructions;
  }
  if (draft.runTimeoutMs !== undefined && draft.runTimeoutMs !== saved.runTimeoutMs) {
    payload.runTimeoutMs = draft.runTimeoutMs;
  }
  if (draft.modelApiKey.length > 0) payload.modelApiKey = draft.modelApiKey;
  return payload;
}

async function save() {
  const current = view.value;
  if (!current || busy.value || configConflict.value || !dirty.value) return;
  validationMessage.value = "";
  saveError.value = "";
  saveIssues.value = [];
  saveNotice.value = "";
  restoreError.value = "";
  restoreIssues.value = [];
  const keyConfigured = current.secrets.modelApiKey?.configured === true;
  if (!keyConfigured && draft.modelApiKey.trim().length === 0) {
    validationMessage.value = "尚未配置模型密钥，请填写 API Key 后再保存。";
    return;
  }
  const previousRevision = current.revision;
  const payload = collectChanges(current);
  if (Object.keys(payload).length === 0) {
    validationMessage.value = "没有需要保存的变更。";
    return;
  }
  saving.value = true;
  try {
    const { data } = await adminApi.saveConfig(previousRevision, payload);
    applyView(data, true);
    saveNotice.value =
      data.revision === previousRevision
        ? "服务端确认配置未发生变化，未生成新修订。"
        : `已保存修订 #${data.revision}。模型、生成参数、业务指令与执行预算将在下一轮对话开始时生效；执行中的轮次保持旧快照。`;
    Message.success("配置已保存");
    void loadHistory();
  } catch (error) {
    const failure = errorOf(error, "保存配置失败，请稍后重试");
    saveError.value = failure.message;
    saveIssues.value = failure.issues ?? [];
    configConflict.value = failure.code === "CONFIG_VERSION_CONFLICT";
    if (configConflict.value) saveNotice.value = "";
  } finally {
    saving.value = false;
  }
}

async function testModel() {
  const current = view.value;
  if (busy.value || !current) return;
  const keyConfigured = current.secrets.modelApiKey?.configured === true;
  testing.value = true;
  testOutcome.value = null;
  validationMessage.value = "";
  try {
    const errors = await formRef.value?.validateField(["modelBaseUrl", "modelId", "modelApiKey"]);
    if (errors) {
      testOutcome.value = { type: "error", message: "请先修正模型接口地址、模型 ID 或密钥格式。" };
      return;
    }
    const modelError = modelUrlError(draft.modelBaseUrl);
    if (modelError || draft.modelId.trim().length === 0) {
      testOutcome.value = {
        type: "error",
        message: modelError ?? "请填写模型 ID。"
      };
      return;
    }
    if (draft.modelApiKey.length === 0 && !keyConfigured) {
      testOutcome.value = {
        type: "error",
        message: "尚未配置模型密钥，请填写 API Key 后再测试。"
      };
      return;
    }
    const payload: ConfigChangeSet = {
      modelBaseUrl: draft.modelBaseUrl,
      modelId: draft.modelId
    };
    if (draft.modelApiKey.length > 0) payload.modelApiKey = draft.modelApiKey;
    const { data } = await adminApi.testModel(payload);
    testOutcome.value = {
      type: "success",
      message: `${data.message}（使用当前草稿参数测试，尚未保存）`
    };
  } catch (error) {
    testOutcome.value = {
      type: "error",
      message: errorOf(error, "模型连接测试失败，请检查地址、模型 ID 与密钥").message
    };
  } finally {
    testing.value = false;
  }
}

function requestDiscard() {
  const current = view.value;
  if (!current || busy.value || configConflict.value) return;
  Modal.confirm({
    title: "放弃未保存修改？",
    content: "将丢弃本地草稿并恢复为最近一次保存的配置。",
    okText: "放弃修改",
    cancelText: "取消",
    okButtonProps: { status: "danger" },
    onOk: () => {
      resetDraftFromSaved();
      saveError.value = "";
      saveIssues.value = [];
      saveNotice.value = "";
      Message.info("已放弃未保存修改");
    }
  });
}

function requestRestore(row: AdminChangeRecord) {
  const current = view.value;
  if (!current || busy.value || configConflict.value || row.revision === current.revision) return;
  const target = row.revision;
  const currentRevision = current.revision;
  Modal.confirm({
    title: `恢复到修订 #${target}`,
    content: `将以修订 #${target} 的配置内容新建一个配置版本（当前修订 #${currentRevision}）。恢复成功后未保存的修改会被丢弃，审计历史不会删除。`,
    okText: "确认恢复",
    cancelText: "取消",
    okButtonProps: { status: "danger" },
    onBeforeOk: async () => {
      await restoreRevision(target);
      return true;
    }
  });
}

async function restoreRevision(revision: number): Promise<boolean> {
  const current = view.value;
  if (!current) return false;
  restoringRevision.value = revision;
  restoreError.value = "";
  restoreIssues.value = [];
  saveError.value = "";
  saveIssues.value = [];
  saveNotice.value = "";
  try {
    const { data } = await adminApi.restoreConfig(current.revision, revision);
    applyView(data, true);
    saveNotice.value = `已恢复到修订 #${revision}，并生成修订 #${data.revision}。新配置将在下一轮对话生效；执行中的轮次保持旧快照。`;
    Message.success("配置已恢复");
    void loadHistory();
    return true;
  } catch (error) {
    const failure = errorOf(error, "恢复配置失败，请稍后重试");
    restoreError.value = failure.message;
    restoreIssues.value = failure.issues ?? [];
    configConflict.value = failure.code === "CONFIG_VERSION_CONFLICT";
    if (configConflict.value) saveNotice.value = "";
    return false;
  } finally {
    restoringRevision.value = null;
  }
}

function clearSaveNotice() {
  saveNotice.value = "";
}

async function logout() {
  logoutBusy.value = true;
  try {
    await session.logout();
  } catch {
    // The session store clears local auth in its finally block; continue to login.
  } finally {
    try {
      await router.replace({ name: "login" });
    } finally {
      logoutBusy.value = false;
    }
  }
}

function requestLogout() {
  if (busy.value || logoutBusy.value) return;
  if (!dirty.value) {
    void logout();
    return;
  }
  Modal.confirm({
    title: "放弃未保存修改并退出登录？",
    content: "本地草稿尚未保存，退出后将丢失。",
    okText: "退出登录",
    cancelText: "取消",
    okButtonProps: { status: "danger" },
    onOk: () => {
      void logout();
    }
  });
}
</script>

<style scoped>
.shell {
  min-height: 100%;
}

.shell-sider {
  position: sticky;
  top: 0;
  height: 100vh;
  overflow-y: auto;
  background: var(--color-bg-2);
  border-right: 1px solid var(--color-border-2);
}

.side-brand {
  padding: 18px 20px 14px;
}

.side-brand-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text-1);
}

.side-brand-sub {
  margin-top: 2px;
  font-size: 12px;
  color: var(--color-text-3);
}

.side-menu {
  background: transparent;
}

.side-meta {
  margin: 20px 16px;
  padding: 12px;
  border: 1px solid var(--color-border-2);
  border-radius: 4px;
}

.side-meta-title {
  margin-bottom: 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--color-text-2);
}

.side-meta-line {
  font-size: 12px;
  line-height: 1.7;
  color: var(--color-text-3);
  word-break: break-word;
}

.shell-main {
  min-width: 0;
}

.shell-header {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px 12px;
  min-height: 56px;
  padding: 8px 24px;
  box-sizing: border-box;
  background: var(--color-bg-2);
  border-bottom: 1px solid var(--color-border-2);
}

.header-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text-1);
}

.header-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

.shell-content {
  min-height: calc(100vh - 56px);
}

.page-content {
  width: 100%;
  max-width: 1120px;
  margin: 0 auto;
  padding: 24px 24px 112px;
  box-sizing: border-box;
}

.page-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.page-heading-text {
  min-width: 0;
}

.page-title {
  margin: 0;
  font-size: 20px;
  line-height: 1.4;
  color: var(--color-text-1);
}

.page-subtitle {
  max-width: 720px;
  margin: 4px 0 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--color-text-3);
}

.page-status {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.overview-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.overview-card {
  min-width: 0;
  padding: 14px 16px;
  background: var(--color-bg-2);
  border: 1px solid var(--color-border-2);
  border-radius: 4px;
}

.overview-label {
  font-size: 12px;
  color: var(--color-text-3);
}

.overview-value {
  margin-top: 4px;
  font-size: 20px;
  font-weight: 600;
  color: var(--color-text-1);
  word-break: break-word;
}

.overview-sub {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--color-text-3);
}

.section-card {
  margin-bottom: 16px;
  scroll-margin-top: 76px;
}

.section-anchor {
  scroll-margin-top: 76px;
}

.notice-block {
  margin-bottom: 16px;
}

.notice-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}

.notice-issues {
  margin-top: 4px;
  font-size: 12px;
  word-break: break-word;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}

.form-grid--three {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.span-2 {
  grid-column: 1 / -1;
}

.full-width {
  width: 100%;
}

.field-note {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--color-text-3);
}

.switch-line {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 32px;
  color: var(--color-text-2);
}

.inline-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
  margin-top: 4px;
}

.state-descriptions {
  margin-bottom: 12px;
}

.state-note {
  margin: 12px 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--color-text-3);
}

.snapshot-collapse {
  margin-top: 4px;
}

.snapshot-wrap {
  width: 100%;
  overflow-x: auto;
}

.snapshot-table {
  width: 100%;
  min-width: 640px;
  border-collapse: collapse;
  font-size: 13px;
}

.snapshot-table th,
.snapshot-table td {
  padding: 9px 12px;
  text-align: left;
  vertical-align: top;
  border-bottom: 1px solid var(--color-border-2);
  word-break: break-word;
}

.snapshot-table th {
  font-size: 12px;
  font-weight: 500;
  color: var(--color-text-2);
  background: var(--color-fill-1);
}

.snapshot-field {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  color: var(--color-text-1);
}

.snapshot-field code {
  font-size: 11px;
  color: var(--color-text-3);
}

.field-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.audit-wrap {
  margin-top: 8px;
}

.table-spin {
  width: 100%;
}

.audit-table {
  width: 100%;
  min-width: 760px;
  border-collapse: collapse;
  font-size: 13px;
}

.audit-table th,
.audit-table td {
  padding: 9px 12px;
  text-align: left;
  vertical-align: top;
  border-bottom: 1px solid var(--color-border-2);
  word-break: break-word;
}

.audit-table th {
  font-size: 12px;
  font-weight: 500;
  color: var(--color-text-2);
  background: var(--color-fill-1);
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.no-wrap {
  white-space: nowrap;
}

.muted {
  font-size: 12px;
  color: var(--color-text-3);
}

.save-bar {
  position: fixed;
  right: 0;
  bottom: 0;
  left: 232px;
  z-index: 20;
  padding: 10px 24px;
  background: var(--color-bg-2);
  border-top: 1px solid var(--color-border-2);
}

.save-bar-inner {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px 16px;
  width: 100%;
  max-width: 1120px;
  margin: 0 auto;
}

.save-status {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  font-size: 13px;
  color: var(--color-text-1);
}

.save-status strong {
  overflow-wrap: anywhere;
}

.save-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  margin: -1px;
  padding: 0;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
  border: 0;
}

@media (max-width: 1180px) {
  .overview-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .form-grid--three {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 900px) {
  .shell-sider {
    display: none;
  }

  .save-bar {
    left: 0;
    padding: 10px 16px;
  }

  .shell-header {
    padding: 8px 16px;
  }

  .page-content {
    padding: 16px 16px 120px;
  }
}

@media (max-width: 720px) {
  .form-grid,
  .form-grid--three {
    grid-template-columns: minmax(0, 1fr);
  }

  .span-2 {
    grid-column: auto;
  }

  .overview-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .header-title {
    display: none;
  }
}
</style>
