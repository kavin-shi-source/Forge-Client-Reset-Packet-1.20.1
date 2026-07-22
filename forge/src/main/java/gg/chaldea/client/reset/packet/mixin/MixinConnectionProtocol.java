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
 * 重新进入 PLAY 后解除 fence。
 *
 * <p>真正的 reset 完成应发生在连接重新进入 PLAY 协议后，而非刚切换到 LOGIN 时。
 * 本 mixin 在 {@link Connection#setProtocol} 返回后观察协议变化：仅当协议变为
 * {@link ConnectionProtocol#PLAY} 且当前 phase 为 {@link ResetConnectionState.Phase#LOGIN_NEGOTIATING}
 * 时才调用 {@link ResetConnectionState#complete} 解除 PacketEncoder fence。
 *
 * <p>FAILED 或 CLEARING_OLD_WORLD 阶段不会因某次意外
 * {@code setProtocol(PLAY)} 而提前 complete，避免 fence 被过早解除。
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
                || ResetConnectionState.phase(connection.channel())
                != ResetConnectionState.Phase.LOGIN_NEGOTIATING) {
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
