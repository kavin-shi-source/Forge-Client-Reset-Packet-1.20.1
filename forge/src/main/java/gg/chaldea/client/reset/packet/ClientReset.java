package gg.chaldea.client.reset.packet;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import com.ibm.icu.impl.Pair;
import gg.chaldea.client.reset.packet.network.S2CReset;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.repository.Pack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.IExtensionPoint.DisplayTest;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.network.HandshakeHandler;
import net.minecraftforge.network.HandshakeMessages;
import net.minecraftforge.network.NetworkConstants;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.GameData;

@Mod("clientresetpacket")
public class ClientReset {

	public static final Field handshakeField;
	public static final Constructor contextConstructor;
	static final Logger logger = LogManager.getLogger();
	static final Marker RESETMARKER = MarkerManager.getMarker("RESETPACKET").setParents(MarkerManager.getMarker("FMLNETWORK"));

	// 10.1 修复：reset generation token，防止超时后旧 Runnable 晚到执行清理新连接状态。
	// 每次 handleClear 开始时递增，Runnable 执行前校验；超时/新 reset 时再次递增使旧 token 失效。
	private static final AtomicLong resetGeneration = new AtomicLong(0);

	public static SimpleChannel handshakeChannel;

	public ClientReset() {
		IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
		bus.addListener(ClientReset::init);
		ModLoadingContext.get().registerExtensionPoint(DisplayTest.class, () -> new DisplayTest(() -> NetworkConstants.IGNORESERVERONLY, (a, b) -> true));
	}

	@SubscribeEvent
	public static void init(FMLCommonSetupEvent event) {
		if (handshakeField == null) {
			logger.error(RESETMARKER, "Failed to find FML's handshake channel. Disabling mod.");
			return;
		}
		if (contextConstructor == null) {
			logger.error(RESETMARKER, "Failed to find FML's network event context constructor. Disabling mod.");
			return;
		}
		try {
			//handshakeField.setAccessible(true);
			//contextConstructor.setAccessible(true);
			Object handshake = handshakeField.get(null);
			if (handshake instanceof SimpleChannel) {
				handshakeChannel = (SimpleChannel)handshake;
				logger.info(RESETMARKER, "Registering forge reset packet.");
				handshakeChannel.messageBuilder(S2CReset.class, 98)
						.loginIndex(S2CReset::getLoginIndex, S2CReset::setLoginIndex)
						.decoder(S2CReset::decode)
						.encoder(S2CReset::encode)
						.consumerNetworkThread(HandshakeHandler.biConsumerFor(ClientReset::handleReset))
						.add();
				logger.info(RESETMARKER, "Registered forge reset packet successfully.");
			}
		}
		catch (Exception e) {
			logger.error(RESETMARKER, "Caught exception when attempting to utilize FML's handshake. Disabling mod. Exception: " + e.getMessage());
		}
	}

	public static void handleReset(HandshakeHandler handler, S2CReset msg, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		Connection connection = context.getNetworkManager();

		if (context.getDirection() != NetworkDirection.LOGIN_TO_CLIENT && context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
			connection.disconnect(Component.literal("Illegal packet received, terminating connection"));
			throw new IllegalStateException("Invalid packet received, aborting connection");
		}

		logger.info(RESETMARKER, "Received reset packet from server.");

		// 必须在 handleClear 之前保存 serverData：clearLevel() 会将 Minecraft.player 设为 null，
		// 而 Minecraft.getCurrentServer() 依赖 player.connection.getServerData()，
		// 之后调用会返回 null，导致 Xaero's World Map 生成 "Multiplayer_Unknown" 目录
		ServerData serverData = Minecraft.getInstance().getCurrentServer();

		if (!handleClear(context)) {
			return;
		}
		// 清除旧的 HandshakeHandler，否则 registerClientLoginChannel 的 compareAndSet(null,...) 是空操作，
		// 导致与新后端的 Forge 握手不完整，custom channel（如 xaeroworldmap:main）不会注册
		connection.channel().attr(NetworkConstants.FML_HANDSHAKE_HANDLER).set(null);
		NetworkHooks.registerClientLoginChannel(connection);
		connection.setProtocol(ConnectionProtocol.LOGIN);
		// P2-4 修复：按 Forge 1.20.1-47.x ClientHandshakePacketListenerImpl 构造函数语义明确每个参数：
		//   Connection connection            — 当前网络连接
		//   Minecraft minecraft              — Minecraft 实例
		//   ServerData serverData            — 目标服务器数据（非 null，reset 前已保存）
		//   Screen parent                    — null（reset 场景无父屏幕）
		//   boolean newWorld                  — false（连接到已有服务器，非新建单人世界）
		//   Duration worldLoadDuration        — null（非世界加载场景，无加载超时）
		//   Consumer<Component> statusUpdate — 空回调（reset 流程不显示状态消息）
		connection.setListener(new ClientHandshakePacketListenerImpl(
				connection, Minecraft.getInstance(), serverData, null, false, null, statusMessage -> {}
		));
		Minecraft.getInstance().pendingConnection = connection;
		context.setPacketHandled(true);
		try {
			handshakeChannel.reply(
				new HandshakeMessages.C2SAcknowledge(),
				(NetworkEvent.Context)contextConstructor.newInstance(connection, NetworkDirection.LOGIN_TO_CLIENT, 98)
			);
		}
		catch (Exception e) {
			// 10.2 修复：reply 失败后连接处于半重置状态（listener/protocol 已切换但 ack 未发出）。
			// 必须 fail-closed disconnect，不能只标记 packet 未处理后继续使用连接。
			logger.error(RESETMARKER, "Exception occurred when attempting to reply to reset packet, disconnecting", e);
			context.setPacketHandled(true);
			connection.disconnect(Component.literal("Reset reply failed, closing connection"));
			return;
		}
		logger.info(RESETMARKER, "Reset complete.");
	}

	@OnlyIn(Dist.CLIENT)
	public static boolean handleClear(NetworkEvent.Context context) {
		// 10.1 修复：每次 reset 分配唯一 generation token。
		// Runnable 在主线程执行前校验 token，若已过期（超时/新 reset/新连接）则跳过清理。
		final long myGeneration = resetGeneration.incrementAndGet();
		CompletableFuture<Void> future = context.enqueueWork(() -> {
			// 10.1 修复：校验 generation，防止超时后排队的旧 Runnable 晚到执行并清理新连接状态。
			if (resetGeneration.get() != myGeneration) {
				logger.warn(RESETMARKER, "Skipping stale clear task (generation={} != current={})",
						myGeneration, resetGeneration.get());
				return;
			}
			logger.debug(RESETMARKER, "Clearing");

			// Preserve
			ServerData serverData = Minecraft.getInstance().getCurrentServer();
			Pack serverPack = Minecraft.getInstance().getDownloadedPackSource().serverPack;

			// Clear
			if (Minecraft.getInstance().level == null) {
				// Ensure the GameData is reverted in case the client is reset during the handshake.
				GameData.revertToFrozen();
			}
			Minecraft.getInstance().getDownloadedPackSource().serverPack = null;

			// Clear
			Minecraft.getInstance().clearLevel(new GenericDirtMessageScreen(Component.translatable("connect.negotiating")));
			try {
				context.getNetworkManager().channel().pipeline().remove("forge:forge_fixes");
			} catch (NoSuchElementException ignored) {
			}
			try {
				context.getNetworkManager().channel().pipeline().remove("forge:vanilla_filter");
			} catch (NoSuchElementException ignored) {
			}
			// Restore
			Minecraft.getInstance().getDownloadedPackSource().serverPack = serverPack;
//			Minecraft.getInstance().setCurrentServer(serverData);//FIXME
		});

		logger.debug(RESETMARKER, "Waiting for clear to complete");
		try {
			// P2-3 修复：原实现 future.get() 无限等待，若网络线程等待 Minecraft 主线程
			// 而主线程被阻塞，会导致连接永久挂起。添加 30 秒超时，超时后断开连接。
			future.get(30, TimeUnit.SECONDS);
			logger.debug("Clear complete, continuing reset");
			return true;
		} catch (Exception ex) {
			// 10.1 修复：超时后递增 generation，使仍排在主线程队列中的旧 Runnable 失效。
			// 旧 Runnable 执行时会检测到 generation 不匹配而跳过，避免清理新连接状态。
			resetGeneration.incrementAndGet();
			logger.error(RESETMARKER, "Failed to clear (or timed out), closing connection", ex);
			context.getNetworkManager().disconnect(Component.literal("Failed to clear, closing connection"));
			return false;
		}
	}

	private static Field fetchHandshakeChannel() {
		try {
			return ObfuscationReflectionHelper.findField(NetworkConstants.class, "handshakeChannel");
		}
		catch (Exception e) {
			logger.error("Exception occurred while accessing handshakeChannel: " + e.getMessage());
			return null;
		}
	}

	private static Constructor fetchNetworkEventContext() {
		try {
			return ObfuscationReflectionHelper.findConstructor(NetworkEvent.Context.class, Connection.class, NetworkDirection.class, int.class);
		}
		catch (Exception e) {
			logger.error("Exception occurred while accessing getLoginIndex: " + e.getMessage());
			return null;
		}
	}

	static {
		handshakeField = fetchHandshakeChannel();
		contextConstructor = fetchNetworkEventContext();
	}
}
