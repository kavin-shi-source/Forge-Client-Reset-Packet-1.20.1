package gg.chaldea.client.reset.packet.mixin;

import gg.chaldea.client.reset.packet.ClientReset;
import gg.chaldea.client.reset.packet.ResetConnectionState;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 文档§七：重新进入 PLAY 后解除 fence。
 *
 * <p>真正的 reset 完成应发生在连接重新进入 PLAY 协议后，而非刚切换到 LOGIN 时。
 * 本 mixin 在 {@link Connection#setProtocol} 返回后观察协议变化：
 * 当协议变为 {@link ConnectionProtocol#PLAY} 且 reset 仍处于活跃阶段时，
 * 调用 {@link ResetConnectionState#complete} 解除 PacketEncoder fence。
 */
@Mixin(Connection.class)
public abstract class MixinConnectionProtocol {

    @Inject(
            method = "setProtocol",
            at = @At("RETURN")
    )
    private void clientresetpacket$finishResetOnPlay(
            ConnectionProtocol protocol,
            CallbackInfo callback
    ) {
        Connection connection = (Connection) (Object) this;

        if (protocol != ConnectionProtocol.PLAY
                || !ResetConnectionState.isResetActive(connection.channel())) {
            return;
        }

        ResetConnectionState.complete(connection.channel());
        ClientReset.logger.info(
                ClientReset.RESETMARKER,
                "Reset completed after entering PLAY. channel={}",
                connection.channel().id().asLongText()
        );
    }
}
