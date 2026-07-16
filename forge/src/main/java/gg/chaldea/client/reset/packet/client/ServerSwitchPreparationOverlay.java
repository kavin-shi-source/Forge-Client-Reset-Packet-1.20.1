package gg.chaldea.client.reset.packet.client;

import gg.chaldea.client.reset.packet.ClientReset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在旧世界画面上绘制跨服准备提示，并确保提示至少渲染一帧后再开始清理。
 */
@Mod.EventBusSubscriber(
        modid = ClientReset.MODID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class ServerSwitchPreparationOverlay {

    private static final long FALLBACK_DELAY_MS = 1_000L;

    private static volatile State state;

    private ServerSwitchPreparationOverlay() {}

    /**
     * 开启跨服准备 HUD。
     *
     * @param generation 当前 FCRP reset generation
     * @param startClear HUD 首帧完成后执行的旧世界清理入口
     */
    public static void begin(long generation, Runnable startClear) {
        Minecraft minecraft = Minecraft.getInstance();

        minecraft.execute(() -> {
            State previous = state;
            if (previous != null) {
                previous.cancelled.set(true);
            }

            State next = new State(
                    generation,
                    System.currentTimeMillis(),
                    startClear
            );
            state = next;

            // 窗口最小化、后台限帧或渲染异常时，不能永久阻塞切服。
            Thread fallbackThread = new Thread(
                    () -> runFallback(next),
                    "FCRP-Preparation-HUD-Fallback"
            );
            fallbackThread.setDaemon(true);
            fallbackThread.start();
        });
    }

    /**
     * 取消指定 generation 的 HUD。
     */
    public static void cancel(long generation) {
        Minecraft.getInstance().execute(() -> {
            State current = state;
            if (current == null || current.generation != generation) {
                return;
            }

            current.cancelled.set(true);
            state = null;
        });
    }

    /**
     * 当前 HUD 是否处于活动状态。
     */
    public static boolean isActive() {
        State current = state;
        return current != null && !current.cancelled.get();
    }

    @SubscribeEvent
    public static void onRenderGuiOverlay(RenderGuiOverlayEvent.Post event) {
        State current = state;
        if (current == null || current.cancelled.get()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }

        render(
                event.getGuiGraphics(),
                minecraft.font,
                event.getWindow().getGuiScaledWidth(),
                event.getWindow().getGuiScaledHeight()
        );

        current.firstFrameRendered.set(true);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        State current = state;
        if (current == null
                || current.cancelled.get()
                || !current.firstFrameRendered.get()) {
            return;
        }

        startClearOnce(current, "first_rendered_frame");
    }

    private static void render(
            GuiGraphics guiGraphics,
            Font font,
            int screenWidth,
            int screenHeight
    ) {
        Component title = Component.translatable(
                "clientresetpacket.overlay.preparing"
        );
        Component subtitle = Component.translatable(
                "clientresetpacket.overlay.warning"
        );

        int titleWidth = font.width(title);
        int subtitleWidth = font.width(subtitle);
        int contentWidth = Math.max(titleWidth, subtitleWidth);

        int centerX = screenWidth / 2;
        int centerY = screenHeight / 2;
        int paddingX = 14;
        int paddingY = 10;

        int left = centerX - contentWidth / 2 - paddingX;
        int right = centerX + contentWidth / 2 + paddingX;
        int top = centerY - 18 - paddingY;
        int bottom = centerY + 15 + paddingY;

        // 半透明背景确保文字在亮色场景中仍然清晰。
        guiGraphics.fill(
                left,
                top,
                right,
                bottom,
                0xB0000000
        );

        // 1.20.1 Api 适配：Font.drawShadow(PoseStack,...) 已迁移至 GuiGraphics.drawString，
        // 且坐标参数由 float 改为 int。最后一个 boolean 参数表示启用阴影，等价于旧的 drawShadow。
        guiGraphics.drawString(
                font,
                title,
                centerX - titleWidth / 2,
                centerY - 14,
                0xFFFFFF,
                true
        );

        guiGraphics.drawString(
                font,
                subtitle,
                centerX - subtitleWidth / 2,
                centerY + 3,
                0xD0D0D0,
                true
        );
    }

    private static void runFallback(State expected) {
        try {
            Thread.sleep(FALLBACK_DELAY_MS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return;
        }

        Minecraft.getInstance().execute(() -> {
            State current = state;
            if (current != expected || current.cancelled.get()) {
                return;
            }

            ClientReset.logger.warn(
                    ClientReset.RESETMARKER,
                    "Preparation HUD did not confirm a rendered frame within {} ms; "
                            + "starting reset cleanup through fallback "
                            + "(generation={})",
                    FALLBACK_DELAY_MS,
                    current.generation
            );

            startClearOnce(current, "render_fallback_timeout");
        });
    }

    private static void startClearOnce(State expected, String reason) {
        State current = state;
        if (current != expected
                || current.cancelled.get()
                || !current.clearStarted.compareAndSet(false, true)) {
            return;
        }

        state = null;

        ClientReset.logger.info(
                ClientReset.RESETMARKER,
                "Starting old-world cleanup after preparation HUD "
                        + "(generation={}, reason={}, elapsed={}ms)",
                current.generation,
                reason,
                System.currentTimeMillis() - current.startedAt
        );

        current.startClear.run();
    }

    private static final class State {

        private final long generation;
        private final long startedAt;
        private final Runnable startClear;
        private final AtomicBoolean firstFrameRendered =
                new AtomicBoolean(false);
        private final AtomicBoolean clearStarted =
                new AtomicBoolean(false);
        private final AtomicBoolean cancelled =
                new AtomicBoolean(false);

        private State(
                long generation,
                long startedAt,
                Runnable startClear
        ) {
            this.generation = generation;
            this.startedAt = startedAt;
            this.startClear = startClear;
        }
    }
}
