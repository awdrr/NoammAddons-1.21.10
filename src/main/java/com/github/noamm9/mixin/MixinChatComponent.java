package com.github.noamm9.mixin;

import com.github.noamm9.interfaces.IChatComponent;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

import static com.github.noamm9.NoammAddons.mc;

@Mixin(ChatComponent.class)
public abstract class MixinChatComponent implements IChatComponent {

    @Shadow @Final private List<GuiMessage.Line> trimmedMessages;

    @Shadow private int chatScrollbarPos;

    @Shadow @Final private static int BOTTOM_MARGIN;

    @Shadow
    protected abstract double getScale();

    @Shadow
    protected abstract int getLineHeight();

    @Shadow
    protected abstract int getWidth();

    @Shadow
    protected abstract boolean isChatHidden();

    @Shadow
    public abstract boolean isChatFocused();

    @Shadow
    public abstract int getLinesPerPage();

    // 1.21.11 removed screenToChatX/screenToChatY/getMessageLineIndexAt, these follow the 1.21.10 versions

    @Override
    public double getMouseXtoChatX() {
        return mc.mouseHandler.getScaledXPos(mc.getWindow()) / getScale() - 4.0;
    }

    @Override
    public double getMouseYtoChatY() {
        double y = mc.getWindow().getGuiScaledHeight() - mc.mouseHandler.getScaledYPos(mc.getWindow()) - BOTTOM_MARGIN;
        return y / (getScale() * getLineHeight());
    }

    @Override
    public double getLineIndex(double x, double y) {
        if (!isChatFocused() || isChatHidden()) return -1;
        if (x < -4.0 || x > Mth.floor(getWidth() / getScale())) return -1;

        int lines = Math.min(getLinesPerPage(), trimmedMessages.size());
        if (y < 0.0 || y >= lines) return -1;

        int index = Mth.floor(y + chatScrollbarPos);
        return index >= 0 && index < trimmedMessages.size() ? index : -1;
    }

    @Override
    public List<GuiMessage.Line> getVisibleMessages() {
        return this.trimmedMessages;
    }

    @Inject(method = "clearMessages", at = @At("HEAD"), cancellable = true)
    private void clearMessages(CallbackInfo ci) {
        ci.cancel();
    }
}
