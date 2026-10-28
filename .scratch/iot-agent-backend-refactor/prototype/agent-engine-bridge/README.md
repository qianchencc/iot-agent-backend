# Agent Engine bridge 探针（throwaway）

验证 Java Spring MVC → Node `pi-agent-core` → Java Tool Bridge → NDJSON 的组合架构。该目录不属于生产源码，不接客户网关，不控制真实设备。

## 运行

从 `iot-agent-backend` 根目录启动 Node sidecar：

```sh
node .scratch/iot-agent-backend-refactor/prototype/agent-engine-bridge/sidecar.mjs
```

另一个终端启动 Java probe：

```sh
mvn -q package -f .scratch/iot-agent-backend-refactor/prototype/agent-engine-bridge/pom.xml
/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home/bin/java \
  -jar .scratch/iot-agent-backend-refactor/prototype/agent-engine-bridge/target/agent-engine-bridge-probe-0.1.0.jar \
  --server.port=3200 --bridge.node-base-url=http://127.0.0.1:3211
```

执行普通 run：

```sh
curl -sS -N -X POST http://127.0.0.1:3200/prototype/bridge/run \
  -H 'Content-Type: application/json' \
  --data '{"runId":"probe-001","content":"hello bridge","toolBridgeUrl":"http://127.0.0.1:3200/prototype/bridge"}'
```

事件应包含 `pi.tool_execution_start`、Java 返回的 `pi.tool_execution_end`、`message.delta`、`message.completed` 和 `run.completed`。

取消场景将请求体加上 `"slow":true`，再调用：

```sh
curl -sS -X POST http://127.0.0.1:3200/prototype/bridge/cancel/<runId>
```

重启 sidecar 后，两个 healthz 均应恢复 200。探针用 faux model 产生确定性模型响应，但 Agent loop、工具 schema、工具执行、取消和事件生命周期均使用真实 `pi-agent-core`。
