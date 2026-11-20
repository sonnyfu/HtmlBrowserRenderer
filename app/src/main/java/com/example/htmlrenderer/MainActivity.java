package com.example.htmlrenderer;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 极简 HTML 即时渲染器 (支持本地回环 HTTP 渲染 & FileProvider 外部浏览器打开)
 *
 * 核心解决痛点：
 * Android 7.0+ 限制了 file:// URI 暴露，且许多现代手机浏览器（如 Chrome）在通过 content:// 打开本地 HTML 时，
 * 会出于安全策略禁用外部 JS/CSS 或直接将其作为纯文本/下载项处理。
 *
 * 本方案同时提供两种一键渲染模式：
 * 1. 【推荐】内置轻量级本地 HTTP 服务 (http://127.0.0.1:端口/index.html) 并拉起系统默认浏览器，
 *    100% 还原真实 Web 环境的 JS、CSS、Canvas、Fetch 等全部特性。
 * 2. 【备用】通过 FileProvider (content://) 生成临时文件并直接调用系统默认浏览器或 HTML 查看器。
 */
public class MainActivity extends AppCompatActivity {

    private EditText htmlInput;
    private LocalHttpServer httpServer;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final String DEFAULT_SAMPLE_HTML = "<!DOCTYPE html>\n"
            + "<html lang=\"zh-CN\">\n"
            + "<head>\n"
            + "  <meta charset=\"UTF-8\">\n"
            + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
            + "  <title>HTML 即时预览</title>\n"
            + "  <style>\n"
            + "    body { font-family: system-ui, sans-serif; padding: 24px; background: #f8fafc; color: #0f172a; }\n"
            + "    .card { background: white; padding: 20px; border-radius: 12px; box-shadow: 0 4px 12px rgba(0,0,0,0.08); }\n"
            + "    button { background: #2563eb; color: white; border: none; padding: 10px 18px; border-radius: 8px; font-size: 16px; }\n"
            + "  </style>\n"
            + "</head>\n"
            + "<body>\n"
            + "  <div class=\"card\">\n"
            + "    <h2>Hello Android 默认浏览器！</h2>\n"
            + "    <p>当前渲染时间：<span id=\"time\"></span></p>\n"
            + "    <button onclick=\"alert('JavaScript 运行正常！')\">测试 JS 交互</button>\n"
            + "  </div>\n"
            + "  <script>\n"
            + "    document.getElementById('time').innerText = new Date().toLocaleString();\n"
            + "  </script>\n"
            + "</body>\n"
            + "</html>";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 使用纯代码构建 UI，免去额外配置 activity_main.xml 的繁琐步骤，单文件即可直接运行
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp2px(16);
        root.setPadding(pad, pad, pad, pad);

        TextView titleView = new TextView(this);
        titleView.setText("HTML 默认浏览器即时渲染器");
        titleView.setTextSize(20f);
        titleView.getPaint().setFakeBoldText(true);
        root.addView(titleView);

        // 顶部快捷操作栏：粘贴剪贴板 / 清空
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        barParams.topMargin = dp2px(12);
        topBar.setLayoutParams(barParams);

        Button btnPasteRender = new Button(this);
        btnPasteRender.setText("粘贴并立即用浏览器渲染");
        LinearLayout.LayoutParams weightParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnPasteRender.setLayoutParams(weightParams);

        Button btnClear = new Button(this);
        btnClear.setText("清空");
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clearParams.leftMargin = dp2px(8);
        btnClear.setLayoutParams(clearParams);

        topBar.addView(btnPasteRender);
        topBar.addView(btnClear);
        root.addView(topBar);

        // HTML 代码输入框
        htmlInput = new EditText(this);
        htmlInput.setHint("在此粘贴或输入 HTML 代码...");
        htmlInput.setText(DEFAULT_SAMPLE_HTML);
        htmlInput.setMinLines(14);
        htmlInput.setTextSize(13f);
        htmlInput.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        inputParams.topMargin = dp2px(12);
        inputParams.bottomMargin = dp2px(12);
        htmlInput.setLayoutParams(inputParams);
        root.addView(htmlInput);

        // 底部主渲染按钮（HTTP 方式，支持完整 JS/CSS 与默认浏览器）
        Button btnRenderHttp = new Button(this);
        btnRenderHttp.setText("用默认浏览器渲染 (本地 HTTP 模式 · 推荐)");
        root.addView(btnRenderHttp);

        // 底部备用渲染按钮（FileProvider content:// 方式）
        Button btnRenderFile = new Button(this);
        btnRenderFile.setText("用外部查看器打开 (FileProvider 文件模式)");
        LinearLayout.LayoutParams fileBtnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fileBtnParams.topMargin = dp2px(8);
        btnRenderFile.setLayoutParams(fileBtnParams);
        root.addView(btnRenderFile);

        scrollView.addView(root);
        setContentView(scrollView);

        // 启动本地轻量 HTTP 服务
        httpServer = new LocalHttpServer();
        httpServer.start();

        // 按钮事件绑定
        btnPasteRender.setOnClickListener(v -> {
            String clipText = readClipboard();
            if (!TextUtils.isEmpty(clipText)) {
                htmlInput.setText(clipText);
                renderInDefaultBrowserHttp(clipText);
            } else {
                Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show();
            }
        });

        btnClear.setOnClickListener(v -> htmlInput.setText(""));

        btnRenderHttp.setOnClickListener(v -> {
            String html = htmlInput.getText().toString();
            renderInDefaultBrowserHttp(html);
        });

        btnRenderFile.setOnClickListener(v -> {
            String html = htmlInput.getText().toString();
            renderViaFileProvider(html);
        });

        // 支持从其他 App 通过「分享 (ACTION_SEND)」直接传入 HTML 文本并立即用默认浏览器打开
        handleIncomingShareIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingShareIntent(intent);
    }

    private void handleIncomingShareIntent(Intent intent) {
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (!TextUtils.isEmpty(sharedText)) {
                htmlInput.setText(sharedText);
                renderInDefaultBrowserHttp(sharedText);
            }
        }
    }

    /**
     * 方式一（推荐）：更新本地 127.0.0.1 HTTP 服务的 HTML 内容，并调用系统默认浏览器打开。
     * 完美支持现代浏览器完整 JS 执行、DOM 操作、自适应 Viewport。
     */
    private void renderInDefaultBrowserHttp(String htmlContent) {
        if (TextUtils.isEmpty(htmlContent.trim())) {
            Toast.makeText(this, "HTML 内容不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        String normalizedHtml = ensureHtmlDocument(htmlContent);
        httpServer.updateHtmlContent(normalizedHtml);

        String url = "http://127.0.0.1:" + httpServer.getPort() + "/render_" + System.currentTimeMillis() + ".html";
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(browserIntent);
        } catch (Exception e) {
            Toast.makeText(this, "无法拉起默认浏览器: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 方式二（备用）：将 HTML 写入缓存目录并通过 FileProvider (content://) 唤起外部应用。
     * 需在 AndroidManifest.xml 中配置 FileProvider。
     */
    private void renderViaFileProvider(String htmlContent) {
        if (TextUtils.isEmpty(htmlContent.trim())) {
            Toast.makeText(this, "HTML 内容不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        executor.execute(() -> {
            try {
                File htmlDir = new File(getCacheDir(), "html_preview");
                if (!htmlDir.exists()) {
                    htmlDir.mkdirs();
                }
                File htmlFile = new File(htmlDir, "preview.html");
                try (FileOutputStream fos = new FileOutputStream(htmlFile, false)) {
                    fos.write(ensureHtmlDocument(htmlContent).getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                }

                Uri contentUri = FileProvider.getUriForFile(
                        MainActivity.this,
                        getPackageName() + ".fileprovider",
                        htmlFile
                );

                Intent viewIntent = new Intent(Intent.ACTION_VIEW);
                viewIntent.setDataAndType(contentUri, "text/html");
                viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);

                mainHandler.post(() -> {
                    try {
                        startActivity(Intent.createChooser(viewIntent, "选择浏览器渲染 HTML"));
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "没有找到支持打开 HTML 文件的应用", Toast.LENGTH_LONG).show();
                    }
                });
            } catch (IOException e) {
                mainHandler.post(() ->
                        Toast.makeText(MainActivity.this, "写入临时文件失败: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
            }
        });
    }

    /**
     * 若用户只传入了零散 HTML 片段（如一段 <div>...</div>），自动补全 UTF-8 与移动端 Viewport 头部，防止中文乱码和缩放异常。
     */
    private String ensureHtmlDocument(String raw) {
        String lower = raw.trim().toLowerCase();
        if (lower.startsWith("<!doctype") || lower.startsWith("<html")) {
            return raw;
        }
        return "<!DOCTYPE html>\n<html>\n<head>\n"
                + "<meta charset=\"UTF-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
                + "</head>\n<body>\n"
                + raw
                + "\n</body>\n</html>";
    }

    private String readClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip()) {
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence text = clip.getItemAt(0).coerceToText(this);
                return text != null ? text.toString() : "";
            }
        }
        return "";
    }

    private int dp2px(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (httpServer != null) {
            httpServer.stop();
        }
        executor.shutdownNow();
    }

    /**
     * 极简本地回环 HTTP 服务器（仅绑定 127.0.0.1，安全且无需申请外部网络暴露）
     */
    private static class LocalHttpServer {
        private ServerSocket serverSocket;
        private volatile boolean running = false;
        private volatile String currentHtml = "<h1>Ready</h1>";
        private int port = 0;

        public void updateHtmlContent(String html) {
            this.currentHtml = html;
        }

        public int getPort() {
            return port;
        }

        public void start() {
            running = true;
            new Thread(() -> {
                try {
                    serverSocket = new ServerSocket();
                    // 仅监听本地回环地址 127.0.0.1，端口设为 0 由系统分配空闲端口
                    serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
                    port = serverSocket.getLocalPort();

                    while (running && !serverSocket.isClosed()) {
                        Socket client = serverSocket.accept();
                        handleClient(client);
                    }
                } catch (IOException ignored) {
                }
            }, "LocalHtmlHttpServer").start();
        }

        private void handleClient(Socket client) {
            try (Socket s = client;
                 BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                 OutputStream out = s.getOutputStream()) {

                // 读取请求首行
                String line = reader.readLine();
                if (line == null) return;

                byte[] bodyBytes = currentHtml.getBytes(StandardCharsets.UTF_8);
                String headers = "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: text/html; charset=utf-8\r\n"
                        + "Content-Length: " + bodyBytes.length + "\r\n"
                        + "Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r\n"
                        + "Connection: close\r\n\r\n";

                out.write(headers.getBytes(StandardCharsets.UTF_8));
                out.write(bodyBytes);
                out.flush();
            } catch (IOException ignored) {
            }
        }

        public void stop() {
            running = false;
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
