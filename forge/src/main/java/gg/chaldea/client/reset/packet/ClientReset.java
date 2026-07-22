package gg.chaldea.client.reset.packet;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import gg.chaldea.client.reset.packet.client.ServerSwitchPreparationOverlay;
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

	public static final String MODID = "clientresetpacket";
	public static final Field handshakeField;
	public static final Constructor contextConstructor;
	public static final Logger logger = LogManager.getLogger();
	public static final Marker RESETMARKER = MarkerManager.getMarker("RESETPACKET").setParents(MarkerManager.getMarker("FMLNETWORK"));

	public static SimpleChannel handshakeChannel;

	// 跟踪上一次显示的阶段 key，避免重复创建相同屏幕。
	private static volatile String lastStatusKey;

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

	/**
	 * 非阻塞 reset。
	 *
	 * <p>关键变化：
	 * <ul>
	 *   <li>网络线程提交主线程任务后立即返回</li>
	 *   <li>不再调用 {@code future.get()}</li>
	 *   <li>清理完成后，通过 {@code channel.eventLoop().execute()} 回到正确的网络线程</li>
	 *   <li>每一步都校验 generation</li>
	 *   <li>超时由 {@code orTimeout()} 处理，而不是阻塞等待</li>
	 * </ul>
	 */
	public static void handleReset(HandshakeHandler handler, S2CReset msg, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		Connection connection = context.getNetworkManager();

		if (context.getDirection() != NetworkDirection.LOGIN_TO_CLIENT && context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
			connection.disconnect(Component.translatable(
					"clientresetpacket.disconnect.illegal_packet"
			));
			context.setPacketHandled(true);
			return;
		}

		context.setPacketHandled(true);

		// 必须在 handleClear 之前保存 serverData：clearLevel() 会将 Minecraft.player 设为 null，
		// 而 Minecraft.getCurrentServer() 依赖 player.connection.getServerData()，
		// 之后调用会返回 null，导致 Xaero's World Map 生成 "Multiplayer_Unknown" 目录
		ServerData serverData = Minecraft.getInstance().getCurrentServer();
		long generation = ResetConnectionState.begin(connection.channel());
		if (generation < 0L) {
			logger.warn(
					RESETMARKER,
					"Ignoring duplicate reset while phase={}",
					ResetConnectionState.phase(connection.channel())
			);
			context.setPacketHandled(true);
			return;
		}

		context.setPacketHandled(true);

		ServerSwitchPreparationOverlay.begin(
				generation,
				() -> startClearAfterPreparation(
						context,
						connection,
						serverData,
						generation
				)
		);

		// Channel 在 HUD 等待期间关闭时，取消 HUD 避免 fallback
		// 在断线后继续启动 clearLevel / LOGIN listener 重建。
		connection.channel().closeFuture().addListener(ignored ->
				ServerSwitchPreparationOverlay.cancel(generation)
		);

		logger.info(
				RESETMARKER,
				"Preparation HUD activated before old-world cleanup "
						+ "(generation={}, channel={})",
				generation,
				connection.channel().id().asLongText()
		);
	}

	/**
	 * HUD 首帧完成后开始旧世界清理。
	 *
	 * <p>从 {@link ServerSwitchPreparationOverlay#begin} 的回调调用。重新校验 generation，
	 * 然后执行原 {@code enqueueClear} + {@code orTimeout} + {@code whenComplete} 流程。
	 */
	@OnlyIn(Dist.CLIENT)
	private static void startClearAfterPreparation(
			NetworkEvent.Context context,
			Connection connection,
			ServerData serverData,
			long generation
	) {
		// HUD 等待期间 Channel 可能已关闭（断线/超时/客户端关闭），
		// 已关闭 Channel 的 Attribute 仍可能保留原 generation，仅校验 generation 不够。
		// 此时不应继续 clearLevel / LOGIN listener 重建 / 向关闭 Channel 提交任务。
		if (!connection.channel().isActive()) {
			ServerSwitchPreparationOverlay.cancel(generation);

			logger.info(
					RESETMARKER,
					"Skipping old-world cleanup because the connection "
							+ "closed before preparation completed "
							+ "(generation={})",
					generation
			);
			return;
		}

		if (!ResetConnectionState.isCurrent(
				connection.channel(),
				generation
		)) {
			ServerSwitchPreparationOverlay.cancel(generation);

			logger.warn(
					RESETMARKER,
					"Skipping old-world cleanup for stale reset "
							+ "generation={}",
					generation
			);
			return;
		}

		// enqueueClear 同步抛出（executor 拒绝、context 失效等）会从
		// ClientTickEvent 监听器向外传播，且此时 state 已清空、autoRead 仍为 false。
		// 必须捕获并走 failReset 进入 FAILED fence + 断线，避免半初始化活连接。
		CompletableFuture<Void> clearFuture;
		try {
			clearFuture = enqueueClear(
					context,
					connection,
					generation
			);
		} catch (Throwable error) {
			connection.channel().eventLoop().execute(() ->
					failReset(
							connection,
							generation,
							"clientresetpacket.disconnect.clear_failed",
							error
					)
			);
			return;
		}

		clearFuture.orTimeout(30, TimeUnit.SECONDS)
				.whenComplete((ignored, error) ->
						connection.channel().eventLoop().execute(() -> {
							if (!ResetConnectionState.isCurrent(
									connection.channel(),
									generation
							)) {
								logger.warn(
										RESETMARKER,
										"Ignoring stale reset completion "
												+ "generation={}",
										generation
								);
								return;
							}

							if (error != null) {
								failReset(
										connection,
										generation,
										"clientresetpacket.disconnect.clear_failed",
										error
								);
								return;
							}

							continueLoginReset(
									context,
									connection,
									serverData,
									generation
							);
						})
				);
	}

	/**
	 * 提交清理任务到主线程，返回 CompletableFuture 而不阻塞网络线程。
	 */
	@OnlyIn(Dist.CLIENT)
	private static CompletableFuture<Void> enqueueClear(
			NetworkEvent.Context context,
			Connection connection,
			long generation
	) {
		return context.enqueueWork(() -> {
			if (!ResetConnectionState.isCurrent(connection.channel(), generation)) {
				logger.warn(
						RESETMARKER,
						"Skipping stale clear task generation={}",
						generation
				);
				return;
			}

			logger.debug(RESETMARKER, "Clearing");
		showStatusNow("clientresetpacket.status.clearing");

			// Preserve
			Pack serverPack = Minecraft.getInstance().getDownloadedPackSource().serverPack;

			// Clear
			if (Minecraft.getInstance().level == null) {
				// Ensure the GameData is reverted in case the client is reset during the handshake.
				GameData.revertToFrozen();
			}
			Minecraft.getInstance().getDownloadedPackSource().serverPack = null;

			// Clear
			Minecraft.getInstance().clearLevel(new GenericDirtMessageScreen(
					Component.translatable("clientresetpacket.status.clearing")
			));

			removePipelineHandler(connection, "forge:forge_fixes");
			removePipelineHandler(connection, "forge:vanilla_filter");

			// Restore
			Minecraft.getInstance().getDownloadedPackSource().serverPack = serverPack;
		});
	}

	/**
	 * 安全移除 pipeline handler，不再依靠捕获 NoSuchElementException。
	 */
	private static void removePipelineHandler(Connection connection, String name) {
		if (connection.channel().pipeline().get(name) != null) {
			connection.channel().pipeline().remove(name);
		}
	}

	/**
	 * 清理完成后继续 LOGIN reset。
	 *
	 * <p>此时不能立刻把 reset 标记为完成；真正的 complete 发生在连接重新进入 PLAY 后
	 * （由 {@code MixinConnectionProtocol} 触发）。
	 */
	private static void continueLoginReset(
			NetworkEvent.Context context,
			Connection connection,
			ServerData serverData,
			long generation
	) {
		try {
			showStatus("clientresetpacket.status.connecting");

			// 清除旧的 HandshakeHandler，否则 registerClientLoginChannel 的 compareAndSet(null,...) 是空操作，
			// 导致与新后端的 Forge 握手不完整，custom channel（如 xaeroworldmap:main）不会注册
			connection.channel()
					.attr(NetworkConstants.FML_HANDSHAKE_HANDLER)
					.set(null);

			NetworkHooks.registerClientLoginChannel(connection);
			ResetConnectionState.beginLogin(connection.channel(), generation);

			connection.setProtocol(ConnectionProtocol.LOGIN);
			// 按 Forge 1.20.1-47.x ClientHandshakePacketListenerImpl 构造函数语义明确每个参数：
			//   Connection connection            — 当前网络连接
			//   Minecraft minecraft              — Minecraft 实例
			//   ServerData serverData            — 目标服务器数据（非 null，reset 前已保存）
			//   Screen parent                    — null（reset 场景无父屏幕）
			//   boolean newWorld                  — false（连接到已有服务器，非新建单人世界）
			//   Duration worldLoadDuration        — null（非世界加载场景，无加载超时）
			//   Consumer<Component> statusUpdate — 显示"正在连接目标服务器"阶段提示
			connection.setListener(new ClientHandshakePacketListenerImpl(
					connection, Minecraft.getInstance(), serverData, null, false, null,
					status -> showStatus("clientresetpacket.status.connecting")
			));

			Minecraft.getInstance().pendingConnection = connection;

			handshakeChannel.reply(
					new HandshakeMessages.C2SAcknowledge(),
					(NetworkEvent.Context) contextConstructor.newInstance(
							connection, NetworkDirection.LOGIN_TO_CLIENT, 98
					)
			);

			// ACK 写出后恢复入站读取，并显示"等待目标服登录"阶段提示。
			// 顺序：setProtocol(LOGIN) → setListener(LOGIN) → 写出 ACK → 恢复 autoRead → showStatus(login)
			ResetConnectionState.resumeReads(connection.channel(), generation);
			showStatus("clientresetpacket.status.login");

			logger.info(
					RESETMARKER,
					"Reset entered LOGIN negotiation generation={}",
					generation
			);
		} catch (Exception error) {
			failReset(
					connection,
					generation,
					"clientresetpacket.disconnect.login_failed",
					error
			);
		}
	}

	/**
	 * reset 失败时 fail-closed。
	 *
	 * <p>先取消前置 HUD（若仍活动），使当前 generation 失效但保持 fence（phase 设为 FAILED），
	 * 并断开连接显示中文原因。fence 保持到 Channel 真正关闭，避免连接关闭前旧 PLAY 包再次
	 * 进入原版编码器报错。
	 */
	private static void failReset(
			Connection connection,
			long generation,
			String translationKey,
			Throwable error
	) {
		ServerSwitchPreparationOverlay.cancel(generation);
		logger.error(
				RESETMARKER,
				"Reset failed generation={} channel={}",
				generation,
				connection.channel().id().asLongText(),
				error
		);
		ResetConnectionState.fail(connection.channel());
		lastStatusKey = null;
		connection.disconnect(Component.translatable(translationKey));
	}

	/**
	 * 向玩家显示当前阶段提示（异步调度版）。
	 *
	 * <p>所有 UI 操作必须在 Minecraft 主线程执行；若从 Netty 线程调用，通过
	 * {@code Minecraft.getInstance().execute(...)} 调度。只在阶段变化时更新一次，
	 * 不在每个握手包到达时重复创建屏幕。
	 */
	@OnlyIn(Dist.CLIENT)
	private static void showStatus(String translationKey) {
		Minecraft.getInstance().execute(
				() -> showStatusNow(translationKey)
		);
	}

	/**
	 * 在 Minecraft 主线程内同步显示提示。
	 *
	 * <p>调用方必须已处于 Minecraft 主线程（如 {@code enqueueClear} 内部），
	 * 不需要再次排队。只在阶段变化时更新一次。
	 */
	@OnlyIn(Dist.CLIENT)
	private static void showStatusNow(String translationKey) {
		if (translationKey.equals(lastStatusKey)) {
			return;
		}
		lastStatusKey = translationKey;
		Minecraft.getInstance().setScreen(
				new GenericDirtMessageScreen(Component.translatable(translationKey))
		);
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
