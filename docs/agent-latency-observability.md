# 对话链路计时规范

## 范围与关联

只增加观测，不改变模型参数、意图路由、授权、目录缓存、检查点顺序或公开NDJSON协议。一次运行以服务端生成的 `runId` 关联，`conversationId` 对应Langfuse session，`turnId` 区分业务轮次。Java日志前缀固定为 `[agent-latency]`，Node根span metadata带相同runId、turnId。

Java使用 `System.nanoTime()`、Node使用单调时钟测持续时间。UTC时间仅用于跨进程定位；不得直接相减不同机器时钟并宣称网络耗时。阶段嵌套，不重复相加；并发／累计操作耗时不是互斥墙钟分段。

## 边界

| 记录 | 边界／解释 |
|---|---|
| java.accept | ConversationFacade.message进入到审计成功、准备排队；不含此前身份过滤器 |
| java.queue | 提交执行前到worker开始 |
| java.prepare | 完整Business Agent Definition准备，含下列子阶段 |
| java.regions / java.catalog / java.reference | 实际区域、授权设备范围、引用核验调用；不是localhost网络耗时 |
| java.engine.headers / java.engine.first-text | Node请求发出至响应头／首个非空正文delta；均包含调度等待，不是纯网络时间 |
| java.delivery | 收尾时汇总事件锁等待、历史事件读取、追加、emitter.send累计耗时／次数，及首正文send返回；send返回不证明小程序收到或渲染 |
| agent-run | 从Node运行入口起，覆盖会话打开与恢复，包含后续模型和工具 |
| open-session / recover-session / load-history / finish-session / append-message | Node关键持久化或历史阶段，成功失败均闭合 |
| model-turn | 每次模型实际调用；包含提供商排队、推理与传输，不能单独区分网络与提供商计算 |
| generation.completionStartTime | 第一个非空text_delta到达时间；不把HTTP头、reasoning或tool-call当可见文字 |
| model-compaction | 历史压缩中的额外模型调用，单列generation，不藏在业务模型耗时中 |

首正文仅表示模型正文或后端发送边界；模型正文可能被现有回复过滤器处理，不能冒充用户真正看到的首字。前端可见完成时间用真实小程序操作单独验证，说明采样精度，不与后端TTFT混用。

## 脱敏与开销

新增计时仅记录随机运行标识、固定阶段名、耗时、次数及成功／失败类别。禁止请求正文、手机号、真实设备ID、模型密钥、桥接Token、配置快照或异常原文进入新增计时字段。既有Langfuse业务内容保持原治理边界，不扩大采集范围。

不逐token／逐delta打印日志，不同步等待远程观测上传，不新增数据库表、后台队列或公开事件。无Langfuse时不创建span。Java仅在有限阶段结束及终态汇总输出；事件路径仅累计单调计时和计数。

SDK沿用锁定的Langfuse 5.11.1，不在埋点任务中升级依赖或扩大部署切换范围。计时依据[官方手工埋点](https://langfuse.com/docs/observability/sdk/instrumentation)与[trace结构规范](https://langfuse.com/docs/observability/best-practices)。

## 验证

已有协议与边界测试覆盖公开行为不变；实际小程序发送只读问候，核对同runId的Java计时、Langfuse阶段／generation、首正文时间与界面答复。失败阶段必须闭合，缺失字段记未观测，不以相减残差冒充具体依赖耗时。固定实例使用新冻结产物，禁止覆盖运行中的jar；保留原启动配置及产物用于回退，不修改生产Runtime。

## 深模块约束

计时属于conversation运行链路，不另建通用观测框架。唯一新增跨模块入口为包根 `conversation.AgentRunTiming`，只声明阶段测量、边界记录、投递汇总三种操作；日志与单调计时实现放 `conversation.internal.RunTimingRecorder`。agent只能引用包根，不能import conversation.internal；原有依赖方向不变。Node观测保持在既有observability模块，持久化实现不向业务模块泄漏。

Java日志与Langfuse目前通过runId关联，不宣称Java日志已经作为Langfuse子span上报。Java入口之前的认证过滤器、小程序网络接收与渲染仍未单独埋点。`firstTextSentMs=-1` 表示没有成功发送非空正文delta，不表示耗时为负数。

本轮实际部署、两次同请求分解、深模块测试与全套测试阻塞见[验证证据](../.scratch/agent-latency-audit-20261105/instrumentation-result.json)。
