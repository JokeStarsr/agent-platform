/**
 * L7 数据层：向量库（pgvector/Milvus）、知识库/文档管道、业务数据库访问、会话缓存、遥测仓库。
 * 设计约束：租户隔离（分 Collection / 分库）；知识库与业务库物理分离。
 */
package com.agent.data;