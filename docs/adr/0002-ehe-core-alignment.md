# 0002 — ehe-core 原则上对齐客户版本；当前版本与 Boot 3.5 不兼容

Status: 采纳 · Date: 2026-10-28

## 背景

客户框架 `com.ehe:ehe-core-*:5.0.0.0-SNAPSHOT`（`docs/reference/core依赖包.zip`）提供用户上下文（`com.ehe.core.thread.LocalVariable.getPrincipalInfo()`）、租户过滤、Swagger、Redis/MyBatis 配置。经反查 jar：

- 字节码 major 55（Java 11 构建）
- 全线 `javax/servlet/*`（`AuthorizationFilterConfig implements javax.servlet.Filter`、`TenantCodeFilter`）
- `ehe-core-web` 含 45 处 springfox 引用（Springfox 已死在 Boot 3）

Spring Boot 3 = jakarta 命名空间：**该版本无法在 Boot 3.5 应用中运行**。且 zip 无父 pom（业务代码却引用 `${project.version}` 的 5.0.0.0-SNAPSHOT 父模块），本地也无法组装。

《项目技术选型清单》（同为客户权威）规定本项目 Java 21 + Spring Boot 3.5.x + Spring Security 6.x。

## 决定

用户裁定（2026-10-28）：**原则上对齐客户的版本。**

- 客户选型清单的版本（Java 21 / Boot 3.5.x / Security 6.x / springdoc 2.x / MyBatis-Plus 3.5.17 / Resilience4j 2.x）为权威，不偏离。
- ehe-core 等客户发布 jakarta/Boot 3 兼容版后接入；届时以 ehe 实现替换本项目的认证上下文实现。
- 过渡期在本项目内自实现等价认证上下文：Spring Security 6 + Filter + ThreadLocal，保留 `LocalVariable.getPrincipalInfo()` 同名 API 形状（`PrincipalContext` seam），调用方不感知实现来源。

## 后果

- 登录/鉴权为自研过渡实现，接口形状与 ehe 对齐以便无痛切换。
- 客户侧 jakarta 版 ehe-core 出现后，仅需替换 seam 实现。
