package org.platform.kernel.port;

/**
 * 策略引擎：调用方、工具、输入三样在手，给出裁决。网关只认 Decision——
 * 规则是静态表还是远端策略服务，网关不关心。
 * 契约不带「拒绝理由」字段：网关审计只记 outcome=policy_denied，
 * 具体为什么拒，策略实现自己留痕（M5 审计统一收口）。
 */
public interface PolicyEngine {

    Decision decide(Principal principal, String toolName, String inputJson);
}
