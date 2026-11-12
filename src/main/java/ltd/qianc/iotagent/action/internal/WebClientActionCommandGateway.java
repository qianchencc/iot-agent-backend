package ltd.qianc.iotagent.action.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import ltd.qianc.iotagent.action.ActionCommandGateway;
import ltd.qianc.iotagent.action.ActionDispatchException;
import ltd.qianc.iotagent.action.ActionDispatchResult;
import ltd.qianc.iotagent.action.ActionReceiptOutcomeEnum;
import ltd.qianc.iotagent.action.ActionDraftStatusEnum;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.DeviceStateReadResult;
import ltd.qianc.iotagent.iot.DeviceCommands;
import ltd.qianc.iotagent.iot.GatewayAuthorizationException;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSourceEnum;
import ltd.qianc.iotagent.iot.RequestIdentity;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Runtime-derived customer control adapter. It is deliberately below the
 * ActionDraft confirmation seam: callers cannot reach it before CAS and scope
 * revalidation have completed.
 */
public final class WebClientActionCommandGateway implements ActionCommandGateway {
    /** Conservative migration cap for the Runtime's independent-group workers (originally 3). */
    private static final int DISPATCH_CONCURRENCY = 6;

    private final ObjectMapper mapper;
    private final WebClient client;
    private final IotAdapter iot;
    private final Duration timeout;
    private final String lightingPath;
    private final String accessPath;
    private final String acPath;
    private final Duration readbackWait;

    public WebClientActionCommandGateway(ObjectMapper mapper, WebClient client, IotAdapter iot,
                                         Duration timeout, String lightingPath, String accessPath, String acPath) {
        this(mapper, client, iot, timeout, lightingPath, accessPath, acPath, Duration.ofSeconds(6));
    }

    public WebClientActionCommandGateway(ObjectMapper mapper, WebClient client, IotAdapter iot,
                                         Duration timeout, String lightingPath, String accessPath, String acPath,
                                         Duration readbackWait) {
        this.mapper = mapper;
        this.client = client;
        this.iot = iot;
        this.timeout = timeout;
        this.lightingPath = lightingPath;
        this.accessPath = accessPath;
        this.acPath = acPath;
        this.readbackWait = readbackWait == null || readbackWait.isNegative() ? Duration.ZERO : readbackWait;
    }

    @Override
    public ActionDispatchResult dispatch(RequestIdentity identity, AuthorizedDeviceScope scope, String selectedActionsJson) {
        return dispatch(identity, scope, selectedActionsJson, Map.of(), true);
    }

    @Override
    public ActionDispatchResult dispatch(RequestIdentity identity, AuthorizedDeviceScope scope,
                                         String selectedActionsJson, Map<String, Object> observedStates,
                                         boolean verifyReadback) {
        ArrayNode actions = readActions(selectedActionsJson);
        validateActions(scope, actions);
        // Runtime executeDevices shape: actions are grouped by hub (falling back
        // to the device id) so one physical chain keeps its parameter order,
        // while independent groups are dispatched by bounded concurrent workers.
        // A typed failure stops workers from claiming further groups; in-flight
        // calls are awaited before the failure is rethrown.
        List<List<Integer>> groups = deviceGroups(scope, actions);
        ObjectNode[] receiptsByAction = new ObjectNode[actions.size()];
        AtomicBoolean dispatchStarted = new AtomicBoolean();
        AtomicInteger nextGroup = new AtomicInteger();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        int workerCount = Math.min(DISPATCH_CONCURRENCY, groups.size());
        AtomicBoolean draining = new AtomicBoolean();
        Thread caller = Thread.currentThread();
        List<Runnable> workers = new ArrayList<>(workerCount);
        for (int index = 0; index < workerCount; index++) {
            workers.add(() -> {
                try {
                    dispatchGroups(identity, scope, actions, observedStates, groups, receiptsByAction,
                            dispatchStarted, draining, caller, nextGroup, failure);
                } catch (RuntimeException error) {
                    failure.compareAndSet(null, error);
                }
            });
        }
        boolean interrupted = runWorkers(workers, draining);
        if (interrupted) Thread.currentThread().interrupt();
        RuntimeException dispatchFailure = failure.get();
        if (dispatchFailure != null) {
            // Authorization can fail on one group after another group's write
            // was accepted. Never let that partial batch roll back to a retryable draft.
            for (ObjectNode receipt : receiptsByAction) {
                if (receipt == null) continue;
                String outcome = receipt.path("outcome").asText();
                if (ActionReceiptOutcomeEnum.ACCEPTED.name().equals(outcome)
                        || ActionReceiptOutcomeEnum.PARTIALLY_ACCEPTED.name().equals(outcome)
                        || ActionReceiptOutcomeEnum.UNKNOWN.name().equals(outcome)) {
                    throw new ActionDispatchException("部分设备指令已下发，批次未能完整完成", dispatchFailure, true);
                }
            }
            throw dispatchFailure;
        }
        if (interrupted) throw new ActionDispatchException("上游控制请求中断", dispatchStarted.get());
        ArrayNode receipts = mapper.createArrayNode();
        List<JsonNode> accepted = new ArrayList<>();
        int rejected = 0, partial = 0, unknown = 0, satisfied = 0;
        for (int index = 0; index < actions.size(); index++) {
            ObjectNode receipt = receiptsByAction[index];
            if (receipt == null) throw new ActionDispatchException("设备指令未取得完整回执", dispatchStarted.get());
            receipts.add(receipt);
            String outcome = receipt.path("outcome").asText("");
            if (ActionReceiptOutcomeEnum.ACCEPTED.name().equals(outcome)) accepted.add(actions.get(index));
            else if (ActionReceiptOutcomeEnum.ALREADY_SATISFIED.name().equals(outcome)) satisfied++;
            else if (ActionReceiptOutcomeEnum.REJECTED.name().equals(outcome)
                    || ActionReceiptOutcomeEnum.NOT_DISPATCHED.name().equals(outcome)) rejected++;
            else if (ActionReceiptOutcomeEnum.PARTIALLY_ACCEPTED.name().equals(outcome)) partial++;
            else unknown++;
        }
        // Even when no pre-state is available, historical reports cannot verify this dispatch.
        long commandAtMs = System.currentTimeMillis();
        ActionDraftStatusEnum status = unknown > 0 && accepted.isEmpty() && partial == 0 ? ActionDraftStatusEnum.INTERRUPTED
                : partial > 0 || unknown > 0 || rejected > 0 && rejected < actions.size()
                        ? ActionDraftStatusEnum.PARTIALLY_ACCEPTED
                : rejected == actions.size() ? ActionDraftStatusEnum.REJECTED
                : satisfied == actions.size() ? ActionDraftStatusEnum.NO_CHANGE : ActionDraftStatusEnum.ACCEPTED;
        ActionReceiptOutcomeEnum overall = switch (status) {
            case ACCEPTED -> ActionReceiptOutcomeEnum.ACCEPTED;
            case PARTIALLY_ACCEPTED -> ActionReceiptOutcomeEnum.PARTIALLY_ACCEPTED;
            case NO_CHANGE -> ActionReceiptOutcomeEnum.ALREADY_SATISFIED;
            case REJECTED -> ActionReceiptOutcomeEnum.REJECTED;
            default -> ActionReceiptOutcomeEnum.UNKNOWN;
        };
        ObjectNode result = mapper.createObjectNode();
        result.put("gateway", "customer");
        result.put("outcome", overall.name());
        result.set("receipts", receipts);
        ArrayNode readbacks = verifyReadback ? readbacks(identity, accepted, commandAtMs) : mapper.createArrayNode();
        result.set("readbacks", readbacks);
        ObjectNode readback = mapper.createObjectNode();
        ObjectNode observedStateMap = mapper.createObjectNode();
        String observedAt = null;
        for (JsonNode item : readbacks) {
            String actionId = item.path("actionId").asText("");
            if (actionId.isBlank()) continue;
            readback.put(actionId, item.path("outcome").asText("unconfirmed"));
            for (JsonNode action : actions) {
                if (actionId.equals(action.path("id").asText("")) && item.has("observedState")) {
                    observedStateMap.set(action.path("deviceId").asText(""), item.get("observedState"));
                    break;
                }
            }
            if (item.has("observedAt")) observedAt = item.path("observedAt").asText(observedAt);
        }
        result.set("readback", readback);
        result.set("observedStates", observedStateMap);
        if (observedAt != null) result.put("observedAt", observedAt);
        result.put("status", status.name());
        return new ActionDispatchResult(status, result.toString(), dispatchStarted.get(), overall);
    }

    private void dispatchGroups(RequestIdentity identity, AuthorizedDeviceScope scope, ArrayNode actions,
                                Map<String, Object> observedStates, List<List<Integer>> groups,
                                ObjectNode[] receiptsByAction, AtomicBoolean dispatchStarted,
                                AtomicBoolean draining, Thread caller, AtomicInteger nextGroup,
                                AtomicReference<RuntimeException> failure) {
        while (failure.get() == null && !stopRequested(draining, caller)) {
            int groupIndex = nextGroup.getAndIncrement();
            if (groupIndex >= groups.size()) return;
            for (int actionIndex : groups.get(groupIndex)) {
                if (failure.get() != null || stopRequested(draining, caller)) return;
                dispatchAction(identity, scope, actions.get(actionIndex), observedStates,
                        receiptsByAction, actionIndex, dispatchStarted);
            }
        }
    }

    private static boolean stopRequested(AtomicBoolean draining, Thread caller) {
        if (caller.isInterrupted()) draining.set(true);
        return draining.get();
    }

    private void dispatchAction(RequestIdentity identity, AuthorizedDeviceScope scope, JsonNode action,
                                Map<String, Object> observedStates, ObjectNode[] receiptsByAction,
                                int actionIndex, AtomicBoolean dispatchStarted) {
        String actionId = action.path("id").asText("");
        ObjectNode receipt = mapper.createObjectNode();
        receipt.put("actionId", actionId);
        if (!"access".equals(action.path("kind").asText(""))
                && commandSatisfied(action.path("command"), observedStates.get(action.path("deviceId").asText("")))) {
            receipt.put("outcome", ActionReceiptOutcomeEnum.ALREADY_SATISFIED.name());
            receiptsByAction[actionIndex] = receipt;
            return;
        }
        dispatchStarted.set(true);
        try {
            JsonNode response = dispatchOne(identity, scope, action);
            receipt.put("outcome", responseOutcome(response).name());
            receipt.set("response", response == null ? mapper.createObjectNode() : response);
            if (response != null && response.path("fields").isArray()) receipt.set("fields", response.get("fields"));
        } catch (GatewayAuthorizationException authorization) {
            throw authorization;
        } catch (ActionDispatchException interrupted) {
            if (interrupted.dispatchStarted()) {
                receipt.put("outcome", ActionReceiptOutcomeEnum.UNKNOWN.name());
                receiptsByAction[actionIndex] = receipt;
            }
            throw interrupted;
        } catch (RuntimeException error) {
            receipt.put("outcome", ActionReceiptOutcomeEnum.UNKNOWN.name());
            receipt.put("error", error.getMessage() == null ? "设备指令结果未知" : error.getMessage());
        }
        receiptsByAction[actionIndex] = receipt;
    }

    /** Runtime grouping: one hub (or standalone device) is one serial chain. */
    private static List<List<Integer>> deviceGroups(AuthorizedDeviceScope scope, ArrayNode actions) {
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int index = 0; index < actions.size(); index++) {
            String deviceId = actions.get(index).path("deviceId").asText("");
            AuthorizedDevice device = scope.device(deviceId).orElse(null);
            String key = device == null || device.hubDeviceId() == null || device.hubDeviceId().isBlank()
                    ? deviceId : device.hubDeviceId();
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(index);
        }
        return new ArrayList<>(groups.values());
    }

    /**
     * Runtime worker shape: bounded independent workers stop claiming groups on
     * a typed failure or a stop request, while requests already sent upstream
     * always run to their real outcome before this returns. Interrupting the
     * calling thread only flips {@code draining}; it never cancels an in-flight
     * upstream request, so a caller cannot mistake a cancelled wait for a
     * settled receipt.
     */
    private static boolean runWorkers(List<Runnable> workers, AtomicBoolean draining) {
        if (Thread.currentThread().isInterrupted()) {
            draining.set(true);
            return true;
        }
        CountDownLatch done = new CountDownLatch(workers.size());
        Thread caller = Thread.currentThread();
        for (Runnable worker : workers) {
            Thread.ofVirtual().name("action-dispatch-worker").unstarted(() -> {
                try {
                    worker.run();
                } finally {
                    done.countDown();
                    LockSupport.unpark(caller);
                }
            }).start();
        }
        boolean interrupted = false;
        while (true) {
            if (caller.isInterrupted()) {
                // Publish the stop before clearing interruption. An interruptible
                // await clears it before its catch, briefly hiding it from workers.
                draining.set(true);
                interrupted = true;
                Thread.interrupted();
            }
            if (done.getCount() == 0) return interrupted;
            LockSupport.park();
        }
    }

    private void validateActions(AuthorizedDeviceScope scope, ArrayNode actions) {
        for (JsonNode action : actions) {
            String deviceId = action.path("deviceId").asText("");
            AuthorizedDevice device = scope.device(deviceId).orElseThrow(() -> new IllegalArgumentException("设备不在授权范围"));
            if (device.observationOnly()) throw new IllegalArgumentException("设备不可控");
            String kind = action.path("kind").asText("");
            if (!device.kind().equals(kind)
                    || !kind.equals("lighting") && !kind.equals("ac") && !kind.equals("access")) {
                throw new IllegalArgumentException("设备类型与操作不匹配或不可控");
            }
            // 下发前统一走 canonical：小数/溢出/非法组合（含 ac power:null、off+temp）
            // 在任何上游调用之前拒绝——worker 的通用 RuntimeException 兜底会吞掉
            // dispatchOne 里的转换失败，校验必须发生在 satisfied/dispatch 判定之前。
            try {
                DeviceCommands.canonical(kind, action.path("command"));
            } catch (IllegalArgumentException invalidCommand) {
                throw new IllegalArgumentException(switch (kind) {
                    case "lighting" -> "照明指令参数无效";
                    case "access" -> "门禁指令参数无效";
                    case "ac" -> "空调指令参数无效";
                    default -> "设备类型不可控";
                });
            }
            requireBinding(device);
        }
    }

    private JsonNode dispatchOne(RequestIdentity identity, AuthorizedDeviceScope scope, JsonNode action) {
        String kind = action.path("kind").asText("");
        String deviceId = action.path("deviceId").asText("");
        AuthorizedDevice device = scope.device(deviceId).orElseThrow(() -> new IllegalArgumentException("设备不在授权范围"));
        ObjectNode command = action.path("command").deepCopy();
        Map<String, Object> body = new LinkedHashMap<>();
        String path;
        if ("lighting".equals(kind)) {
            path = lightingPath;
            body.put("lightingAreaDeviceId", requireBinding(device));
            body.put("closeOrOpen", command.has("closeOrOpen")
                    ? DeviceCommands.lightingCloseOrOpen(command.path("closeOrOpen"))
                    : DeviceCommands.closeOrOpenFromPower(command.path("power")));
        } else if ("access".equals(kind)) {
            path = accessPath;
            body.put("accessAreaDeviceId", requireBinding(device));
            body.put("controlType", DeviceCommands.accessControlType(command.path("controlType")));
        } else if ("ac".equals(kind)) {
            return dispatchAc(identity, device, command);
        } else {
            throw new IllegalArgumentException("设备类型不可控");
        }
        return post(identity, path, body);

    }
    
    private JsonNode dispatchAc(RequestIdentity identity, AuthorizedDevice device, ObjectNode command) {
        ArrayNode fields = mapper.createArrayNode();
        for (String field : List.of("power", "mode", "temperature", "fanSpeed")) {
            if (!command.has(field)) continue;
            try {
                JsonNode response;
                if (identity.source() == IotSourceEnum.COMPANY && device.metadata().get("companyAcActionId") instanceof String actionId) {
                    response = post(identity, "/micro-api/api/iot-center/interactive/" + encode(device.hubDeviceId()) + "/" + encode(actionId) + "/down",
                            companyAcBody(command, field));
                } else {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("acAreaDeviceId", requireBinding(device));
                    body.put("acDeviceParamTemplate", acTemplate(field, command.path(field).asText("off")));
                    if ("temperature".equals(field)) body.put("temperature", DeviceCommands.acTemperature(command.path(field)) * 10);
                    response = post(identity, acPath, body);
                }
                ObjectNode fieldResult = mapper.createObjectNode();
                fieldResult.put("field", field);
                fieldResult.put("outcome", responseOutcome(response).name());
                fieldResult.set("response", response);
                fields.add(fieldResult);
            } catch (GatewayAuthorizationException error) {
                boolean alreadySent = fields.valueStream().anyMatch(receiptField ->
                        !"REJECTED".equals(receiptField.path("outcome").asText())
                                && !"NOT_DISPATCHED".equals(receiptField.path("outcome").asText()));
                if (alreadySent) throw new ActionDispatchException("部分空调参数已下发，后续授权失效", error, true);
                throw error;
            } catch (ActionDispatchException error) {
                throw error;
            } catch (RuntimeException error) {
                ObjectNode fieldResult = mapper.createObjectNode();
                fieldResult.put("field", field);
                fieldResult.put("outcome", "UNKNOWN");
                fieldResult.put("error", error.getMessage() == null ? "设备指令结果未知" : error.getMessage());
                fields.add(fieldResult);
            }
        }
        ObjectNode result = mapper.createObjectNode();
        boolean allAccepted = fields.size() > 0;
        for (JsonNode field : fields) allAccepted &= "ACCEPTED".equals(field.path("outcome").asText());
        result.put("code", allAccepted ? 0 : 1);
        result.set("fields", fields);
        return result;
    }

    private JsonNode post(RequestIdentity identity, String path, Map<String, ?> body) {
        try {
            return client.post().uri(path)
                    .headers(headers -> identity.gatewayHeaders().forEach(headers::set))
                    .bodyValue(body).exchangeToMono(this::decode).block(timeout);
        } catch (GatewayAuthorizationException error) {
            throw error;
        } catch (ActionDispatchException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ActionDispatchException("上游控制请求中断", error, true);
        }
    }


    private reactor.core.publisher.Mono<JsonNode> decode(ClientResponse response) {
        int status = response.statusCode().value();
        if (status == 401 || status == 403) return response.releaseBody().then(reactor.core.publisher.Mono.error(new GatewayAuthorizationException(status)));
        if (!response.statusCode().is2xxSuccessful()) return response.releaseBody().then(reactor.core.publisher.Mono.error(new IllegalStateException("上游控制请求失败")));
        return response.bodyToMono(JsonNode.class).switchIfEmpty(reactor.core.publisher.Mono.error(new IllegalStateException("上游控制响应为空")))
                .flatMap(body -> {
                    int code = body.path("code").asInt(-1);
                    if (code == 401 || code == 403) return reactor.core.publisher.Mono.error(new GatewayAuthorizationException(code));
                    JsonNode content = body.get("content");
                    if (content != null && content.isTextual()) {
                        try { content = mapper.readTree(content.asText()); } catch (Exception ignored) { content = null; }
                    }
                    int nested = content == null ? 0 : content.path("code").asInt(0);
                    if (nested == 401 || nested == 403) return reactor.core.publisher.Mono.error(new GatewayAuthorizationException(nested));
                    return reactor.core.publisher.Mono.just(body);
                });
    }

    private ArrayNode readbacks(RequestIdentity identity, List<JsonNode> actions, long commandAtMs) {
        ArrayNode result = mapper.createArrayNode();
        // Open-once access controls have no persistent target state to verify.
        actions = actions.stream().filter(action -> !"access".equals(action.path("kind").asText(""))).toList();
        if (actions.isEmpty()) return result;
        List<String> ids = actions.stream().map(action -> action.path("deviceId").asText(""))
                .filter(id -> !id.isBlank()).distinct().toList();
        try {
            if (!readbackWait.isZero()) Thread.sleep(readbackWait.toMillis());
            DeviceStateReadResult states = iot.readDeviceStates(identity, ids).block(timeout);
            if (states == null) return unknownReadbacks(actions);
            for (JsonNode action : actions) {
                String deviceId = action.path("deviceId").asText("");
                Object state = states.states().get(deviceId);
                String observedAt = states.observedAtByDevice().getOrDefault(deviceId, states.observedAt());
                boolean fresh = freshSince(observedAt, commandAtMs);
                ObjectNode readback = mapper.createObjectNode();
                readback.put("actionId", action.path("id").asText(""));
                boolean confirmed = fresh && "ac".equals(action.path("kind").asText(""))
                        && state != null && commandSatisfied(action.path("command"), state);
                readback.put("outcome", confirmed ? "confirmed" : "unconfirmed");
                readback.set("observedState", mapper.valueToTree(state == null ? Map.of() : state));
                readback.put("observedAt", observedAt);
                result.add(readback);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return unknownReadbacks(actions);
        } catch (RuntimeException ignored) {
            return unknownReadbacks(actions);
        }
        return result;
    }

    private ArrayNode unknownReadbacks(List<JsonNode> actions) {
        ArrayNode result = mapper.createArrayNode();
        for (JsonNode action : actions) {
            ObjectNode readback = mapper.createObjectNode();
            readback.put("actionId", action.path("id").asText(""));
            readback.put("outcome", "unconfirmed");
            readback.set("observedState", mapper.createObjectNode());
            result.add(readback);
        }
        return result;
    }

    private boolean freshSince(String observedAt, long commandAtMs) {
        try {
            return Instant.parse(observedAt).toEpochMilli() >= commandAtMs;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean commandSatisfied(JsonNode command, Object state) {
        if (state == null || command == null || !command.isObject() || command.isEmpty()) return false;
        if (state instanceof Boolean lit) {
            return command.has("closeOrOpen") && command.size() == 1
                    && lit == (command.path("closeOrOpen").asInt() == 1);
        }
        JsonNode observed = mapper.valueToTree(state);
        if (command.has("power") && !command.path("power").asText().equals(observed.path("power").asText())) return false;
        if (command.has("mode") && !command.path("mode").asText().equals(observed.path("mode").asText())) return false;
        if (command.has("fanSpeed") && !command.path("fanSpeed").asText().equals(observed.path("fanSpeed").asText())) return false;
        if (command.has("temperature")) {
            double actual = observed.path("temperature").asDouble(Double.NaN);
            double target = command.path("temperature").asDouble(Double.NaN);
            if (!Double.isFinite(actual) || !Double.isFinite(target) || Math.abs(target - actual) > 0.01) return false;
        }
        if (command.has("closeOrOpen")) {
            JsonNode closeOrOpen = observed.get("closeOrOpen");
            if (closeOrOpen == null && observed.has("power")) {
                closeOrOpen = mapper.getNodeFactory().numberNode("on".equals(observed.path("power").asText()) ? 1 : 0);
            }
            if (closeOrOpen == null || command.path("closeOrOpen").asInt() != closeOrOpen.asInt(Integer.MIN_VALUE)) return false;
        }
        return true;
    }

    private ActionReceiptOutcomeEnum responseOutcome(JsonNode body) {
        if (body == null) return ActionReceiptOutcomeEnum.UNKNOWN;
        JsonNode fields = body.get("fields");
        if (fields != null && fields.isArray()) {
            if (fields.isEmpty()) return ActionReceiptOutcomeEnum.UNKNOWN;
            int accepted = 0, rejected = 0;
            for (JsonNode field : fields) {
                String outcome = field.path("outcome").asText();
                if ("ACCEPTED".equalsIgnoreCase(outcome)) accepted++;
                else if ("REJECTED".equalsIgnoreCase(outcome) || "NOT_DISPATCHED".equalsIgnoreCase(outcome)) rejected++;
            }
            if (accepted == fields.size()) return ActionReceiptOutcomeEnum.ACCEPTED;
            if (rejected == fields.size()) return ActionReceiptOutcomeEnum.REJECTED;
            return accepted > 0 ? ActionReceiptOutcomeEnum.PARTIALLY_ACCEPTED : ActionReceiptOutcomeEnum.UNKNOWN;
        }
        if (!body.hasNonNull("code")) return ActionReceiptOutcomeEnum.UNKNOWN;
        int code = body.path("code").asInt(-1);
        if (code != 0 && code != 200) return ActionReceiptOutcomeEnum.REJECTED;
        JsonNode content = body.get("content");
        if (content != null && content.isTextual()) {
            String text = content.asText().trim();
            if (text.startsWith("{") || text.startsWith("[")) {
                try { content = mapper.readTree(text); }
                catch (Exception malformed) { return ActionReceiptOutcomeEnum.UNKNOWN; }
            }
        }
        if (content != null && content.isObject() && content.hasNonNull("code")) {
            int nested = content.path("code").asInt(-1);
            if (nested != 0 && nested != 200) return ActionReceiptOutcomeEnum.REJECTED;
        }
        return ActionReceiptOutcomeEnum.ACCEPTED;
    }

    private static ArrayNode readActions(String json) {
        try {
            JsonNode value = new ObjectMapper().readTree(json);
            if (!value.isArray() || value.isEmpty()) throw new IllegalArgumentException("动作列表为空");
            return (ArrayNode) value;
        } catch (Exception error) { throw new IllegalArgumentException("动作列表无效", error); }
    }

    private static String requireBinding(AuthorizedDevice device) {
        if (device.controlRef() == null || device.controlRef().isBlank()) throw new IllegalArgumentException("设备控制绑定缺失");
        return device.controlRef();
    }

    private static Map<String, Object> companyAcBody(JsonNode command, String field) {
        String value = command.path(field).asText("");
        int val = switch (field) {
            case "power" -> "on".equals(value) ? 1 : 0;
            case "temperature" -> DeviceCommands.acTemperature(command.path(field)) * 10;
            case "mode" -> Map.of("fan", 2, "cool", 3, "heat", 4, "dry", 5, "auto", 7).getOrDefault(value, -1);
            default -> Map.of("low", 15, "high", 75, "auto", 101).getOrDefault(value, -1);
        };
        if (val < 0) throw new IllegalArgumentException("空调参数未核验");
        int type = switch (field) { case "power" -> "on".equals(value) ? 129 : 128; case "temperature" -> 136; default -> 206; };
        String idx = switch (field) { case "power" -> "O"; case "temperature" -> "tT"; case "mode" -> "MODE"; default -> "F"; };
        return Map.of("idx", idx, "type", type, "val", val, "v", val);
    }

    private static String acTemplate(String field, String value) {
        return switch (field) {
            case "temperature" -> "AC_TEMPERATURE";
            case "mode" -> "AC_MODE_" + value.toUpperCase(Locale.ROOT);
            case "fanSpeed" -> "AC_FAN_SPEED_" + value.toUpperCase(Locale.ROOT);
            default -> "AC_" + ("on".equals(value) ? "OPEN" : "CLOSE");
        };
    }

    private static String encode(String value) { return java.net.URLEncoder.encode(value == null ? "" : value, java.nio.charset.StandardCharsets.UTF_8); }
}
