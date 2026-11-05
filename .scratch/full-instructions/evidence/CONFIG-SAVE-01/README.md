# CONFIG-SAVE-01：完整业务指令直接保存

结果：**已验收（真实 Web 管理控制台）**。目标为 `http://127.0.0.1:5185/` 的业务指令编辑与保存，不是小程序设备操作或模型业务案例。

## 真实操作证据

1. 实际 Chrome 登录固定后端控制台，展开四组指令。10个文本框均有真实后端绑定值，7段基础文本非空；原3项追加内容与升级前逐字一致。
2. 清空办公基础指令，离开输入框并尝试保存，页面提示“请填写办公助手基础指令”；重新读取后台，原内容与保存状态均未变化。
3. 仅在办公基础指令末尾增加一个换行，使用原有“保存到下一轮”。实际 PATCH 为200，差异字段只有 `officeBaseInstructions`。
4. 刷新整个页面，文本框、后端 saved/active 均精确保留该换行；既有 `GET /api/admin/prompts` 的实际办公组装文本也采用了保存值，其他9项指令未变。此接口只是既有实际输出读取，没有增加预览界面或预览流程。
5. 再用普通“保存到下一轮”恢复原文，未使用历史恢复。刷新后10项 saved/active 与初始状态完全一致，草稿无未保存内容。
6. 320px 下文档宽度仍为320px，10项输入框横向边界均在视口内，字体16px。运行阶段组可通过键盘 Enter 展开，键盘焦点有2px轮廓。截图在展开动画结束后采集。

[脱敏结果](result.json) · [桌面办公段](desktop-office.webp) · [桌面运行阶段](desktop-phases.webp) · [320px运行阶段视口](mobile-phases.webp)

## 工程验证，不替代上面的前端验收

- 聚焦检查：`mvn -q -Dtest=AgentInstructionsTest,AdminInstructionConfigurationContractTest,AdminPromptsReadHttpTest,AgentInstructionRunSnapshotContractTest,ArchitectureTest test`，12项通过。
- 隔离本次范围的 `mvn -q clean package`：608项，0失败、0错误、25跳过；跳过项不算通过。
- `web/admin` 的 `npm run build` 通过；已有大于500kB的包体提示仍在，本次没有另做拆包。
- 新轮快照合同测试：准备好的旧轮指令不变，后续 prepare 读取新配置。没有在用户已有业务对话里创建测试轮次。
- 对实际发布包运行隔离探针：完整配置视图、加密保存、active 组装、非法值原子拒绝及重开读取均通过；仅使用临时文件与非生产样例配置，不接数据库、模型或物联上游。

## 部署包边界与排障

源码检查基线 `6bff4b9`，功能审查快照 `e2fe9fb888450aa9c84c63f689ddce8d7388ca38`。本次运行包保留原有资源和无关条目，更新本次类及必须的配置元数据依赖共23个类；本次Java切换当时，Node与控制台进程保持不变。

初次窄范围打包发现实际旧运行包缺少已提交源码中的配置元数据类型／视图接口，分别复现 `AdminSettingField` 缺失与 `ConfigurationView.fields()` ABI 不一致。错误包均先回退；随后用实际包的加载、视图和保存探针形成红绿反馈，补齐必要类型与接口，再完成真实控制台保存验收。没有通过替换整份未完成工作区来掩盖差异，也没有修改原 Runtime。临时录制已停止，临时探针及错误包不作为交付内容保留。

验收后的最终复核发现另一部署已将固定Java／Node切换至 `var/agent-latency-20261105-170113/`，并更新了Node观测相关环境项。本线程保留该部署，未用旧启动快照覆盖它；重新通过真实Chrome登录读取，10项 saved/active 与原始值仍完全一致、编辑区无未保存内容，Java与带认证的Node健康读取均为200。进程号和路径仅作为各次观察的时间截面，不宣称后续部署没有变化。

## Standards

固定功能快照没有阻塞性的标准或安全问题。审查指出合并长度错误归属基础字段、组装长度与组装顺序需同步维护等低优先级可维护性判断；本次不为此扩展新的错误协议或指令注册框架。秘密配置、鉴权和CSRF边界未改动。

## Spec

7段完整文本、3项追加、旧数据缺省补齐、普通保存和后续运行消费均已覆盖，没有新增指令版本／历史恢复／预览／发布功能。审查提示历史追加字段若曾被非法 API 写入非字符串，将被新文本校验明确拒绝；实际原3项均已核对为合法字符串，不增加静默忽略非法值的兜底。

## Interface review

| Field | Value |
| --- | --- |
| Target | `6bff4b9..e2fe9fb` 中本次指令编辑区 |
| Base ref | `6bff4b9` |
| Head ref | `e2fe9fb` |
| Commits | 1个固定功能审查快照 |
| Files in scope | `HomeView.vue`、`InstructionSectionField.vue` |
| Excluded | Java与文档由通用代码审查覆盖；未扩展审计、名单、既有历史、全局主题或其它线程改动 |
| Surfaces expanded | 1个直接消费者：配置首页；桌面与320px，同一保存／错误／刷新流程 |

| Domain | Evidence inspected | Result |
| --- | --- | --- |
| Accessibility | 真实名称／标签、错误提示、原生文本框、Enter展开与2px键盘焦点 | 已检查；屏幕阅读器和自动审计未验证 |
| Layout | 四组折叠结构、全部10项、320px水平边界与可滚动内容 | 无阻塞问题；RTL和200%浏览器缩放未验证 |
| Writing | 保存／追加／基础／阶段说明与字段错误，对照既有中文配置用语 | 无阻塞问题 |
| Typography | 实际完整文本、原生滚动、320px输入16px／24px行高 | 无阻塞问题 |
| Colors | 实测浅色文本／白底对比：输入及标签10.29、计数7.58、必填提示6.47 | 均超过普通文字4.5要求；深色主题未验证 |
| UI | 复用既有Element Plus控件，检查展开、空值错误、保存和无改动状态 | 无阻塞问题；未做动画慢放或新动效设计 |

No actionable interface findings in this change within the inspected scope.

**Approve（限上述实际检查范围）**。

## 明确未做

没有调用模型进行真实业务对话，没有下发设备命令、创建／确认任务或发送生产通知；没有更改模型参数、密钥、工具契约或程序级权限／确认约束。不能据此声称上述设备或模型业务已经验收。

[实现票据](../../issues/01-complete-instruction-settings.md)
