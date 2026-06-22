package cd.lan1akea.core.util;

import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 分布式唯一ID生成器（雪花算法变体）。
 * <p>
 * 结构：41位时间戳（毫秒）+ 10位机器ID + 12位序列号 = 63位。
 * 不使用符号位，保证ID始终为正数。
 * </p>
 */
public class IdGenerator {

    // 时间戳起始偏移（2025-01-01 00:00:00 UTC）
    private static final long EPOCH = 1735689600000L;

    // 各部分位数
    private static final long MACHINE_ID_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;

    // 最大值
    private static final long MAX_MACHINE_ID = ~(-1L << MACHINE_ID_BITS);
    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS);

    // 位移
    private static final long MACHINE_ID_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = MACHINE_ID_BITS + SEQUENCE_BITS;

    // 单例
    private static final IdGenerator INSTANCE = new IdGenerator();

    private final long machineId;
    private final AtomicLong sequence = new AtomicLong(0L);
    private volatile long lastTimestamp = -1L;

    private IdGenerator() {
        this.machineId = generateMachineId();
    }

    /**
     * 获取全局唯一ID。
     *
     * @return 唯一ID（正数）
     */
    public static long nextId() {
        return INSTANCE.generate();
    }

    /**
     * 获取全局唯一ID的字符串形式。
     *
     * @return ID字符串
     */
    public static String nextIdStr() {
        return String.valueOf(nextId());
    }

    private synchronized long generate() {
        long currentTimestamp = System.currentTimeMillis();

        // 时钟回拨检测
        if (currentTimestamp < lastTimestamp) {
            long offset = lastTimestamp - currentTimestamp;
            if (offset > 100) {
                throw new IllegalStateException(
                    "时钟回拨超过100ms，无法生成ID。回拨量: " + offset + "ms");
            }
            // 小幅度回拨，等待追上
            try {
                Thread.sleep(offset + 1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("ID生成被中断", e);
            }
            currentTimestamp = System.currentTimeMillis();
        }

        if (currentTimestamp == lastTimestamp) {
            // 同一毫秒内，序列号递增
            long seq = sequence.incrementAndGet() & MAX_SEQUENCE;
            if (seq == 0) {
                // 序列号耗尽，等待下一毫秒
                currentTimestamp = waitNextMillis(currentTimestamp);
            }
        } else {
            // 新的毫秒，重置序列号
            sequence.set(0L);
        }

        lastTimestamp = currentTimestamp;

        return ((currentTimestamp - EPOCH) << TIMESTAMP_SHIFT)
            | (machineId << MACHINE_ID_SHIFT)
            | sequence.get();
    }

    /**
     * 生成机器ID，基于MAC地址哈希。
     */
    private long generateMachineId() {
        try {
            java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    NetworkInterface ni = interfaces.nextElement();
                    byte[] mac = ni.getHardwareAddress();
                    if (mac != null && mac.length > 0) {
                        long hash = 0;
                        for (byte b : mac) {
                            hash = (hash << 5) - hash + (b & 0xFF);
                        }
                        return Math.abs(hash) & MAX_MACHINE_ID;
                    }
                }
            }
        } catch (Exception ignored) {
            // MAC获取失败，使用随机值
        }
        return new SecureRandom().nextInt((int) MAX_MACHINE_ID + 1);
    }

    private long waitNextMillis(long currentTimestamp) {
        long next = System.currentTimeMillis();
        while (next <= currentTimestamp) {
            next = System.currentTimeMillis();
        }
        return next;
    }
}
