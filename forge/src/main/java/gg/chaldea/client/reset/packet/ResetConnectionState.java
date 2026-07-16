package gg.chaldea.client.reset.packet;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.util.concurrent.atomic.AtomicLong;

/**
 * FCRP 连接级 reset 状态机（文档§四）。
 *
 * <p>状态必须绑定 {@link Channel}，不能继续只使用全局 {@code AtomicLong}：
 * <ul>
 *   <li>全局 token 无法准确表达多个 Connection 或重连后的旧任务</li>
 *   <li>Channel Attribute 能保证旧连接和新连接相互隔离</li>
 *   <li>{@code generation} 用于判断异步回调是否仍属于当前 reset</li>
 * </ul>
 *
 * <p>阶段流转：
 * <pre>
 * PLAY_ACTIVE → CLEARING_OLD_WORLD → LOGIN_NEGOTIATING → PLAY_ACTIVE(complete)
 *                  ↘ FAILED（失败后保持 fence 直到 Channel 关闭）
 * </pre>
 */
public final class ResetConnectionState {

    public enum Phase {
        PLAY_ACTIVE,
        CLEARING_OLD_WORLD,
        LOGIN_NEGOTIATING,
        FAILED
    }

    private static final AtomicLong NEXT_GENERATION = new AtomicLong();

    private static final AttributeKey<Long> GENERATION =
            AttributeKey.valueOf("clientresetpacket:reset_generation");
    private static final AttributeKey<Phase> PHASE =
            AttributeKey.valueOf("clientresetpacket:reset_phase");
    private static final AttributeKey<Integer> DROPPED_PACKETS =
            AttributeKey.valueOf("clientresetpacket:dropped_stale_packets");
    // 文档§3.5/§4.1：非阻塞 reset 开始时显式暂停入站读取，替代原阻塞实现的隐式暂停效果。
    private static final AttributeKey<Boolean> PREVIOUS_AUTO_READ =
            AttributeKey.valueOf("clientresetpacket:previous_auto_read");

    private ResetConnectionState() {}

    /**
     * 开始一次新的 reset：分配新 generation，标记进入 CLEARING_OLD_WORLD 阶段，
     * 并显式暂停入站读取（文档§3.5）。
     *
     * @return 本次 reset 的 generation，后续所有异步回调都需校验此值
     */
    public static long begin(Channel channel) {
        long generation = NEXT_GENERATION.incrementAndGet();
        channel.attr(GENERATION).set(generation);
        channel.attr(PHASE).set(Phase.CLEARING_OLD_WORLD);
        channel.attr(DROPPED_PACKETS).set(0);
        channel.attr(PREVIOUS_AUTO_READ).set(channel.config().isAutoRead());
        channel.config().setAutoRead(false);
        return generation;
    }

    /**
     * 清理完成、LOGIN protocol 和 listener 建立、ACK 写出后恢复入站读取（文档§3.5/§4.1）。
     *
     * <p>仅当 generation 匹配时恢复，防止旧 reset 任务污染新状态。
     * 仅在 reset 前处于 autoRead=true 时才重新开启。
     */
    public static void resumeReads(Channel channel, long generation) {
        if (!isCurrent(channel, generation)) {
            return;
        }
        Boolean previous = channel.attr(PREVIOUS_AUTO_READ).getAndSet(null);
        if (Boolean.TRUE.equals(previous)) {
            channel.config().setAutoRead(true);
        }
    }

    /**
     * 判断给定 generation 是否仍是当前 channel 的活跃 reset。
     */
    public static boolean isCurrent(Channel channel, long generation) {
        Long current = channel.attr(GENERATION).get();
        return current != null && current == generation;
    }

    /**
     * 获取当前 phase，未标记时返回 {@link Phase#PLAY_ACTIVE}。
     */
    public static Phase phase(Channel channel) {
        Phase phase = channel.attr(PHASE).get();
        return phase == null ? Phase.PLAY_ACTIVE : phase;
    }

    /**
     * 进入 LOGIN_NEGOTIATING 阶段（清理完成、切换协议前调用）。
     * 仅当 generation 匹配时才更新，防止旧 reset 任务污染新状态。
     */
    public static void beginLogin(Channel channel, long generation) {
        if (isCurrent(channel, generation)) {
            channel.attr(PHASE).set(Phase.LOGIN_NEGOTIATING);
        }
    }

    /**
     * 完成 reset：回到 PLAY_ACTIVE，清零 dropped 计数。
     * 由 {@code MixinConnectionProtocol} 在连接重新进入 PLAY 协议时调用。
     */
    public static void complete(Channel channel) {
        channel.attr(PHASE).set(Phase.PLAY_ACTIVE);
        channel.attr(DROPPED_PACKETS).set(0);
    }

    /**
     * 失败路径：使当前 generation 失效，但保持 fence（文档§3.4/§4.1）。
     *
     * <p>不立即切回 PLAY_ACTIVE。phase 设为 FAILED，使 PacketEncoder fence 继续
     * 丢弃所有出站包，直到 Channel 真正关闭。避免连接关闭前旧 PLAY 包再次进入
     * 原版编码器触发 {@code Can't serialize unregistered packet}。
     */
    public static void fail(Channel channel) {
        channel.attr(GENERATION).set(NEXT_GENERATION.incrementAndGet());
        channel.attr(PHASE).set(Phase.FAILED);
    }

    /**
     * 判断 channel 是否处于 reset 活跃阶段（非 PLAY_ACTIVE）。
     */
    public static boolean isResetActive(Channel channel) {
        return phase(channel) != Phase.PLAY_ACTIVE;
    }

    /**
     * 判断 packet 在当前协议下是否已注册（可编码）。
     * 用于 reset fence 决定是否丢弃旧 PLAY 出站包。
     */
    public static boolean isRegistered(
            Channel channel,
            PacketFlow flow,
            Packet<? extends PacketListener> packet
    ) {
        ConnectionProtocol protocol =
                channel.attr(net.minecraft.network.Connection.ATTRIBUTE_PROTOCOL).get();
        return protocol != null && protocol.getPacketId(flow, packet) >= 0;
    }

    /**
     * 递增 dropped 计数并返回当前累计值（用于限流日志）。
     */
    public static int incrementDropped(Channel channel) {
        Integer previous = channel.attr(DROPPED_PACKETS).get();
        int next = previous == null ? 1 : previous + 1;
        channel.attr(DROPPED_PACKETS).set(next);
        return next;
    }
}
