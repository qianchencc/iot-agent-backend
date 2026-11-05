<template>
  <el-form-item :prop="fieldKey" class="instruction-field">
    <template #label>
      <span class="instruction-label">
        <span>{{ label }}</span>
        <el-tag v-if="required" size="small" type="danger" effect="plain">必填</el-tag>
        <el-tag v-else size="small" type="info" effect="plain">可选追加</el-tag>
      </span>
    </template>
    <el-input
      :model-value="modelValue"
      type="textarea"
      :autosize="{ minRows: minRows, maxRows: maxRows }"
      :maxlength="maxLength"
      show-word-limit
      :placeholder="placeholder"
      @update:model-value="emit('update:modelValue', $event)"
    />
    <div v-if="note" class="instruction-note">{{ note }}</div>
  </el-form-item>
</template>

<script setup>
defineProps({
  modelValue: { type: String, required: true },
  fieldKey: { type: String, required: true },
  label: { type: String, required: true },
  required: { type: Boolean, default: false },
  maxLength: { type: Number, required: true },
  placeholder: { type: String, default: "" },
  note: { type: String, default: "" },
  minRows: { type: Number, default: 4 },
  maxRows: { type: Number, default: 12 }
});

const emit = defineEmits(["update:modelValue"]);
</script>

<style scoped>
.instruction-label {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.instruction-note {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-muted);
}
</style>
