package org.platform.kernel.id;

/**
 * 租户标识：多租户隔离维度，M2 接真实来源。幂等键、journal、配额都从 tenant 起头，
 * 现在就进键，M2 才不用返工——届时只换 Main 里的取值。
 */
public record TenantId(String value) {

    public static final TenantId DEFAULT = new TenantId("default");

    public TenantId {
        RunId.requireValid(value, "TenantId");
    }
}
