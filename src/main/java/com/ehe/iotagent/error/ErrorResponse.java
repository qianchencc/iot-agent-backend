package com.ehe.iotagent.error;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一错误体 {@code {code,message,recovery?}}，对齐 runtime `docs/api/openapi.yaml` 的 ErrorResponse schema。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, Object recovery) {
}
