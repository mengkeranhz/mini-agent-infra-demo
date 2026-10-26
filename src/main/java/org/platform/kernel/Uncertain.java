package org.platform.kernel;

import java.util.Objects;

import org.platform.kernel.id.RunId;
import org.platform.kernel.id.StepId;
import org.platform.kernel.id.TenantId;

/**
 * 悬空步：上次进程死在「已声明发起（Dispatched 已落盘）、结果（Done/Failed）未落盘」之间的调用。
 * journal 只能证明发起过，证明不了执行没执行、完成没完成——所以叫悬空。
 * attempt 是这个调用已发起过的次数（journal 里同一 toolUseId 最大 attempt 号），重试上限的计数依据；
 * inputDigest 是当时要发的内容摘要——人工裁决时得看得见「到底发了什么」。
 * 恢复裁决以它为单位，人工介入时看到的也是它——M2 里它就是人工队列里的一张工单。
 * 工单身份对齐 ApprovalRecord 的七字段纪律：(tenantId, runId, toolUseId) 定位一次调用，
 * toolName + inputDigest 呈现「发的是什么」。tenantId 必须随身带：人工队列按租户分片，
 * 缺了它工单跨租户串台——runId 只定位 run，定位不了「谁的 run」（与 StepCtx 同款三元组，顺序也一致）。
 * 裁决人只能是 Principal.Human——机器的 Scope 里没有 APPROVE，批不了也裁不了；
 * approver / expiresAt 是裁决发生时才落下的答案，工单上只有问题，不带答案。
 * toolVersion / targetResource 等 ToolRegistry（十二步的第 2/3 步）把版本钉进计划段再跟上：
 * journal 里没有的字段进不了工单——工单不编造。
 */
public record Uncertain(TenantId tenantId, RunId runId, StepId stepId, String toolUseId, String toolName,
                        boolean idempotent, int attempt, String inputDigest) {

    public Uncertain {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(runId, "runId");
    }
}
