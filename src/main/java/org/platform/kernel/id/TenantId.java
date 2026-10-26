package org.platform.kernel.id;

/**
 * 租户标识：多租户隔离维度，M2 接真实来源。幂等键、journal、配额都从 tenant 起头，
 * 现在就进键，M2 才不用返工——届时只换 Main 里的取值。
 * 刻意不设 DEFAULT 常量：全局默认租户 = 全平台共用一个分区，键里带着它等于隔离白做；
 * 装配处必须显式给值，出处不明宁可编译不过，也别悄悄落进同一个默认租户。
 */
public record TenantId(String value) {

    public TenantId {
        RunId.requireValid(value, "TenantId");
    }
}
