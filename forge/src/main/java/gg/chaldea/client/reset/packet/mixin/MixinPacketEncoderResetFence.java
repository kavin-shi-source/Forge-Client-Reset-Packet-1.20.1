package gg.chaldea.client.reset.packet.mixin;

import gg.chaldea.client.reset.packet.ClientReset;
import gg.chaldea.client.reset.packet.ResetConnectionState;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.login.ServerboundCustomQueryPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FCRP 自己的旧 PLAY 出站 fence。
 *
 * <p>仅仅取消 Netty 阻塞仍不够。reset 开始之前，某些其他线程可能已经提交了发送任务；
 * 或 reset 期间仍有旧世界模组异步调用 {@code Connection.send()}。即使 event loop
 * 没被长时间阻塞，这些任务仍可能晚于协议切换执行，导致旧 PLAY 包进入 LOGIN 编码器
 * 触发 {@code Can't serialize unregistered packet}。
 *
 * <p>本 mixin 在 {@link PacketEncoder#encode} 前增加仅限 reset 期间的保护：
 * <ul>
 *   <li>PLAY_ACTIVE：完全不影响正常游戏和 BO 带宽优化</li>
 *   <li>CLEARING_OLD_WORLD：丢弃全部旧出站包</li>
 *   <li>LOGIN_NEGOTIATING：只允许当前 LOGIN 注册包和 Forge query</li>
 *   <li>FAILED：继续保持 fence</li>
 * </ul>
 *
 * <p>Mixin priority 设为 {@code 900}，低于 BO 默认 priority
 * {@code 1000}，使 BO 的 {@code PacketOutPipeMixin} 有机会先对
 * {@link ServerboundCustomQueryPacket} 执行特殊 LOGIN 编码，避免 FCRP 把 Forge
 * 握手 ACK 当成“旧未注册包”静默吞掉。同时在 LOGIN_NEGOTIATING 阶段明确放行
 * {@link ServerboundCustomQueryPacket}，即使未安装 BO 也能由原版注册表正常编码。
 */
@Mixin(value = PacketEncoder.class, priority = 900)
public abstract class MixinPacketEncoderResetFence<T extends PacketListener> {

    @Shadow
    @Final
    private PacketFlow flow;

    @Inject(
            method = "encode*",
            at = @At("HEAD"),
            cancellable = true
    )
    private void clientresetpacket$dropStalePacketDuringReset(
            ChannelHandlerContext context,
            Packet<T> packet,
            ByteBuf output,
            CallbackInfo callback
    ) {
        if (context == null || context.channel() == null || packet == null) {
            return;
        }

        ResetConnectionState.Phase phase =
                ResetConnectionState.phase(context.channel());

        if (phase == ResetConnectionState.Phase.PLAY_ACTIVE) {
            return;
        }

        // Forge LOGIN query 是切服握手所必需的。
        // FCRP 的 priority 低于 BO，使 BO 有机会先执行特殊编码。
        if (phase == ResetConnectionState.Phase.LOGIN_NEGOTIATING
                && packet instanceof ServerboundCustomQueryPacket) {
            return;
        }

        boolean shouldDrop =
                phase == ResetConnectionState.Phase.CLEARING_OLD_WORLD
                        || phase == ResetConnectionState.Phase.FAILED
                        || !ResetConnectionState.isRegistered(
                                context.channel(),
                                this.flow,
                                packet
                        );

        if (!shouldDrop) {
            return;
        }

        int dropCount = ResetConnectionState.incrementDropped(context.channel());

        if (dropCount <= 10 || dropCount % 64 == 0) {
            ClientReset.logger.warn(
                    ClientReset.RESETMARKER,
                    "Dropped outbound packet during reset. "
                            + "phase={}, flow={}, packetClass={}, "
                            + "dropCount={}, channel={}",
                    phase,
                    this.flow,
                    packet.getClass().getName(),
                    dropCount,
                    context.channel().id().asLongText()
            );
        }

        callback.cancel();
    }
}
