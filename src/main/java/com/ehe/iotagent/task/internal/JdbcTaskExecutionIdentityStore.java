package com.ehe.iotagent.task.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import com.ehe.iotagent.task.TaskExecutionIdentityStore;
import com.ehe.iotagent.task.TaskExecutionIdentityStore.TaskExecutionIdentity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MySQL-backed protected execution identity. Only AES-GCM ciphertext is stored;
 * decryption requires the deployment's agent.task.credential-key and the result never
 * leaves this adapter except as the gateway identity used for one execution.
 */
public final class JdbcTaskExecutionIdentityStore implements TaskExecutionIdentityStore {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final byte[] key;

    public JdbcTaskExecutionIdentityStore(JdbcTemplate jdbc, ObjectMapper mapper, String credentialKey) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.key = parseKey(credentialKey);
    }

    @Override
    public void saveForTask(String taskId, TaskExecutionIdentity identity) {
        save(taskId, "TASK", identity);
    }

    @Override
    public void saveForPlan(String planId, TaskExecutionIdentity identity) {
        save(planId, "PLAN", identity);
    }

    private void save(String ownerId, String kind, TaskExecutionIdentity identity) {
        requireKey();
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("ownerId is required");
        String payload = encrypt(identity);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO task_execution_identities(owner_id,owner_kind,tenant_code,owner_key,payload,create_time,update_time) "
                        + "VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE tenant_code=VALUES(tenant_code),"
                        + "owner_key=VALUES(owner_key),payload=VALUES(payload),update_time=VALUES(update_time)",
                ownerId, kind, identity.tenantCode(), identity.ownerKey(), payload, now, now);
    }

    @Override
    public Optional<TaskExecutionIdentity> loadForTask(String taskId) {
        requireKey();
        return jdbc.query("SELECT tenant_code,owner_key,payload FROM task_execution_identities "
                                + "WHERE owner_id=? AND owner_kind='TASK'",
                        (rs, row) -> new StoredIdentity(rs.getString(1), rs.getString(2), rs.getString(3)), taskId)
                .stream().findFirst().map(this::decrypt);
    }

    @Override
    public void copyPlanIdentityToTask(String planId, String taskId) {
        if (planId == null || planId.isBlank() || taskId == null || taskId.isBlank()) return;
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO task_execution_identities(owner_id,owner_kind,tenant_code,owner_key,payload,create_time,update_time) "
                        + "SELECT ?, 'TASK', tenant_code, owner_key, payload, ?, ? FROM task_execution_identities "
                        + "WHERE owner_id=? AND owner_kind='PLAN' "
                        + "ON DUPLICATE KEY UPDATE owner_id=VALUES(owner_id)",
                taskId, now, now, planId);
    }

    @Override
    public void deleteForTask(String taskId) {
        jdbc.update("DELETE FROM task_execution_identities WHERE owner_id=? AND owner_kind IN ('TASK','PLAN')", taskId);
    }

    private TaskExecutionIdentity decrypt(StoredIdentity stored) {
        try {
            String[] parts = stored.payload().split(":");
            if (parts.length != 3) throw new IllegalStateException("任务托管凭据格式无效");
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] tag = Base64.getDecoder().decode(parts[1]);
            byte[] encrypted = Base64.getDecoder().decode(parts[2]);
            if (iv.length != 12 || tag.length != 16) throw new IllegalStateException("任务托管凭据格式无效");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(stored.ownerKey().getBytes(StandardCharsets.UTF_8));
            byte[] plain = cipher.doFinal(concat(encrypted, tag));
            Map<?, ?> body = mapper.readValue(plain, Map.class);
            Object authorization = body.get("authorization");
            if (!(authorization instanceof String value) || value.isBlank()) {
                throw new IllegalStateException("任务托管凭据缺少授权");
            }
            return new TaskExecutionIdentity(stored.tenantCode(), stored.ownerKey(), value);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("任务托管凭据无法解密", failure);
        }
    }

    private String encrypt(TaskExecutionIdentity identity) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Map<String, String> body = new LinkedHashMap<>();
            body.put("authorization", identity.authorization());
            body.put("tenantCode", identity.tenantCode());
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(identity.ownerKey().getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(mapper.writeValueAsBytes(body));
            byte[] tag = Arrays.copyOfRange(encrypted, encrypted.length - 16, encrypted.length);
            byte[] cipherText = Arrays.copyOf(encrypted, encrypted.length - 16);
            return Base64.getEncoder().encodeToString(iv) + ":"
                    + Base64.getEncoder().encodeToString(tag) + ":"
                    + Base64.getEncoder().encodeToString(cipherText);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("任务托管凭据无法加密", failure);
        }
    }

    private static byte[] concat(byte[] cipherText, byte[] tag) {
        byte[] result = new byte[cipherText.length + tag.length];
        System.arraycopy(cipherText, 0, result, 0, cipherText.length);
        System.arraycopy(tag, 0, result, cipherText.length, tag.length);
        return result;
    }

    private void requireKey() {
        if (key == null) throw new IllegalStateException("agent.task.credential-key 未配置，无法保管任务执行身份");
    }

    private static byte[] parseKey(String credentialKey) {
        if (credentialKey == null || credentialKey.isBlank()) return null;
        String normalized = credentialKey.trim();
        if (!normalized.matches("[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("agent.task.credential-key must be 32 bytes of hex");
        }
        byte[] result = new byte[32];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) Integer.parseInt(normalized.substring(index * 2, index * 2 + 2), 16);
        }
        return result;
    }

    private record StoredIdentity(String tenantCode, String ownerKey, String payload) { }
}
