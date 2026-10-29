# Agent Engine bridge 探针（throwaway）

- 运行时复用 `../iot-agent-runtime/node_modules` 中真实的 `@earendil-works/pi-agent-core` / `@earendil-works/pi-ai`，模型配置由本机 `.env` 注入；不复制或重写这两个包。该探针只接 `echo_tool`，不代表业务 Tool Bridge 已完成。

## 运行

从 `iot-agent-backend` 根目录启动 Node sidecar：

```sh
set -a; . ./.env; set +a; node .scratch/iot-agent-backend-refactor/prototype/agent-engine-bridge/sidecar.mjs
```

另一个终端启动 Java probe：

```sh
mvn -q package -f .scratch/iot-agent-backend-refactor/prototype/agent-engine-bridge/pom.xml
/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home/bin/java \
  -jar .scratch/iot-agent-backend-refactor/prototype/agent-engine-bridge/target/agent-engine-bridge-probe-0.1.0.jar \
  --server.port=3200 --bridge.node-base-url=http://127.0.0.1:3211
```

执行带会话身份的普通 run：

```sh
curl -sS -N -X POST http://127.0.0.1:3200/prototype/bridge/run \
  -H 'Content-Type: application/json' \
  --data '{"runId":"probe-001","conversationId":"conversation-001","turnId":"turn-001","content":"hello bridge","toolBridgeUrl":"http://127.0.0.1:3200/prototype/bridge"}'
```

事件包含 `conversationId`、`turnId`、`pi.tool_execution_start`、Java 返回的 `pi.tool_execution_end`、`message.delta`、`message.completed` 和 `run.completed`。

## 恢复、超时和取消

断开 run 的 HTTP 响应后，运行继续；完成后通过同一 Java 入口重放：

```sh
curl -sS http://127.0.0.1:3200/prototype/bridge/runs/<runId>/events
```

超时场景给请求加 `"timeoutMs":1000`；取消场景给请求加 `"slow":true`，再调用：

```sh
curl -sS -X POST http://127.0.0.1:3200/prototype/bridge/cancel/<runId>
```

相同 `runId` 重复提交返回 409；不同 `conversationId` 使用独立 Pi Session 上下文。sidecar 停止并重新启动后，两个 healthz 均应恢复 200。探针使用本机 `.env` 的真实 Chat Completions 模型配置，Agent loop、工具 schema、工具执行、取消、超时和事件生命周期均使用真实 `pi-agent-core`；仅 `echo_tool` 是探针工具，不代表业务 Tool Bridge 已完成。
