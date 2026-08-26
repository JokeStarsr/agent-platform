/**
 * L1 接入层：统一入口、身份认证（SSO/RBAC）、租户识别、流量治理（限流/审计/SSE）。
 * 设计约束：所有请求携带 tenant_id + trace_id；审计日志不可篡改。
 */
package com.agent.access;