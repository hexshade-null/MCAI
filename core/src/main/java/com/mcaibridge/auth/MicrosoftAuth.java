package com.mcaibridge.auth;

import net.lenni0451.commons.httpclient.HttpClient;
import net.raphimc.minecraftauth.MinecraftAuth;
import net.raphimc.minecraftauth.java.JavaAuthManager;
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;
import net.raphimc.minecraftauth.msa.service.impl.DeviceCodeMsaAuthService;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.datatransfer.StringSelection;
import java.awt.Toolkit;

/**
 * 微软登录：设备码流程。弹窗显示验证链接和用户代码，登录成功后产出在线认证协议。
 * API（MinecraftAuth 5.x，已用 javap 校准）：
 * JavaAuthManager.create(httpClient).login((hc, appConfig) -> new DeviceCodeMsaAuthService(hc, appConfig, deviceCodeConsumer))
 */
public final class MicrosoftAuth {
    private static final Logger log = LoggerFactory.getLogger(MicrosoftAuth.class);

    /** 设备码回调：收到链接与代码时触发（用于弹窗展示）。 */
    public interface DeviceCodeListener {
        void onDeviceCode(String verificationUri, String userCode, String directVerificationUri);
    }

    private MicrosoftAuth() {
    }

    public static AuthResult login(DeviceCodeListener listener) throws Exception {
        return login(listener, null);
    }

    /**
     * 带令牌持久化的登录：tokenFile 存在且有效 → 直接恢复会话（不弹设备码，自动刷新过期令牌）；
     * 否则走设备码流程，成功后把整条认证链（含 refresh token）保存到 tokenFile。
     */
    public static AuthResult login(DeviceCodeListener listener, java.nio.file.Path tokenFile) throws Exception {
        if (tokenFile != null && java.nio.file.Files.exists(tokenFile)) {
            try {
                com.google.gson.JsonObject saved = com.google.gson.JsonParser
                        .parseString(java.nio.file.Files.readString(tokenFile)).getAsJsonObject();
                JavaAuthManager restored = JavaAuthManager.fromJson(MinecraftAuth.createHttpClient(), saved);
                AuthResult result = build(restored);
                log.info("已从本地令牌恢复微软登录会话: {}（无需再次设备码验证）", result.profile().getName());
                return result;
            } catch (Exception e) {
                log.warn("本地令牌恢复失败（{}），转设备码登录", e.toString());
            }
        }

        JavaAuthManager authManager = JavaAuthManager.create(MinecraftAuth.createHttpClient())
                .login((HttpClient httpClient, net.raphimc.minecraftauth.msa.model.MsaApplicationConfig appConfig) ->
                        new DeviceCodeMsaAuthService(httpClient, appConfig, (MsaDeviceCode deviceCode) -> {
                            String uri = deviceCode.getVerificationUri();
                            String code = deviceCode.getUserCode();
                            String direct = deviceCode.getDirectVerificationUri();
                            log.info("微软设备码：打开 {} 并输入代码 {}", uri, code);
                            if (listener != null) listener.onDeviceCode(uri, code, direct);
                        }));

        AuthResult result = build(authManager);
        if (tokenFile != null) saveToken(authManager, tokenFile);
        return result;
    }

    private static AuthResult build(JavaAuthManager authManager) throws Exception {
        var mcProfile = authManager.getMinecraftProfile().getUpToDate();
        var mcToken = authManager.getMinecraftToken().getUpToDate();
        GameProfile profile = new GameProfile(mcProfile.getId(), mcProfile.getName());
        String token = mcToken.getToken();
        return new AuthResult("microsoft", profile, token, new MinecraftProtocol(profile, token));
    }

    private static void saveToken(JavaAuthManager authManager, java.nio.file.Path tokenFile) {
        try {
            java.nio.file.Files.createDirectories(tokenFile.getParent());
            java.nio.file.Files.writeString(tokenFile, JavaAuthManager.toJson(authManager).toString());
            log.info("微软登录令牌已保存，下次启动自动恢复: {}", tokenFile);
        } catch (Exception e) {
            log.warn("微软登录令牌保存失败: {}", e.toString());
        }
    }

    /** Swing 弹窗：展示验证链接 + 代码，带“复制代码/直接打开”按钮。 */
    public static void showDeviceCodeDialog(String verificationUri, String userCode, String directUri) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> showDeviceCodeDialog(verificationUri, userCode, directUri));
            return;
        }
        JDialog dialog = new JDialog();
        dialog.setTitle("MCAI Bridge - 微软登录");
        dialog.setLayout(new BorderLayout(10, 10));
        JLabel label = new JLabel("<html><body style='width:320px'>"
                + "1. 浏览器打开: <b>" + verificationUri + "</b><br>"
                + "2. 输入代码: <b style='font-size:16px'>" + userCode + "</b></body></html>");
        dialog.add(label, BorderLayout.CENTER);
        JButton copy = new JButton("复制代码");
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(userCode), null));
        JButton direct = new JButton("直接打开");
        direct.addActionListener(e -> {
            try {
                java.awt.Desktop.getDesktop().browse(new java.net.URI(directUri));
            } catch (Exception ex) {
                log.warn("无法打开浏览器: {}", ex.toString());
            }
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(copy);
        buttons.add(direct);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setModal(false);
        dialog.setVisible(true);
    }
}
