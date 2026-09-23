package com.github.lisama569.runtimefeatureflagsjava.eval;

/**
 * 放量分桶：对 (flagKey, visitorId) 做跨 JVM 稳定的 FNV-1a 64 位哈希，
 * 取模得到 [0, 10000) 的固定桶号（单位：基点，1/100 个百分点）。
 *
 * <p>关键性质：桶号只取决于开关名与访问者标识，与放量比例无关。
 * 因此放量比例从 p1 调整到 p2 时，已命中的访问者不会反复抖动：
 * 命中条件 bucket &lt; percentage，比例单调增减时结论只做单向迁移。</p>
 */
public final class BucketHasher {

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    private static final int BUCKETS = 10_000;

    private BucketHasher() {
    }

    /** 同一 flagKey + visitorId 永远得到相同桶号；不依赖对象 hashCode，跨 JVM 可复现。 */
    public static int bucketOf(String flagKey, String visitorId) {
        long hash = FNV_OFFSET_BASIS;
        hash = foldString(hash, flagKey);
        hash = foldByte(hash, (byte) 0);
        hash = foldString(hash, visitorId);
        return (int) Long.remainderUnsigned(hash, BUCKETS);
    }

    private static long foldString(long hash, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            hash = foldByte(hash, (byte) (c & 0xff));
            hash = foldByte(hash, (byte) ((c >>> 8) & 0xff));
        }
        return hash;
    }

    private static long foldByte(long hash, byte b) {
        hash ^= (b & 0xffL);
        hash *= FNV_PRIME;
        return hash;
    }
}
