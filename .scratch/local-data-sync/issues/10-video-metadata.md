# 10: 视频目录/安全关联候选/回放窗口准备本地化与专门开流出口

**What to build:** 本地化视频授权目录、安全关联候选和回放窗口准备，准备只生成卡片不取流地址；使用独立视频授权，不以安全授权代替。07提供关联所需最小视频范围，本票扩展完整目录与准备链路。真正openVideoStream/openVideoPlayback保留专门外部动作及既有授权/绑定安全复核，短期地址不进入普通读缓存，旧URL不能冒充可播放。

**Blocked by:** [03-office-candidates.md](03-office-candidates.md)（候选发现本地化）、[07-safety-ledger.md](07-safety-ledger.md)（本地事件与关联候选基础）；未完成不得开工。

**Status:** ready-for-agent
**Type:** implementation
**契约参照:** [../spec.md](../spec.md) · [../acceptance.md](../acceptance.md)

## 范围
- 本地数据：完整视频目录（Company monitor 区域树与设备、Office 视频设备）随目录批次同步，独立于全目录与其他种类授权；同步范围/进度挂接 02 骨架，与 07 的最小视频授权元数据衔接不重复建模。
- 关联候选：本地事件详情（07）× 当前安全设备位置 × 本地视频授权目录，本端生成候选——同地点同楼层优先、只选有 hub 绑定的 Company 摄像头、候选窗口为原事件 ±15 分钟；"按当前安装位置匹配，不证明事件时位置或画面覆盖"的披露保持；历史候选引用使用时复核当前独立视频授权。
- 窗口与准备：prepareVideoPlayback 校验本地独立视频授权与设备、生成准备卡片（不取 URL、不开流）；review_safety_event 与 prepare_video_playback 工具同口径；AgentReadIotAdapter 对视频 scope/preparation 的透传分支改本地。
- 开流例外：openVideoStream/openVideoPlayback走既有专门上游动作，保留当前必须的授权/绑定复核及错误契约；普通准备不借此回源。开流内部必要即时校验属于该显式例外，不能为了零回源数字删除安全步骤。
- URL 生命周期：短期地址仅本次响应有效，不写普通读缓存、不回放旧地址；"缓存旧 URL 不算可播放"。
- 窗口语义：保持原有实际业务窗口与上游限制，不借读架构调整事件邻域或未来时间裁剪；若发现现有入口口径不一致，按已确认Runtime业务基线记录并定点处理，不凭注释选定新规则。
- 覆盖语义：本地视频目录未覆盖时返回 preparing/UNAVAILABLE 并登记需求，不误报 EMPTY/NOT_FOUND；EMPTY 仅用于同步已证实无数据。

## 非目标
- 不做安全事件采集（07）或dashboard聚合（11），不改CORS/鉴权配置；本票失去用途的回源准备代码随切换删除，全局出口约束归13，真实播放按14允许路径抽样。

## 行为验收
- [ ] 隔离程序只阻断普通客户数据读取出口：已覆盖的视频目录/关联候选/窗口准备网络计数0；允许的开流及必要安全复核经专门出口，其调用单独计数，不与普通查询混算。
- [ ] 准备动作不产生任何视频 URL；开流成功返回真实短期地址；重复请求不回放旧地址、不把缓存地址当可播。
- [ ] 独立视频授权：仅持安全授权不持视频授权的主体不获得候选与准备；撤权后候选与准备失败关闭；历史关联候选复核当前权限。
- [ ] 候选窗口、位置匹配优先级、覆盖不确定性披露与既有 SafetyCameraObservationContractTest 契约一致。
- [ ] 207 忙碌、401/403、空录像错误语义与 CORS 行为不回退。
- [ ] 本地目录未覆盖→preparing/UNAVAILABLE 如实返回，不误报空目录。
- [ ] 视频目录、关联候选、准备卡片的开发者工具 UI 截图验证；允许的真实播放有真实画面证据并标注外部出口（不注入消息/卡片）。

## 证据与既有公开接口
- 公开符号：IotAdapter.loadAuthorizedVideoScope、IotAdapter.prepareVideoPlayback、IotAdapter.openVideoStream、IotAdapter.openVideoPlayback、VideoPlaybackPreparation、VideoPlaybackWindow、VideoStream、VideoStreamBusyException。
- 既有测试接口：VideoAdapterContractTest、SafetyCameraObservationContractTest、SafetyReviewToolContractTest、VideoStreamCorsTest；测试经包根入口断言，不从 internal 导入，不开新测试框架。
