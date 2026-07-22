package gg.chaldea.client.reset.packet.mixin;

import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.filters.NetworkFilters;
import net.minecraftforge.network.filters.VanillaPacketFilter;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;
import java.util.function.Function;

@Mixin(NetworkFilters.class)
public class MixinNetworkFilters {

    @Shadow(remap = false) @Final private static Map<String, Function<Connection, VanillaPacketFilter>> instances;
    @Shadow(remap = false) @Final private static Logger LOGGER;

    /**
     * @author danorris709
     * @reason Prevent error
     */
    @Overwrite(remap = false)
    public static void injectIfNecessary(Connection manager)
    {
        instances.forEach((key, filterFactory) -> {
            if (manager.channel().pipeline().get(key) != null) {
                return;
            }

            try {
                VanillaPacketFilter filter = filterFactory.apply(manager);
                if (((VanillaPacketFilterAccessor)filter).invokeIsNecessary(manager)) {
                    manager.channel().pipeline().addBefore("packet_handler", key, filter);
                    LOGGER.debug("Injected {} into {}", filter, manager);
                }
            } catch (Exception e) {
                // 原实现为空 catch 块，pipeline 可能处于半重置状态，
                // 随后表现为难以诊断的 packet decode、channel 缺失或连接挂起。
                // 现在记录错误并断开连接，明确中止半重置的连接。
                LOGGER.error("Failed to inject network filter {} into {}", key, manager, e);
                manager.disconnect(Component.literal(
                        "Forge network reset failed while rebuilding packet filters."));
            }
        });
    }

}
