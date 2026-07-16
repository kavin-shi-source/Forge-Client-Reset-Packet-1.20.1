package gg.chaldea.client.reset.packet.mixin;

import gg.chaldea.client.reset.packet.ClientReset;
import gg.chaldea.client.reset.packet.ResetConnectionState;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 文档§六：FCRP 自己的旧 PLAY 出站 fence。
 *
 * <p>仅仅取消 Netty 阻塞仍不够。reset 开始之前，某些其他线程可能已经提交了发送任务；
 * 或 reset 期间仍有旧世界模组异步调用 {@code Connection.send()}。即使 event loop
 * 没被长时间阻塞，这些任务仍可能晚于协议切换执行，导致旧 PLAY 包进入 LOGIN 编码器
 * 触发 {@code Can't serialize unregistered packet}。
 *
 * <p>本 mixin 在 {@link PacketEncoder#encode} 前增加仅限 reset 期间的保护：
 * <ul>
 *   <li>只在 FCRP reset active 时生效</li>
 *   <li>当前协议已经注册的包继续发送</li>
 *   <li>当前协议没有注册的包丢弃并打印真实 packetClass 诊断</li>
 *   <li>reset 结束后完全恢复原版行为</li>
 * </ul>
 */
@Mixin(PacketEncoder.class)
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
        if (context == null
                || context.channel() == null
                || packet == null
                || !ResetConnectionState.isResetActive(context.channel())) {
            return;
        }

        if (ResetConnectionState.isRegistered(context.channel(), this.flow, packet)) {
            return;
        }

        int dropCount = ResetConnectionState.incrementDropped(context.channel());

        if (dropCount <= 10 || dropCount % 64 == 0) {
            ClientReset.logger.warn(
                    ClientReset.RESETMARKER,
                    "Dropped stale outbound packet during reset. "
                            + "phase={}, flow={}, packetClass={}, "
                            + "dropCount={}, channel={}",
                    ResetConnectionState.phase(context.channel()),
                    this.flow,
                    packet.getClass().getName(),
                    dropCount,
                    context.channel().id().asLongText()
            );
        }

        callback.cancel();
    }
}
