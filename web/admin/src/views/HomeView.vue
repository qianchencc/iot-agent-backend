<template>
  <div class="config-page">
    <div v-if="!view && loading" class="page-content">
      <el-card shadow="never">
        <el-skeleton animated>
          <el-skeleton-item :rows="8" />
        </el-skeleton>
      </el-card>
    </div>

    <div v-else-if="!view" class="page-content">
      <el-result
        status="error"
        title="配置加载失败"
        :sub-title="configError || '暂时无法读取配置，请稍后重试。'"
      >
        <template #extra>
          <el-button type="primary" :loading="loading" @click="reloadAll(true)">重试</el-button>
        </template>
      </el-result>
    </div>

    <div v-else class="page-content">
      <div class="page-heading">
        <div class="page-heading-text">
          <h1 class="page-title">模型与业务配置</h1>
          <p class="page-subtitle">
            模型、业务指令、通用预算与变更审计。保存后生成新的配置快照：模型与业务字段由下一轮对话读取，任务扫描间隔由下一个扫描周期读取，观测配置需重启后由 sidecar 采用；执行中的轮次保持旧快照。
          </p>
        </div>
        <div class="page-status">
          <el-tag :type="dirty ? 'warning' : 'success'">{{ dirty ? "草稿未保存" : "已同步" }}</el-tag>
          <el-tag :type="stateHasPending ? 'warning' : 'info'">
            {{ stateHasPending ? "快照待应用" : "配置快照就绪" }}
          </el-tag>
          <el-button size="small" :loading="loading" :disabled="busy" @click="requestRefresh">
            刷新
          </el-button>
        </div>
      </div>

      <el-alert v-if="configError" type="error" class="notice-block">
        <div>{{ configError }}</div>
        <div class="notice-actions">
          <el-button size="small" native-type="button" @click="requestRefresh">重新加载</el-button>
        </div>
      </el-alert>

      <el-alert v-if="configConflict" type="warning" class="notice-block">
        <div>配置已被其他操作修改，保存已暂停；你的本地草稿仍然保留。</div>
        <div>
          载入最新修订并保留草稿后可以重新保存，但可能覆盖服务端的新值，请先核对。放弃草稿会丢失当前全部未保存修改。
        </div>
        <div class="notice-actions">
          <el-button
            size="small"
            type="primary"
            native-type="button"
            :loading="loading"
            @click="rebaseKeepingDraft"
          >
            载入最新修订（保留草稿）
          </el-button>
          <el-button size="small" native-type="button" :disabled="busy" @click="requestRefresh">
            放弃草稿并刷新
          </el-button>
        </div>
      </el-alert>

      <el-alert v-if="saveError && !configConflict" type="error" class="notice-block">
        <div>{{ saveError }}</div>
        <div v-if="saveIssues.length" class="notice-issues">
          需要检查的字段：{{ issueLabels(saveIssues) }}
        </div>
      </el-alert>

      <el-alert v-if="saveNotice" type="success" class="notice-block" closable @close="clearSaveNotice">
        {{ saveNotice }}
      </el-alert>

      <el-alert v-if="validationMessage" type="warning" class="notice-block">
        {{ validationMessage }}
      </el-alert>

      <div id="overview" class="overview-grid section-anchor">
        <div class="overview-card">
          <div class="overview-label">已保存修订</div>
          <div class="overview-value">#{{ view.revision }}</div>
          <div class="overview-sub">{{ dirty ? `含 ${dirtyCount} 项本地修改` : "与服务端一致" }}</div>
        </div>
        <div class="overview-card">
          <div class="overview-label">下一轮读取快照修订</div>
          <div class="overview-value">#{{ view.activeRevision }}</div>
          <div class="overview-sub">下一轮对话读取的配置快照基线；观测配置需重启后由 sidecar 采用</div>
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

      <el-form
        ref="formRef"
        :model="draft"
        :rules="rules"
        label-position="top"
        :disabled="busy"
        @submit.prevent="save"
      >
        <el-card id="model" class="section-card" shadow="never">
          <template #header>
            <div class="card-head">
              <span class="card-title">模型连接</span>
              <div class="header-actions">
                <el-tag :type="apiKeyConfigured ? 'success' : 'danger'">
                  {{ apiKeyConfigured ? "密钥已配置" : "密钥未配置" }}
                </el-tag>
                <el-tag v-if="draft.modelApiKey.length > 0" type="warning">新密钥待保存</el-tag>
              </div>
            </div>
          </template>
          <div class="form-grid">
            <el-form-item prop="modelBaseUrl" label="模型接口根地址">
              <el-input v-model="draft.modelBaseUrl" clearable placeholder="https://api.example.com/v1" />
              <div class="field-note">
                OpenAI 兼容接口根地址；必须是 http(s) 地址，不能带登录信息、查询参数或片段。
              </div>
            </el-form-item>
            <el-form-item prop="modelId" label="模型 ID">
              <el-input v-model="draft.modelId" clearable placeholder="例如 gpt-4.1-mini" />
            </el-form-item>
            <el-form-item
              prop="modelApiKey"
              label="模型 API Key（替换）"
              class="span-2"
              :required="!apiKeyConfigured"
            >
              <el-input
                v-model="draft.modelApiKey"
                type="password"
                clearable
                show-password
                autocomplete="new-password"
                :placeholder="apiKeyConfigured ? '已配置，留空保持当前密钥' : '尚未配置，请输入 API Key'"
              />
              <div class="field-note">
                已保存密钥：{{ apiKeyConfigured ? "已配置（不回显）" : "未配置" }}；下一轮读取快照：{{
                  apiKeyActiveConfigured ? "已配置" : "未配置"
                }}。{{
                  apiKeyConfigured
                    ? "只有填写新值才会替换，留空表示保持当前密钥。"
                    : "当前没有可用密钥，必须填写后才能保存或测试。"
                }}
              </div>
            </el-form-item>
          </div>
          <div class="inline-actions">
            <el-button native-type="button" :loading="testing" :disabled="busy" @click="testModel">
              测试模型连接
            </el-button>
            <span class="field-note">
              测试会调用 Java 管理接口，使用服务端已保存密钥加当前草稿中的地址、模型 ID 与新密钥；不会保存配置，也不会回显密钥。测试只发送固定 1 token 上限的最小请求并丢弃响应内容，仅验证服务端接受该连接与鉴权，不代表生成参数兼容或回答正确。
            </span>
          </div>
          <div v-if="testOutcome" class="notice-block" aria-live="polite">
            <el-alert :type="testOutcome.type === 'success' ? 'success' : 'error'">
              {{ testOutcome.message }}
            </el-alert>
          </div>
        </el-card>

        <el-card id="generation" class="section-card" shadow="never">
          <template #header><span class="card-title">输出、上下文与推理兼容</span></template>
          <div class="form-grid">
            <el-form-item prop="contextWindow" label="上下文窗口">
              <el-input-number
                v-model="draft.contextWindow"
                class="full-width"
                :min="8192"
                :max="2000000"
                :precision="0"
                :step="1024"
              />
              <div class="field-note">范围 8,192–2,000,000 tokens。</div>
            </el-form-item>
            <el-form-item prop="maxOutputTokens" label="最大输出 tokens">
              <el-input-number
                v-model="draft.maxOutputTokens"
                class="full-width"
                :min="256"
                :max="128000"
                :precision="0"
                :step="256"
              />
              <div class="field-note">范围 256–128,000，且必须小于上下文窗口。</div>
            </el-form-item>
            <el-form-item prop="reasoning" label="启用推理">
              <div class="switch-line">
                <el-switch v-model="draft.reasoning" />
                <span>{{ draft.reasoning ? "开启" : "关闭" }}</span>
              </div>
              <div class="field-note">关闭推理时，思考级别必须为“关闭”。</div>
            </el-form-item>
            <el-form-item prop="thinkingLevel" label="思考级别">
              <el-select v-model="draft.thinkingLevel" class="full-width" :disabled="!draft.reasoning">
                <el-option v-for="item in THINKING_OPTIONS" :key="item.value" :value="item.value">
                  {{ item.label }}
                </el-option>
              </el-select>
              <div class="field-note">off / low / medium / high，随模型能力选择。</div>
            </el-form-item>
            <el-form-item prop="maxTokensField" label="输出上限字段">
              <el-select v-model="draft.maxTokensField" class="full-width">
                <el-option v-for="item in MAX_TOKENS_OPTIONS" :key="item.value" :value="item.value">
                  {{ item.label }}
                </el-option>
              </el-select>
              <div class="field-note">与服务商兼容；不确定时保留 max_completion_tokens。</div>
            </el-form-item>
            <el-form-item prop="supportsDeveloperRole" label="支持 developer 角色">
              <div class="switch-line">
                <el-switch v-model="draft.supportsDeveloperRole" />
                <span>{{ draft.supportsDeveloperRole ? "支持" : "不支持" }}</span>
              </div>
            </el-form-item>
            <el-form-item prop="supportsReasoningEffort" label="支持推理强度参数">
              <div class="switch-line">
                <el-switch v-model="draft.supportsReasoningEffort" />
                <span>{{ draft.supportsReasoningEffort ? "支持" : "不支持" }}</span>
              </div>
            </el-form-item>
            <el-form-item prop="supportsUsageInStreaming" label="流式返回用量统计">
              <div class="switch-line">
                <el-switch v-model="draft.supportsUsageInStreaming" />
                <span>{{ draft.supportsUsageInStreaming ? "支持" : "不支持" }}</span>
              </div>
            </el-form-item>
          </div>
        </el-card>

        <el-card id="limits" class="section-card" shadow="never">
          <template #header><span class="card-title">通用执行预算</span></template>
          <div class="form-grid form-grid--three">
            <el-form-item prop="maxAgentTurns" label="单轮最大模型回合数">
              <el-input-number
                v-model="draft.maxAgentTurns"
                class="full-width"
                :min="1"
                :max="30"
                :precision="0"
              />
              <div class="field-note">范围 1–30。</div>
            </el-form-item>
            <el-form-item prop="maxToolCalls" label="单轮最大工具调用次数">
              <el-input-number
                v-model="draft.maxToolCalls"
                class="full-width"
                :min="1"
                :max="1000"
                :precision="0"
              />
              <div class="field-note">范围 1–1000。</div>
            </el-form-item>
            <el-form-item prop="runTimeoutMs" label="单轮执行超时（毫秒）">
              <el-input-number
                v-model="draft.runTimeoutMs"
                class="full-width"
                :min="1000"
                :max="600000"
                :precision="0"
                :step="1000"
              />
              <div class="field-note">范围 1,000–600,000 毫秒。</div>
            </el-form-item>
            <el-form-item prop="heartbeatMs" label="会话心跳间隔（毫秒）">
              <el-input-number
                v-model="draft.heartbeatMs"
                class="full-width"
                :min="0"
                :max="60000"
                :precision="0"
                :step="1000"
              />
              <div class="field-note">{{ fieldNote("heartbeatMs") || "范围 0–60,000 毫秒，0 表示关闭。" }}</div>
            </el-form-item>
            <el-form-item prop="scanIntervalMs" label="任务扫描间隔（毫秒）">
              <el-input-number
                v-model="draft.scanIntervalMs"
                class="full-width"
                :min="1000"
                :max="60000"
                :precision="0"
                :step="500"
              />
              <div class="field-note">{{ fieldNote("scanIntervalMs") || "范围 1,000–60,000 毫秒。" }}</div>
            </el-form-item>
          </div>
          <el-alert type="info" :closable="false">
            限额在一轮对话开始时读取；正在执行的轮次继续使用开始时的预算，不会在新旧配置之间切换。
          </el-alert>
        </el-card>

        <el-card id="instructions" class="section-card" shadow="never">
          <template #header>
            <div class="card-head">
              <span class="card-title">业务指令</span>
              <div class="header-actions">
                <el-tag size="small" type="info" effect="plain">7 个必填基础段落</el-tag>
                <el-tag size="small" type="info" effect="plain">3 个可选追加</el-tag>
              </div>
            </div>
          </template>
          <el-alert type="info" :closable="false">
            <div>
              指令由后端在每次运行时拼装：办公 = 办公基础 + 办公追加 + 回复风格追加 + 共用；
              公司 = 公司基础 + 任务阶段 + 公司追加 + 回复风格追加 + 共用；检查点、收尾、压缩指令原样使用。
            </div>
            <div>
              7 个基础段落必填；3 个可选追加可留空，留空即不追加。此处编辑各段落的完整文本，
              保存后下一轮对话生效，正在执行的轮次保持旧指令。
            </div>
            <div>
              每次运行注入的当前时间、授权目录、参考资料、会话上下文与工具契约由后端按运行生成，不在此编辑。
            </div>
          </el-alert>
          <el-collapse v-model="openInstructionGroups" class="instruction-groups">
            <el-collapse-item v-for="group in INSTRUCTION_GROUPS" :key="group.name" :name="group.name">
              <template #title>
                <span class="group-title">
                  <span class="group-name">{{ group.title }}</span>
                  <span class="group-sub">{{ group.subtitle }}</span>
                  <el-tag
                    v-if="instructionGroupDirtyFields(group.name).length > 0"
                    size="small"
                    type="warning"
                  >
                    已修改 {{ instructionGroupDirtyFields(group.name).length }} 项
                  </el-tag>
                </span>
              </template>
              <InstructionSectionField
                v-for="field in INSTRUCTION_GROUP_FIELDS[group.name]"
                :key="field.key"
                v-model="draft[field.key]"
                :field-key="field.key"
                :label="field.label"
                :required="field.required"
                :max-length="instructionMaxLength(field.key)"
                :placeholder="field.placeholder"
                :note="field.note"
                :min-rows="field.minRows"
                :max-rows="field.maxRows"
              />
            </el-collapse-item>
          </el-collapse>
        </el-card>

        <el-card id="observability" class="section-card" shadow="never">
          <template #header>
            <div class="card-head">
              <span class="card-title">运行观测（Langfuse）</span>
              <div class="header-actions">
                <el-tag :type="draft.langfuseEnabled ? 'success' : 'info'">
                  {{ draft.langfuseEnabled ? "草稿：启用" : "草稿：关闭" }}
                </el-tag>
                <el-tag v-if="view.saved.langfuseEnabled === true" type="success">已保存：启用</el-tag>
                <el-tag v-else type="info">已保存：关闭</el-tag>
              </div>
            </div>
          </template>
          <el-alert
            v-if="observabilityStatus"
            :type="observabilityStatus.adopted === true ? 'success' : 'warning'"
            class="notice-block"
            :closable="false"
          >
            <div>sidecar 实际采用状态：{{ observabilityStatus.detail }}</div>
            <div v-if="observabilityStatus.adopted !== true">
              保存或应用请求本身不等于已采用；下方状态只依据 sidecar 上报的真实快照。
            </div>
          </el-alert>
          <div class="form-grid">
            <el-form-item prop="langfuseEnabled" label="启用运行观测">
              <div class="switch-line">
                <el-switch v-model="draft.langfuseEnabled" />
                <span>{{ draft.langfuseEnabled ? "开启" : "关闭" }}</span>
              </div>
              <div class="field-note">
                {{ fieldNote("langfuseEnabled") || "启用需地址与两把密钥齐备；保存后需受监督重启由 sidecar 采用。" }}
              </div>
            </el-form-item>
            <el-form-item prop="langfuseBaseUrl" label="观测服务地址">
              <el-input
                v-model="draft.langfuseBaseUrl"
                clearable
                :disabled="!draft.langfuseEnabled"
                placeholder="https://langfuse.example.com"
              />
              <div class="field-note">例如 Langfuse 自建实例根地址；保存后需受监督重启生效。</div>
            </el-form-item>
            <el-form-item prop="langfusePublicKey" label="观测公钥（替换）">
              <el-input
                v-model="draft.langfusePublicKey"
                type="password"
                clearable
                show-password
                autocomplete="new-password"
                :disabled="!draft.langfuseEnabled"
                :placeholder="langfuseSecretConfigured('langfusePublicKey') ? '已配置，留空保持当前公钥' : '尚未配置'"
              />
              <div v-if="langfuseClearPending('langfusePublicKey')" class="field-note">
                已排队清除公钥：保存后仅删除已保存值；运行中的 sidecar 仍使用启动时快照，需重启后生效。
                <el-button link size="small" native-type="button" @click="cancelLangfuseClear('langfusePublicKey')">
                  撤销清除
                </el-button>
              </div>
              <el-button
                v-else-if="langfuseSecretConfigured('langfusePublicKey') && !draft.langfuseEnabled"
                size="small"
                native-type="button"
                @click="queueLangfuseClear('langfusePublicKey')"
              >
                清除已保存公钥
              </el-button>
            </el-form-item>
            <el-form-item prop="langfuseSecretKey" label="观测密钥（替换）">
              <el-input
                v-model="draft.langfuseSecretKey"
                type="password"
                clearable
                show-password
                autocomplete="new-password"
                :disabled="!draft.langfuseEnabled"
                :placeholder="langfuseSecretConfigured('langfuseSecretKey') ? '已配置，留空保持当前密钥' : '尚未配置'"
              />
              <div v-if="langfuseClearPending('langfuseSecretKey')" class="field-note">
                已排队清除密钥：保存后仅删除已保存值；运行中的 sidecar 仍使用启动时快照，需重启后生效。
                <el-button link size="small" native-type="button" @click="cancelLangfuseClear('langfuseSecretKey')">
                  撤销清除
                </el-button>
              </div>
              <el-button
                v-else-if="langfuseSecretConfigured('langfuseSecretKey') && !draft.langfuseEnabled"
                size="small"
                native-type="button"
                @click="queueLangfuseClear('langfuseSecretKey')"
              >
                清除已保存密钥
              </el-button>
            </el-form-item>
          </div>
          <el-alert type="info" :closable="false">
            观测配置不参与热更新：sidecar 在进程启动时从 Java 拉取一次快照并固定使用；保存后需受监督重启（未接入受监督应用通道时需按部署流程手动重启后端与 sidecar）。密钥只写不回显；关闭启用开关后可清除已保存密钥。
          </el-alert>
        </el-card>

        <el-card id="deployment" class="section-card" shadow="never">
          <template #header><span class="card-title">只读部署参数</span></template>
          <el-alert type="info" class="notice-block" :closable="false">
            以下参数由部署属性在进程启动时读取并实际消费；管理台保存值无消费者，因此这里只读展示当前启动值，不做假生效。
          </el-alert>
          <el-descriptions :column="1" bordered class="state-descriptions">
            <el-descriptions-item v-for="field in deploymentFields" :key="field.name" :label="field.label">
              <div class="deployment-value">
                <span>{{ formatFieldValue(field.name, view.deploymentSettings?.[field.name]) }} {{ field.unit }}</span>
                <code>{{ field.springKey }}</code>
              </div>
              <div class="field-note">{{ field.consumer }}。{{ field.note }}</div>
            </el-descriptions-item>
          </el-descriptions>
        </el-card>

        <el-card id="state" class="section-card" shadow="never">
          <template #header><span class="card-title">生效状态与完整快照</span></template>
          <el-alert v-if="stateHasPending" type="warning" class="notice-block" :closable="false">
            <div>以下字段的已保存值尚未进入下一轮读取的配置快照：{{ pendingFieldLabels }}。</div>
            <div v-if="view?.pendingRestart">后端待应用标记：是。</div>
            <div>
              当前不在热更新范围的是观测配置（Langfuse）：sidecar 在进程启动时拉取一次快照，保存后需通过受监督重启应用；未接入受监督应用通道时按部署流程手动重启后端与 sidecar。模型、生成参数、业务指令、执行预算与会话心跳由下一轮对话开始时读取；任务扫描间隔由下一个扫描周期读取。可在下方“完整字段快照对照”查看差异。
            </div>
          </el-alert>
          <el-alert v-else type="success" class="notice-block" :closable="false">
            已保存配置已进入下一轮读取的配置快照。正在执行的轮次仍使用其开始时的快照；观测配置（Langfuse）以运行中 sidecar 上报的采用状态为准。
          </el-alert>

          <el-descriptions :column="2" bordered class="state-descriptions">
            <el-descriptions-item label="已保存修订">#{{ view?.revision }}</el-descriptions-item>
            <el-descriptions-item label="下一轮读取快照修订">#{{ view?.activeRevision }}</el-descriptions-item>
            <el-descriptions-item label="模型">
              {{ view?.saved.modelId || "（未配置）" }}
            </el-descriptions-item>
            <el-descriptions-item label="执行预算">
              {{ view?.saved.maxAgentTurns }} 回合 / {{ view?.saved.maxToolCalls }} 次工具 /
              {{ view?.saved.runTimeoutMs }} 毫秒
            </el-descriptions-item>
            <el-descriptions-item label="配置差异">
              {{ stateHasPending ? "存在待应用字段" : "无" }}
            </el-descriptions-item>
            <el-descriptions-item label="自动应用能力">
              {{ view?.applyEnabled ? "部署声明可用（本页不触发进程重启）" : "未接入（本页不重启进程）" }}
            </el-descriptions-item>
            <el-descriptions-item label="部署地址">
              {{ view?.deployment.host }}:{{ view?.deployment.port }} · {{ view?.deployment.environment }}
            </el-descriptions-item>
          </el-descriptions>
          <el-divider />
          <el-space direction="vertical">
            <el-space>
              <strong>智能体会话存储</strong>
              <el-button size="small" :loading="sessionHealthLoading" @click="loadSessionHealth">检查 MySQL 状态</el-button>
            </el-space>
            <el-alert v-if="sessionHealthError" type="error" :closable="false">{{ sessionHealthError }}</el-alert>
            <el-descriptions v-else-if="sessionHealth" :column="2" bordered>
              <el-descriptions-item label="存储">{{ sessionHealth.message }}</el-descriptions-item>
              <el-descriptions-item label="职责">Java / MySQL 持久化，Node 无本地会话库</el-descriptions-item>
              <el-descriptions-item label="会话范围">{{ sessionHealth.details.scopes }}</el-descriptions-item>
              <el-descriptions-item label="会话日志">{{ sessionHealth.details.documents }}</el-descriptions-item>
            </el-descriptions>
            <span v-else class="field-note">尚未检查。备份与恢复统一使用 MySQL 运维流程，不再操作 Node 文件。</span>
          </el-space>

          <div class="state-note">
            保存只会生成新的配置修订，不执行任何进程重启。模型、生成参数、业务指令、执行预算与会话心跳由下一轮对话开始时读取；任务扫描间隔由下一个扫描周期读取；观测配置需受监督重启后由 sidecar 采用，以 sidecar 上报状态为准。完整快照中的指令字段只显示字符数；只读部署参数在本页仅展示当前启动值。
          </div>

          <el-collapse class="snapshot-collapse">
            <el-collapse-item title="完整字段快照对照（不含密钥）" name="snapshot">
              <div class="snapshot-wrap">
                <table class="snapshot-table">
                  <caption class="visually-hidden">已保存配置与下一轮读取快照字段对照</caption>
                  <thead>
                    <tr>
                      <th scope="col">字段</th>
                      <th scope="col">已保存</th>
                      <th scope="col">下一轮读取</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="row in snapshotRows" :key="row.field">
                      <td>
                        <div class="snapshot-field">
                          <span>{{ row.label }}</span>
                          <code>{{ row.field }}</code>
                          <el-tag v-if="row.changed" size="small" type="warning">差异</el-tag>
                        </div>
                      </td>
                      <td>{{ row.saved }}</td>
                      <td>{{ row.active }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </el-collapse-item>
          </el-collapse>
        </el-card>

        <el-card id="history" class="section-card" shadow="never">
          <template #header>
            <div class="card-head">
              <span class="card-title">变更审计与恢复</span>
              <el-button
                size="small"
                native-type="button"
                :loading="historyLoading"
                :disabled="saving || testing || restoringRevision !== null"
                @click="loadHistory"
              >
                刷新审计
              </el-button>
            </div>
          </template>
          <el-alert type="info" class="notice-block" :closable="false">
            审计只记录修订号、时间、操作者与变更字段名，不记录配置值、密钥或供应商凭证。
          </el-alert>

          <el-alert v-if="historyError" type="error" class="notice-block" :closable="false">
            <div>{{ historyError }}</div>
            <div class="notice-actions">
              <el-button size="small" native-type="button" :loading="historyLoading" @click="loadHistory">
                重试
              </el-button>
            </div>
          </el-alert>

          <el-alert v-if="restoreError" type="error" class="notice-block" :closable="false">
            <div>{{ restoreError }}</div>
            <div v-if="restoreIssues.length" class="notice-issues">
              需要检查的字段：{{ issueLabels(restoreIssues) }}
            </div>
          </el-alert>

          <div class="audit-wrap">
            <div v-loading="historyLoading" class="table-spin">
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
                        <el-tag v-for="field in row.changedFields" :key="field" size="small">
                          {{ fieldLabel(field) }}
                        </el-tag>
                      </div>
                      <span v-else class="muted">初始配置</span>
                    </td>
                    <td>
                      <el-button
                        size="small"
                        native-type="button"
                        :loading="restoringRevision === row.revision"
                        :disabled="busy || configConflict || row.revision === view?.revision"
                        @click="requestRestore(row)"
                      >
                        {{ row.revision === view?.revision ? "当前版本" : "恢复" }}
                      </el-button>
                    </td>
                  </tr>
                </tbody>
              </table>
              <el-empty v-else-if="!historyLoading" description="暂无配置变更记录" />
            </div>
          </div>
        </el-card>

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
              <el-button
                native-type="button"
                :disabled="!dirty || busy || configConflict"
                @click="requestDiscard"
              >
                放弃修改
              </el-button>
              <el-button
                type="primary"
                native-type="submit"
                :loading="saving"
                :disabled="!dirty || busy || configConflict"
              >
                保存配置
              </el-button>
            </div>
          </div>
        </div>
      </el-form>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { adminApi, errorOf } from "../api/admin";
import InstructionSectionField from "../components/InstructionSectionField.vue";

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

const FIELD_LABELS = {
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
  officeInstructions: "办公助手追加指令",
  companyInstructions: "公司助手追加指令",
  replyInstructions: "回复风格追加指令",
  officeBaseInstructions: "办公助手基础指令",
  companyBaseInstructions: "公司助手基础指令",
  sharedInstructions: "共用基础指令",
  companyTaskInstructions: "公司任务阶段指令",
  checkpointInstructions: "检查点阶段指令",
  finalizationInstructions: "收尾阶段指令",
  compactionInstructions: "压缩阶段指令",
  runTimeoutMs: "单轮执行超时",
  heartbeatMs: "会话心跳间隔",
  scanIntervalMs: "任务扫描间隔",
  langfuseEnabled: "运行观测启用",
  langfuseBaseUrl: "观测服务地址",
  langfusePublicKey: "观测公钥",
  langfuseSecretKey: "观测密钥",
  draftTtlMs: "动作草稿有效期",
  readbackWaitMs: "状态回读等待",
  controlTimeoutMs: "控制调用超时"
};

const SECRET_FIELDS = new Set(["modelApiKey", "langfusePublicKey", "langfuseSecretKey"]);
const INSTRUCTION_ALL_FIELDS = [
  "officeBaseInstructions",
  "companyBaseInstructions",
  "sharedInstructions",
  "companyTaskInstructions",
  "checkpointInstructions",
  "finalizationInstructions",
  "compactionInstructions",
  "officeInstructions",
  "companyInstructions",
  "replyInstructions"
];
const INSTRUCTION_FIELDS = new Set(INSTRUCTION_ALL_FIELDS);
const INSTRUCTION_MAX_FALLBACK = 65536;

/** Grouped editor layout so ten long instruction texts are never dumped at once. */
const INSTRUCTION_GROUPS = [
  { name: "office", title: "办公助手", subtitle: "基础段落 + 可选追加" },
  { name: "company", title: "公司助手", subtitle: "基础段落 + 任务阶段 + 可选追加" },
  { name: "shared", title: "共用与回复风格", subtitle: "两个来源共用 + 可选追加" },
  { name: "advanced", title: "运行阶段指令", subtitle: "检查点 / 收尾 / 压缩，原样使用" }
];

const INSTRUCTION_GROUP_FIELDS = {
  office: [
    {
      key: "officeBaseInstructions",
      label: "办公助手基础指令",
      required: true,
      note: "办公助手系统提示的开头基础段落。",
      placeholder: "办公助手的角色、业务范围与输出约束。",
      minRows: 6,
      maxRows: 14
    },
    {
      key: "officeInstructions",
      label: "办公助手追加指令",
      required: false,
      note: "可选追加：非空时拼在办公基础段落之后，留空即不追加。",
      placeholder: "需要追加到办公助手指令末尾的内容，可留空。",
      minRows: 3,
      maxRows: 10
    }
  ],
  company: [
    {
      key: "companyBaseInstructions",
      label: "公司助手基础指令",
      required: true,
      note: "公司助手系统提示的开头基础段落。",
      placeholder: "公司助手的角色、业务范围与输出约束。",
      minRows: 6,
      maxRows: 14
    },
    {
      key: "companyTaskInstructions",
      label: "公司任务阶段指令",
      required: true,
      note: "公司助手任务阶段的指令段落，拼在公司基础段落之后。",
      placeholder: "任务阶段的执行要求与边界。",
      minRows: 5,
      maxRows: 12
    },
    {
      key: "companyInstructions",
      label: "公司助手追加指令",
      required: false,
      note: "可选追加：非空时拼在公司任务阶段之后，留空即不追加。",
      placeholder: "需要追加到公司助手指令末尾的内容，可留空。",
      minRows: 3,
      maxRows: 10
    }
  ],
  shared: [
    {
      key: "sharedInstructions",
      label: "共用基础指令",
      required: true,
      note: "共用段落：办公与公司两个合成都会在末尾追加此段。",
      placeholder: "两个助手共用的规则与约束。",
      minRows: 5,
      maxRows: 12
    },
    {
      key: "replyInstructions",
      label: "回复风格追加指令",
      required: false,
      note: "可选追加：办公与公司合成都会追加此回复风格段，留空即不追加。",
      placeholder: "回复语气、结构、语言与拒绝边界，可留空。",
      minRows: 3,
      maxRows: 10
    }
  ],
  advanced: [
    {
      key: "checkpointInstructions",
      label: "检查点阶段指令",
      required: true,
      note: "公司检查点运行使用的指令段落，原样下发，不参与办公/公司拼接。",
      placeholder: "检查点阶段的执行要求。",
      minRows: 4,
      maxRows: 12
    },
    {
      key: "finalizationInstructions",
      label: "收尾阶段指令",
      required: true,
      note: "运行收尾阶段使用的指令段落，普通与检查点运行均携带，原样下发。",
      placeholder: "收尾阶段的输出要求。",
      minRows: 4,
      maxRows: 12
    },
    {
      key: "compactionInstructions",
      label: "压缩阶段指令",
      required: true,
      note: "会话历史压缩（精简）使用的指令段落，原样下发。",
      placeholder: "历史压缩时的保留与改写规则。",
      minRows: 4,
      maxRows: 12
    }
  ]
};

/** Catalog served by GET /config is the single field-description source. */
const fieldCatalog = computed(() => new Map((view.value?.fields ?? []).map((field) => [field.name, field])));

/** Editable fields in snapshot order: not secret, not deployment-readonly. */
const snapshotFieldNames = computed(() =>
  (view.value?.fields ?? [])
    .filter((field) => !field.secret && field.effect !== "readonly")
    .map((field) => field.name)
);

const deploymentFields = computed(() =>
  (view.value?.fields ?? []).filter((field) => field.effect === "readonly")
);

const observabilityStatus = computed(() => view.value?.runtime?.observability ?? null);

const formatter = new Intl.DateTimeFormat("zh-CN", {
  year: "numeric", month: "2-digit", day: "2-digit",
  hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false
});

const emit = defineEmits(["dirty-change"]);

const view = ref(null);
const draft = reactive(emptyDraft());
const langfuseClears = reactive(new Set());
const loading = ref(false);
const saving = ref(false);
const testing = ref(false);
const restoringRevision = ref(null);
const historyLoading = ref(false);
const configError = ref("");
const historyError = ref("");
const saveError = ref("");
const saveIssues = ref([]);
const restoreError = ref("");
const restoreIssues = ref([]);
const validationMessage = ref("");
const configConflict = ref(false);
const saveNotice = ref("");
const testOutcome = ref(null);
const historyRecords = ref([]);
const formRef = ref();
let historyRequest = 0;
const openInstructionGroups = ref(["office", "company"]);

const sessionHealth = ref();
const sessionHealthLoading = ref(false);
const sessionHealthError = ref("");

const busy = computed(
  () => loading.value || saving.value || testing.value || restoringRevision.value !== null
);

const apiKeyConfigured = computed(() => view.value?.secrets.modelApiKey?.configured === true);
const apiKeyActiveConfigured = computed(
  () => view.value?.secrets.modelApiKey?.activeConfigured === true
);

const dirtyFields = computed(() => {
  const current = view.value;
  if (!current) return [];
  const saved = current.saved;
  const fields = [];
  if (draft.modelBaseUrl !== saved.modelBaseUrl) fields.push("modelBaseUrl");
  if (draft.modelId !== saved.modelId) fields.push("modelId");
  if (draft.contextWindow !== saved.contextWindow) fields.push("contextWindow");
  if (draft.maxOutputTokens !== saved.maxOutputTokens) fields.push("maxOutputTokens");
  if (draft.reasoning !== saved.reasoning) fields.push("reasoning");
  if (draft.thinkingLevel !== saved.thinkingLevel) fields.push("thinkingLevel");
  if (draft.supportsDeveloperRole !== saved.supportsDeveloperRole) fields.push("supportsDeveloperRole");
  if (draft.supportsReasoningEffort !== saved.supportsReasoningEffort) fields.push("supportsReasoningEffort");
  if (draft.supportsUsageInStreaming !== saved.supportsUsageInStreaming) fields.push("supportsUsageInStreaming");
  if (draft.maxTokensField !== saved.maxTokensField) fields.push("maxTokensField");
  if (draft.maxAgentTurns !== saved.maxAgentTurns) fields.push("maxAgentTurns");
  if (draft.maxToolCalls !== saved.maxToolCalls) fields.push("maxToolCalls");
  for (const field of INSTRUCTION_ALL_FIELDS) {
    if (draft[field] !== (saved[field] ?? "")) fields.push(field);
  }
  if (draft.runTimeoutMs !== saved.runTimeoutMs) fields.push("runTimeoutMs");
  if (draft.heartbeatMs !== saved.heartbeatMs) fields.push("heartbeatMs");
  if (draft.scanIntervalMs !== saved.scanIntervalMs) fields.push("scanIntervalMs");
  if (draft.langfuseEnabled !== (saved.langfuseEnabled === true)) fields.push("langfuseEnabled");
  if (draft.langfuseBaseUrl !== (saved.langfuseBaseUrl ?? "")) fields.push("langfuseBaseUrl");
  if (draft.modelApiKey.length > 0) fields.push("modelApiKey");
  if (draft.langfusePublicKey.length > 0 || langfuseClears.has("langfusePublicKey")) fields.push("langfusePublicKey");
  if (draft.langfuseSecretKey.length > 0 || langfuseClears.has("langfuseSecretKey")) fields.push("langfuseSecretKey");
  return fields;
});

const dirtyCount = computed(() => dirtyFields.value.length);
const dirty = computed(() => dirtyCount.value > 0);

const snapshotRows = computed(() => {
  const current = view.value;
  if (!current) return [];
  return snapshotFieldNames.value.map((field) => {
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
const stateChangedFields = computed(() => {
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

const STATIC_RULES = {
  modelBaseUrl: [
    { required: true, message: "请填写模型接口根地址" },
    {
      validator: (rule, value, callback) => {
        const text = typeof value === "string" ? value : "";
        callback(modelUrlError(text) ? new Error(modelUrlError(text)) : undefined);
      }
    }
  ],
  modelId: [
    { required: true, message: "请填写模型 ID" },
    { max: 200, message: "模型 ID 不能超过 200 个字符" },
    {
      validator: (rule, value, callback) => {
        const text = typeof value === "string" ? value : "";
        callback(text.trim().length > 0 ? undefined : new Error("请填写模型 ID"));
      }
    }
  ],
  modelApiKey: [
    { max: 4096, message: "模型 API Key 不能超过 4096 个字符" },
    {
      validator: (rule, value, callback) => {
        const text = typeof value === "string" ? value : "";
        if (text.length === 0) {
          callback(apiKeyConfigured.value ? undefined : new Error("尚未配置模型密钥，请填写 API Key"));
          return;
        }
        if (text !== text.trim() || text.trim().length === 0) {
          callback(new Error("密钥首尾不能有空白字符"));
          return;
        }
        callback();
      }
    }
  ],
  contextWindow: [
    {
      required: true, type: "number", min: 8192, max: 2000000,
      message: "上下文窗口需在 8,192–2,000,000 之间"
    }
  ],
  maxOutputTokens: [
    {
      required: true, type: "number", min: 256, max: 128000,
      message: "最大输出需在 256–128,000 之间"
    },
    {
      validator: (rule, value, callback) => {
        if (
          typeof value === "number" &&
          typeof draft.contextWindow === "number" &&
          value >= draft.contextWindow
        ) {
          callback(new Error("最大输出必须小于上下文窗口"));
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
      required: true, type: "number", min: 1000, max: 600000,
      message: "执行超时需在 1,000–600,000 毫秒之间"
    }
  ],
  thinkingLevel: [
    { required: true, message: "请选择思考级别" },
    {
      validator: (rule, value, callback) => {
        const text = typeof value === "string" ? value : "";
        if (!THINKING_OPTIONS.some((item) => item.value === text)) {
          callback(new Error("请选择有效的思考级别"));
          return;
        }
        if (!draft.reasoning && text !== "off") {
          callback(new Error("关闭推理时，思考级别必须为 off"));
          return;
        }
        callback();
      }
    }
  ],
  maxTokensField: [
    { required: true, message: "请选择输出上限字段" },
    {
      validator: (rule, value, callback) => {
        const text = typeof value === "string" ? value : "";
        callback(
          MAX_TOKENS_OPTIONS.some((item) => item.value === text)
            ? undefined
            : new Error("请选择有效的输出上限字段")
        );
      }
    }
  ],
  heartbeatMs: [
    {
      required: true, type: "number", min: 0, max: 60000,
      message: "心跳间隔需在 0–60,000 毫秒之间（0 表示关闭）"
    }
  ],
  scanIntervalMs: [
    {
      required: true, type: "number", min: 1000, max: 60000,
      message: "任务扫描间隔需在 1,000–60,000 毫秒之间"
    }
  ],
  langfuseBaseUrl: [
    {
      validator: (rule, value, callback) => {
        const text = typeof value === "string" ? value.trim() : "";
        if (!draft.langfuseEnabled) {
          callback();
          return;
        }
        if (text.length === 0) {
          callback(new Error("启用观测前请填写观测服务地址"));
          return;
        }
        try {
          const url = new URL(text);
          callback(url.protocol === "http:" || url.protocol === "https:" ? undefined : new Error("观测地址必须是 http(s) URL"));
        } catch {
          callback(new Error("请输入有效的观测服务地址"));
        }
      }
    }
  ],
  langfusePublicKey: [
    {
      validator: (rule, value, callback) => {
        const text = typeof value === "string" ? value : "";
        if (!draft.langfuseEnabled) {
          callback();
          return;
        }
        const configured = langfuseSecretConfigured("langfusePublicKey");
        if (text.length === 0 && !configured) {
          callback(new Error("启用观测前请填写观测公钥"));
          return;
        }
        if (text !== text.trim()) {
          callback(new Error("公钥首尾不能有空白字符"));
          return;
        }
        callback();
      }
    }
  ],
  langfuseSecretKey: [
    {
      validator: (rule, value, callback) => {
        const text = typeof value === "string" ? value : "";
        if (!draft.langfuseEnabled) {
          callback();
          return;
        }
        const configured = langfuseSecretConfigured("langfuseSecretKey");
        if (text.length === 0 && !configured) {
          callback(new Error("启用观测前请填写观测密钥"));
          return;
        }
        if (text !== text.trim()) {
          callback(new Error("密钥首尾不能有空白字符"));
          return;
        }
        callback();
      }
    }
  ]
};

const instructionRules = computed(() => {
  const entries = {};
  for (const group of INSTRUCTION_GROUPS) {
    for (const field of INSTRUCTION_GROUP_FIELDS[group.name]) {
      const max = instructionMaxLength(field.key);
      const fieldRules = [];
      if (field.required) fieldRules.push({ required: true, message: `请填写${field.label}` });
      fieldRules.push({ max, message: `${field.label}不能超过 ${max.toLocaleString("zh-CN")} 个字符` });
      fieldRules.push({
        validator: (rule, value, callback) => {
          const text = typeof value === "string" ? value : "";
          if (field.required && text.trim().length === 0) {
            callback(new Error(`请填写${field.label}（不能为空白）`));
            return;
          }
          callback();
        }
      });
      entries[field.key] = fieldRules;
    }
  }
  return entries;
});

const rules = computed(() => ({ ...STATIC_RULES, ...instructionRules.value }));

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

watch(dirtyCount, (count) => emit("dirty-change", count));

onMounted(() => {
  window.addEventListener("beforeunload", handleBeforeUnload);
  void loadConfig(true);
  void loadHistory();
});

onBeforeUnmount(() => {
  window.removeEventListener("beforeunload", handleBeforeUnload);
});

function emptyDraft() {
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
    officeBaseInstructions: "",
    companyBaseInstructions: "",
    sharedInstructions: "",
    companyTaskInstructions: "",
    checkpointInstructions: "",
    finalizationInstructions: "",
    compactionInstructions: "",
    runTimeoutMs: undefined,
    heartbeatMs: undefined,
    scanIntervalMs: undefined,
    langfuseEnabled: false,
    langfuseBaseUrl: "",
    langfusePublicKey: "",
    langfuseSecretKey: ""
  };
}

function settingsToDraft(settings) {
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
    officeInstructions: settings.officeInstructions ?? "",
    companyInstructions: settings.companyInstructions ?? "",
    replyInstructions: settings.replyInstructions ?? "",
    officeBaseInstructions: settings.officeBaseInstructions ?? "",
    companyBaseInstructions: settings.companyBaseInstructions ?? "",
    sharedInstructions: settings.sharedInstructions ?? "",
    companyTaskInstructions: settings.companyTaskInstructions ?? "",
    checkpointInstructions: settings.checkpointInstructions ?? "",
    finalizationInstructions: settings.finalizationInstructions ?? "",
    compactionInstructions: settings.compactionInstructions ?? "",
    runTimeoutMs: settings.runTimeoutMs,
    heartbeatMs: settings.heartbeatMs,
    scanIntervalMs: settings.scanIntervalMs,
    langfuseEnabled: settings.langfuseEnabled === true,
    langfuseBaseUrl: settings.langfuseBaseUrl ?? "",
    langfusePublicKey: "",
    langfuseSecretKey: ""
  };
}

function resetDraftFromSaved() {
  const current = view.value;
  if (!current) return;
  Object.assign(draft, settingsToDraft(current.saved));
  langfuseClears.clear();
  testOutcome.value = null;
  validationMessage.value = "";
  void nextTick(() => formRef.value?.clearValidate());
}

function applyView(next, resetDraft) {
  view.value = next;
  configError.value = "";
  configConflict.value = false;
  if (resetDraft) resetDraftFromSaved();
}

async function loadConfig(resetDraft) {
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
  void ElMessageBox.confirm(
    "刷新会从服务端重新读取配置和审计记录，本地草稿与未保存的密钥输入将丢失。",
    "放弃未保存修改并刷新？",
    { confirmButtonText: "刷新", cancelButtonText: "取消", type: "warning" }
  ).then(() => reloadAll(true)).catch(() => undefined);
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
  ElMessage.info("已载入最新修订，本地草稿已保留；请核对后重新保存");
  void loadHistory();
}

function handleBeforeUnload(event) {
  if (!dirty.value) return;
  event.preventDefault();
  event.returnValue = "";
}

function fieldLabel(field) {
  return fieldCatalog.value.get(field)?.label ?? FIELD_LABELS[field] ?? field;
}

function fieldNote(field) {
  const meta = fieldCatalog.value.get(field);
  if (!meta) return "";
  const parts = [];
  if (meta.note) parts.push(meta.note);
  if (meta.unit && meta.kind === "int") parts.push(`单位：${meta.unit}`);
  if (meta.defaultValue !== "" && meta.defaultValue !== null && meta.defaultValue !== undefined) {
    parts.push(`默认 ${String(meta.defaultValue)}`);
  }
  return parts.join("；");
}

function langfuseSecretConfigured(field) {
  return view.value?.secrets[field]?.configured === true;
}

function queueLangfuseClear(field) {
  langfuseClears.add(field);
  draft[field] = "";
}

function cancelLangfuseClear(field) {
  langfuseClears.delete(field);
}

function langfuseClearPending(field) {
  return langfuseClears.has(field);
}

function instructionMaxLength(field) {
  const max = fieldCatalog.value.get(field)?.max;
  return typeof max === "number" && max > 0 ? max : INSTRUCTION_MAX_FALLBACK;
}

function instructionGroupDirtyFields(group) {
  return INSTRUCTION_GROUP_FIELDS[group].filter((field) => dirtyFields.value.includes(field.key));
}

function fieldValue(settings, field) {
  return settings[field];
}

function formatFieldValue(field, value) {
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

function formatTime(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : formatter.format(date);
}

function issueLabels(issues) {
  return issues.map((field) => fieldLabel(field)).join("、");
}

function modelUrlError(value) {
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

function collectChanges(current) {
  const saved = current.saved;
  const payload = {};
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
  for (const field of INSTRUCTION_ALL_FIELDS) {
    if (draft[field] !== (saved[field] ?? "")) payload[field] = draft[field];
  }
  if (draft.runTimeoutMs !== undefined && draft.runTimeoutMs !== saved.runTimeoutMs) {
    payload.runTimeoutMs = draft.runTimeoutMs;
  }
  if (draft.heartbeatMs !== undefined && draft.heartbeatMs !== saved.heartbeatMs) {
    payload.heartbeatMs = draft.heartbeatMs;
  }
  if (draft.scanIntervalMs !== undefined && draft.scanIntervalMs !== saved.scanIntervalMs) {
    payload.scanIntervalMs = draft.scanIntervalMs;
  }
  if (draft.langfuseEnabled !== (saved.langfuseEnabled === true)) {
    payload.langfuseEnabled = draft.langfuseEnabled;
  }
  if (draft.langfuseBaseUrl !== (saved.langfuseBaseUrl ?? "")) {
    payload.langfuseBaseUrl = draft.langfuseBaseUrl;
  }
  if (draft.modelApiKey.length > 0) payload.modelApiKey = draft.modelApiKey;
  for (const field of ["langfusePublicKey", "langfuseSecretKey"]) {
    if (draft[field].length > 0) payload[field] = draft[field];
    else if (langfuseClears.has(field)) payload[field] = "";
  }
  return payload;
}

/** Shared per-field adoption wording used by both save and restore notices. */
function adoptionNotice(data) {
  let text =
    "模型、生成参数、业务指令、执行预算与会话心跳将在下一轮对话开始时读取；任务扫描间隔将在下一个扫描周期读取；执行中的轮次保持旧快照。";
  if (data.pendingRestart === true) {
    text += " 观测配置（Langfuse）已保存但尚未采用：需通过受监督重启由 sidecar 在启动时拉取后生效。";
  }
  return text;
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
        : `已保存修订 #${data.revision}。${adoptionNotice(data)}`;
    ElMessage.success("配置已保存");
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
    const valid = await formRef.value?.validateField(["modelBaseUrl", "modelId", "modelApiKey"]).catch(() => false);
    if (!valid) {
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
    const payload = {
      modelBaseUrl: draft.modelBaseUrl,
      modelId: draft.modelId
    };
    if (draft.modelApiKey.length > 0) payload.modelApiKey = draft.modelApiKey;
    const { data } = await adminApi.testModel(payload);
    testOutcome.value = {
      type: "success",
      message: `${data.message}（仅限连接测试；本测试不执行保存）`
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
  void ElMessageBox.confirm(
    "将丢弃本地草稿并恢复为最近一次保存的配置。",
    "放弃未保存修改？",
    { confirmButtonText: "放弃修改", cancelButtonText: "继续编辑", type: "warning" }
  ).then(() => {
    resetDraftFromSaved();
    saveError.value = "";
    saveIssues.value = [];
    saveNotice.value = "";
    ElMessage.info("已放弃未保存修改");
  }).catch(() => undefined);
}

function requestRestore(row) {
  const current = view.value;
  if (!current || busy.value || configConflict.value || row.revision === current.revision) return;
  const target = row.revision;
  const currentRevision = current.revision;
  void ElMessageBox.confirm(
    `将以修订 #${target} 的配置内容新建一个配置版本（当前修订 #${currentRevision}）。恢复成功后未保存的修改会被丢弃，审计历史不会删除。`,
    `恢复到修订 #${target}`,
    { confirmButtonText: "确认恢复", cancelButtonText: "取消", type: "warning" }
  ).then(() => restoreRevision(target)).catch(() => undefined);
}

async function restoreRevision(revision) {
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
    saveNotice.value = `已恢复到修订 #${revision}，并生成修订 #${data.revision}。${adoptionNotice(data)}`;
    ElMessage.success("配置已恢复");
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

async function loadSessionHealth() {
  sessionHealthLoading.value = true;
  sessionHealthError.value = "";
  try {
    sessionHealth.value = (await adminApi.piSessionHealth()).data;
  } catch (error) {
    sessionHealth.value = undefined;
    sessionHealthError.value = errorOf(error, "无法读取 MySQL 会话状态").message;
  } finally {
    sessionHealthLoading.value = false;
  }
}

function clearSaveNotice() {
  saveNotice.value = "";
}
</script>

<style scoped>
.config-page {
  min-height: 100%;
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
  color: var(--text-strong);
}

.page-subtitle {
  max-width: 720px;
  margin: 4px 0 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-muted);
}

.page-status {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
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
  background: var(--surface-card);
  border: 1px solid var(--border-subtle);
  border-radius: 8px;
}

.overview-label {
  font-size: 12px;
  color: var(--text-muted);
}

.overview-value {
  margin-top: 4px;
  font-size: 20px;
  font-weight: 600;
  color: var(--text-strong);
  word-break: break-word;
}

.overview-sub {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-muted);
}

.section-card {
  margin-bottom: 16px;
  scroll-margin-top: 76px;
  border: 1px solid var(--border-subtle);
}

.section-anchor {
  scroll-margin-top: 76px;
}

.card-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.card-title {
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
  color: var(--text-muted);
}

.switch-line {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 32px;
  color: var(--text-body);
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
  color: var(--text-muted);
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
  border-bottom: 1px solid var(--border-subtle);
  word-break: break-word;
}

.snapshot-table th {
  font-size: 12px;
  font-weight: 500;
  color: var(--text-body);
  background: var(--surface-subtle);
}

.snapshot-field {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  color: var(--text-strong);
}

.snapshot-field code {
  font-size: 11px;
  color: var(--text-muted);
}

.deployment-value {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
  color: var(--text-strong);
}

.deployment-value code {
  font-size: 11px;
  color: var(--text-muted);
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
  border-bottom: 1px solid var(--border-subtle);
  word-break: break-word;
}

.audit-table th {
  font-size: 12px;
  font-weight: 500;
  color: var(--text-body);
  background: var(--surface-subtle);
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.no-wrap {
  white-space: nowrap;
}

.muted {
  font-size: 12px;
  color: var(--text-muted);
}

.save-bar {
  position: fixed;
  right: 0;
  bottom: 0;
  left: 216px;
  z-index: 20;
  padding: 10px 24px;
  background: var(--surface-card);
  border-top: 1px solid var(--border-subtle);
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
  color: var(--text-strong);
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
  .save-bar {
    left: 0;
    padding: 10px 16px;
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
}

.instruction-groups {
  margin-top: 4px;
}

.group-title {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.group-name {
  font-weight: 600;
  color: var(--text-strong);
}

.group-sub {
  font-size: 12px;
  color: var(--text-muted);
}

@media (max-width: 720px) {
  .group-sub {
    display: none;
  }
}
</style>
