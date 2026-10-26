package org.platform.demo.tools;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.platform.demo.KeyedLock;
import org.platform.demo.ToolResult;
import org.platform.demo.ToolUse;
import org.platform.kernel.ApprovalRecord;
import org.platform.kernel.ApprovalRequiredException;
import org.platform.kernel.Receipt;
import org.platform.kernel.id.IdempotencyKey;
import org.platform.kernel.id.StepCtx;
import org.platform.kernel.id.TenantId;
import org.platform.kernel.port.ApprovalStore;
import org.platform.kernel.port.ArtifactStore;
import org.platform.kernel.port.AuditLog;
import org.platform.kernel.port.Decision;
import org.platform.kernel.port.PolicyEngine;
import org.platform.kernel.port.Principal;
import org.platform.kernel.port.ReceiptStore;
import org.platform.kernel.port.ToolGateway;

/**
 * M2 工具网关：ToolRouter 的升级版，Agent 调外部系统的唯一出口。
 * 红线：Agent 不直连外部系统——这条一破，权限、审批、幂等、审计四道闸全废。
 * 十二步按序走：
 * 1 租户检查（注册表出本租户视图，跨租户连工具存在性都不泄露）
 * 2 注册表查找（查无此工具按 unknown tool 报错，不炸循环）
 * 3 发布版本检查（注册表钉版本，未发布/已下线的版本不可调）
 * 4 schema 校验（必填字段缺失/JSON 不合法，执行前就拒）
 * 5 策略裁决（PolicyEngine 给 ALLOW/DENY/ASK）
 * 6 审批（ASK 必须有匹配且未过期的 Human 批条，七字段逐一核对；挡下抛 ApprovalRequiredException——
 *   无结果不走结果通道，harness 收到即挂起 run）
 * 7 幂等检查（同键收据在，回放不重执行；执行后收据立刻抢存）
 * 8 执行（工具异常照抛——崩溃语义与 M1 一致，窗口 [Dispatched, 收据落库) 内崩 = 悬空步）
 * 9 结果大小守卫（超限内容不进轨迹）
 * 10 artifact offload（全文入仓，轨迹只留引用）
 * 11 审计事件（每个出口留痕：放行、拒绝、回放 alike）
 * 12 返回
 * 重放也重走 5/6：策略改了、批条过期了，已执行调用的结果同样不再放行——重放不是免检通道。
 * 幂等层已并入第 7 步：M2 装配不再包 IdempotentToolWrapper（包了也只是多一道同键检查，无害但多余）。
 * caller 随网关实例走（M2 demo：一个网关 = 一个机器凭证）；多 agent 共用网关时，
 * principal 要改成随 StepCtx 走——到时改这里，别在端口上开洞。
 */
public final class GatedToolGateway implements ToolGateway {

    /** 轨迹里单条工具结果的上限（字节）；超限走 artifact offload。 */
    public static final int DEFAULT_MAX_RESULT_BYTES = 4096;

    private final ToolRegistry registry;
    private final Principal caller;
    private final PolicyEngine policy;
    private final ApprovalStore approvals;
    private final ReceiptStore receipts;
    private final ArtifactStore artifacts;
    private final AuditLog audit;
    private final int maxResultBytes;
    private final ObjectMapper json = new ObjectMapper();
    /** 同键串行化（第 7 步）：find→execute→save 是 check-then-act，不加锁并发同键会双执行；跨进程要换 DB claim 行。
     *  分段锁——同键互斥即可，不必每键一把（锁表不随调用数增长），见 KeyedLock。 */
    private final KeyedLock locks = new KeyedLock();

    public GatedToolGateway(ToolRegistry registry, Principal caller, PolicyEngine policy,
                            ApprovalStore approvals, ReceiptStore receipts, ArtifactStore artifacts,
                            AuditLog audit) {
        this(registry, caller, policy, approvals, receipts, artifacts, audit, DEFAULT_MAX_RESULT_BYTES);
    }

    public GatedToolGateway(ToolRegistry registry, Principal caller, PolicyEngine policy,
                            ApprovalStore approvals, ReceiptStore receipts, ArtifactStore artifacts,
                            AuditLog audit, int maxResultBytes) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.caller = Objects.requireNonNull(caller, "caller");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.audit = Objects.requireNonNull(audit, "audit");
        if (maxResultBytes <= 0) {
            throw new IllegalArgumentException("maxResultBytes 必须为正: " + maxResultBytes);
        }
        this.maxResultBytes = maxResultBytes;
    }

    @Override
    public ToolResult invoke(StepCtx ctx, ToolUse call) {
        // 1 租户检查：注册表按租户出视图——别家的工具在这里就不存在，存在性都不泄露
        ToolRegistry.View tools = registry.forTenant(ctx.tenantId());
        // 2 注册表查找
        ToolRegistry.Registration reg = tools.find(call.name()).orElse(null);
        if (reg == null) {
            return audited(ctx, call, null, "unknown_tool", "unknown tool: " + call.name());
        }
        // 3 发布版本检查：版本由注册表钉死，未发布的版本不可调
        if (!reg.released()) {
            return audited(ctx, call, reg, "unreleased_version",
                    "tool 未发布: " + call.name() + "@" + reg.toolVersion());
        }
        // 4 schema 校验：必填字段缺失或 JSON 不合法，执行前就拒
        String schemaError = schemaProblem(reg.requiredInputFields(), call.input());
        if (schemaError != null) {
            return audited(ctx, call, reg, "schema_rejected", schemaError);
        }
        // 5 策略裁决
        // 已知取舍：回放重走第 5/6 步（重放不是免检通道）。代价是批条过期后已有收据的调用
        // 回放不出来，run 会卡死。M3 在 StepPlanned/StepDispatched 里冻结 Decision 与 approvalRef 解决。
        Decision decision = policy.decide(caller, call.name(), call.input());
        if (decision == Decision.DENY) {
            return audited(ctx, call, reg, "policy_denied", "策略拒绝: " + call.name());
        }
        // 6 审批：ASK 要有人批——机器批不出来，ApprovalRecord.approver 的类型就是闸
        // 挡下即抛：这步没有结果（没执行、没失败）——走错误结果会让 harness 记 StepFailed、
        // 假装这步有结论，模型接着采样、run 假收尾，人工后来补的批条永远没人消费
        if (decision == Decision.ASK) {
            ApprovalRecord approval = approvals.find(ctx.tenantId(), ctx.runId(), call.id()).orElse(null);
            String problem = approvalProblem(approval, reg, ctx, call);
            if (problem != null) {
                throw auditedPending(ctx, call, reg,
                        "需要人工审批（" + problem + "）；" + workOrder(reg, ctx, call));
            }
        }
        // 第 7 步起进入同键临界区：find → execute → save 必须串行，否则并发同键双执行
        IdempotencyKey key = new IdempotencyKey(ctx.tenantId(), ctx.runId(), call.id());
        synchronized (locks.lockFor(key)) {
            // 7 幂等检查：收据在，不执行，原样回放
            Receipt hit = receipts.find(key).orElse(null);
            if (hit != null) {
                return audited(ctx, call, reg, "replayed_from_receipt", hit.content(), hit.isError());
            }
            // 8 执行：工具异常照抛，崩溃恢复交给 journal + 收据（M1 语义原样保留）
            ToolResult result = reg.tool().invoke(call);
            // 9 结果大小守卫 + 10 artifact offload：超限的全文入仓，轨迹只留引用
            byte[] bytes = result.content().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > maxResultBytes) {
                String ref = artifacts.save(ctx.tenantId(), ctx.runId(), call.id(), result.content());
                result = new ToolResult(result.toolUseId(),
                        "结果 " + bytes.length + " 字节超出上限 " + maxResultBytes + "，全文已入 artifact 仓: " + ref,
                        result.isError());
            }
            // 第 7 步的下半场：收据存「最终返回内容」，重放与首次返回才一致；
            // 抢输（跨进程共享仓才会发生）丢弃自己的结果、用赢家的
            if (!receipts.trySaveIfAbsent(key, new Receipt(result.content(), result.isError()))) {
                Receipt winner = receipts.find(key).orElseThrow();
                result = new ToolResult(call.id(), winner.content(), winner.isError());
            }
            // 11 审计事件 + 12 返回
            return audited(ctx, call, reg, "executed", result.content(), result.isError());
        }
    }

    @Override
    public boolean idempotent(TenantId tenant, String toolName) {
        return registry.idempotent(tenant, toolName);
    }

    /** 拒绝类出口：错误结果回轨迹 + 审计留痕（第 11/12 步）。 */
    private ToolResult audited(StepCtx ctx, ToolUse call, ToolRegistry.Registration reg,
                               String outcome, String detail) {
        audit.append(event(ctx, call, reg, outcome, detail));
        return new ToolResult(call.id(), detail, true);
    }

    /** 放行/回放出口：结果原样返回 + 审计留痕（第 11/12 步）。 */
    private ToolResult audited(StepCtx ctx, ToolUse call, ToolRegistry.Registration reg,
                               String outcome, String detail, boolean isError) {
        audit.append(event(ctx, call, reg, outcome, detail));
        return new ToolResult(call.id(), detail, isError);
    }

    /** 等批出口：审计留痕后抛异常——没有结果就不走结果通道（第 11/12 步只留给有结果的出口）。 */
    private ApprovalRequiredException auditedPending(StepCtx ctx, ToolUse call, ToolRegistry.Registration reg,
                                                      String detail) {
        audit.append(event(ctx, call, reg, "awaiting_approval", detail));
        return new ApprovalRequiredException(detail);
    }

    /** 审计事件：七字段身份 + 谁在何时、结果如何。 */
    private AuditLog.Event event(StepCtx ctx, ToolUse call, ToolRegistry.Registration reg,
                                 String outcome, String detail) {
        return new AuditLog.Event(
                call.name(),
                reg == null ? null : reg.toolVersion(),
                call.inputDigest(),
                reg == null ? null : reg.targetResource(),
                ctx.tenantId(), ctx.runId(), call.id(),
                Instant.now(), caller, outcome, detail);
    }

    /** 批条核对：七字段逐一比对 + 有效期。返回 null = 批条有效。 */
    private static String approvalProblem(ApprovalRecord approval, ToolRegistry.Registration reg,
                                          StepCtx ctx, ToolUse call) {
        if (approval == null) {
            return "无批条";
        }
        if (!approval.toolName().equals(call.name())) {
            return "批条工具不符: " + approval.toolName();
        }
        if (!approval.toolVersion().equals(reg.toolVersion())) {
            return "批条版本不符: " + approval.toolVersion();
        }
        if (!approval.inputDigest().equals(call.inputDigest())) {
            return "批条输入摘要不符";
        }
        if (!approval.targetResource().equals(reg.targetResource())) {
            return "批条目标资源不符: " + approval.targetResource();
        }
        if (!approval.tenantId().equals(ctx.tenantId()) || !approval.runId().equals(ctx.runId())
                || !approval.toolUseId().equals(call.id())) {
            return "批条身份不符";   // find 本就按 (tenant, run, toolUseId) 查，这条防的是存错位置的批条
        }
        if (!approval.expiresAt().isAfter(Instant.now())) {
            return "批条已过期: " + approval.expiresAt();
        }
        return null;
    }

    /** 工单：把要批的调用按七字段摆给人看——批条的内容就从这来。 */
    private static String workOrder(ToolRegistry.Registration reg, StepCtx ctx, ToolUse call) {
        return "待批调用: " + call.name() + "@" + reg.toolVersion()
                + " input=" + call.inputDigest()
                + " target=" + reg.targetResource()
                + " tenant=" + ctx.tenantId().value()
                + " run=" + ctx.runId().value()
                + " toolUseId=" + call.id();
    }

    /** demo 级 schema 校验：input 必须是 JSON 对象且必填字段齐全。返回 null = 通过。 */
    private String schemaProblem(Set<String> requiredFields, String input) {
        JsonNode root;
        try {
            root = json.readTree(input);
        } catch (JsonProcessingException e) {
            return "input 不是合法 JSON: " + input;
        }
        if (root == null || !root.isObject()) {
            return "input 不是 JSON 对象: " + input;
        }
        for (String field : requiredFields) {
            if (root.get(field) == null || root.get(field).isNull()) {
                return "缺少必填字段: " + field;
            }
        }
        return null;
    }
}
