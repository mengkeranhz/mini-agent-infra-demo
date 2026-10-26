package org.platform.demo;

/**
 * 进程内分段锁：固定 N 把锁条，键哈希取模挑一把——内存 O(1)、不随键数增长、也永不 remove。
 * 取代「每键一把锁」的写法（ConcurrentHashMap&lt;键, Object&gt; + computeIfAbsent + 永不清理）：
 * 那种写法的键是随业务量线性增长的身份（每次工具调用、每个文件），锁表跟着无界涨；
 * 而 remove 才是真正容易写错的地方——握锁的线程还没出临界区，锁就可能被别的线程摘走。
 *
 * 代价是假竞争：不同键可能撞上同一把锁，多等一会儿——只损失吞吐，不损失正确性。
 * 用这把锁的地方（幂等层、网关、收据仓）要的都是「同键互斥」，不是「异键并行」，
 * 且它们都不是正确性的来源——真正拦并发双写的是收据仓的原子写
 * （内存 putIfAbsent / 落盘 UNIQUE(tenant, run, tool_call) + ON CONFLICT DO NOTHING），
 * 这把锁只是让并发同键的第二个线程直接命中收据、不白执行一遍。跨进程互斥同样只能靠存储层。
 */
public final class KeyedLock {

    /** 默认 1024 把：demo 量级的假竞争可忽略，常驻内存约 8KB。 */
    private static final int DEFAULT_STRIPES = 1024;

    private final Object[] stripes;

    public KeyedLock() {
        this(DEFAULT_STRIPES);
    }

    /** stripeCount 必须是 2 的幂：取模走掩码，非 2 的幂就得取余、还带进负数下标。 */
    public KeyedLock(int stripeCount) {
        if (Integer.bitCount(stripeCount) != 1) {
            throw new IllegalArgumentException("stripeCount 必须是 2 的幂: " + stripeCount);
        }
        Object[] created = new Object[stripeCount];
        for (int i = 0; i < created.length; i++) {
            created[i] = new Object();
        }
        this.stripes = created;
    }

    /** 同键永远同一把锁（不同键可能共享一把）；返回值拿去 synchronized。 */
    public Object lockFor(Object key) {
        int h = key.hashCode();
        h ^= h >>> 16;   // 高位掺进低位（HashMap 同款 spread）：短连续键不至于全挤在少数条上
        return stripes[h & (stripes.length - 1)];
    }
}
