package com.ehe.iotagent.iot.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import com.ehe.iotagent.error.BusinessException;
import com.ehe.iotagent.iot.AuthorizedDevice;
import com.ehe.iotagent.iot.AuthorizedDeviceScope;
import com.ehe.iotagent.iot.DeviceCandidateBinding;
import com.ehe.iotagent.iot.DeviceCandidateQuery;
import com.ehe.iotagent.iot.DeviceCandidateRecord;
import com.ehe.iotagent.iot.DeviceCandidateSet;
import com.ehe.iotagent.iot.DeviceCandidateSetStore;
import com.ehe.iotagent.iot.DeviceCapabilityRequest;
import com.ehe.iotagent.iot.DeviceDirectoryBatch;
import com.ehe.iotagent.iot.RequestIdentity;

/** One matcher and durable handle contract for source fixtures and local ordinary consumers. */
final class DeviceCandidates {
    private DeviceCandidates() { }

    static DeviceCandidateSet find(AuthorizedDeviceScope scope, DeviceCandidateQuery query,
            DeviceCandidateBinding binding, DeviceCandidateSetStore store) {
        DeviceCandidateMatcher.Result result = DeviceCandidateMatcher.search(scope.devices(), query);
        String id = UUID.randomUUID().toString();
        DeviceCandidateSet set = new DeviceCandidateSet(id, query, result.visible(), result.complete(), result.totalMatches(),
                result.groups(), result.nameHintMatches(), result.unmatchedNameHints(), result.resolution(), result.members());
        if (binding == null) return set;
        if (store == null) throw new BusinessException("CANDIDATE_SET_STORE_UNAVAILABLE", "设备候选集事实存储未配置", 503);
        RequestIdentity identity = scope.identity();
        DeviceCandidateRecord saved = store.save(new DeviceCandidateRecord(id, binding.conversationId(), identity.tenantCode(),
                identity.ownerKey(), identity.source(), scope.catalogFingerprint(), query, result.members(), result.complete(),
                result.totalMatches(), Instant.now().toString(), result.resolution().mode(), null));
        if (saved.handle() == null || saved.handle().isBlank()) return set;
        List<String> handles = new ArrayList<>(result.visible().size());
        for (int index = 0; index < result.visible().size(); index++) handles.add(saved.deviceHandle(index));
        return new DeviceCandidateSet(id, query, result.visible(), result.complete(), result.totalMatches(), result.groups(),
                result.nameHintMatches(), result.unmatchedNameHints(), result.resolution(), saved.handle(), handles, result.members());
    }

    static DeviceCandidateRecord require(DeviceCandidateSetStore store, RequestIdentity identity, String id, String conversationId) {
        if (store == null) throw new BusinessException("CANDIDATE_SET_STORE_UNAVAILABLE", "设备候选集事实存储未配置", 503);
        if (conversationId == null || conversationId.isBlank()) throw new BusinessException("INVALID_DEVICE_REFERENCE", "设备候选集会话绑定缺失");
        DeviceCandidateRecord record = store.find(id, conversationId).orElseThrow(DeviceCandidates::notFound);
        if (!identity.tenantCode().equals(record.tenantCode()) || !identity.ownerKey().equals(record.ownerKey())
                || identity.source() != record.source() || !conversationId.equals(record.conversationId())) throw notFound();
        return record;
    }

    static DeviceCandidateRecord resolve(DeviceCandidateSetStore store, RequestIdentity identity, String id,
            String conversationId, DeviceDirectoryBatch batch) {
        DeviceCandidateRecord record = require(store, identity, id, conversationId);
        AuthorizedDeviceScope current = batch.forRegion(record.query().regionId());
        for (AuthorizedDevice member : record.members()) {
            if (current.device(member.id()).filter(device -> device.kind().equals(member.kind())).isEmpty()) {
                throw new BusinessException("CANDIDATE_SET_STALE", "设备候选集成员授权已变化，请重新查找", 409);
            }
        }
        return record;
    }

    static void validate(DeviceCapabilityRequest request) {
        if (request == null || request.candidateSets().isEmpty()) throw new BusinessException("INVALID_DEVICE_REFERENCE", "设备候选集不能为空");
        var sets = new HashSet<String>();
        var devices = new HashSet<String>();
        for (var selection : request.candidateSets()) {
            if (!sets.add(selection.candidateSetId())) throw new BusinessException("INVALID_DEVICE_REFERENCE", "设备候选集引用重复");
            if (selection.deviceIds().isEmpty()) throw new BusinessException("INVALID_DEVICE_REFERENCE", "设备引用无效");
            for (String id : selection.deviceIds()) {
                if (id == null || id.isBlank() || !devices.add(id)) throw new BusinessException("INVALID_DEVICE_REFERENCE", "设备引用无效或重复");
            }
        }
    }

    private static BusinessException notFound() { return new BusinessException("CANDIDATE_SET_NOT_FOUND", "设备候选集不存在", 404); }
}
