/**
 * L3 智能体编排层：Agent Runtime（带护栏的 ReAct 循环）、Workflow 引擎（DAG/HITL）、
 * 多智能体协作、Skill Hub、Agent Harness。
 * 设计约束：每次执行有预算（步数/Token/时长）；失败可重放；状态可持久化。
 */
package com.agent.orchestration;