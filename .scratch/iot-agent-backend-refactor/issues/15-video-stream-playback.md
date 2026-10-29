# 15: Video Live 与 Playback

**What to build:** 用户在当前授权范围内打开 Office/Company 视频直播或回放，Java 返回 Runtime 兼容的短时效入口，不持久化或缓存流地址。

**Blocked by:** 11: Company 多源设备目录与 Control Reference

**Status:** implemented-local (Runtime protocol adapter and route validation are complete; real video decode remains external acceptance)

- [x] Company monitor scope 使用当前 video-only authorized catalog 和 monitorAreaDeviceId。
- [x] Office live 使用 Runtime-derived hub/action binding；Company live 使用 monitor/down payload。
- [x] playback 使用 Runtime playback action、时区校验、开始/结束顺序和三天上限。
- [x] 返回 no-store、授权失败、来源错误、窗口错误、上游 busy/unavailable 的 Runtime 语义。
- [x] 任何旧 scope、旧 URL、客户端 hubDeviceId 或越权设备都不能复用。
- [x] 迁移 Runtime company-video-scope、video-playback、company-smart-devices fixture tests；不宣称真实视频解码通过。

## Comments

- Runtime 已记录客户上游 path、body、action id 和错误语义。
- 本票不做生产视频播放写入或真机解码验收。
