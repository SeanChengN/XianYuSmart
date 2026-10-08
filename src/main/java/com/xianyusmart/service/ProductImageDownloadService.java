package com.xianyusmart.service;

import com.xianyusmart.service.notification.WebhookSecurity;
import okhttp3.CookieJar;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Service
public class ProductImageDownloadService {
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    // Dedicated client: no account credentials, redirects, proxies or automatic retries.
    private final OkHttpClient client = new OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout(Duration.ofSeconds(15))
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(15))
            .dns(host -> {
                try {
                    // The connection uses this validated address; TLS still verifies the hostname.
                    return List.of(WebhookSecurity.resolveSafeTarget(URI.create("https://" + host)).address());
                } catch (IllegalArgumentException e) {
                    throw new UnknownHostException("图片地址无法解析到允许的公网地址");
                }
            })
            .build();

    public Download download(String url) {
        URI uri;
        try {
            if (url == null || url.length() > 8192) throw new IllegalArgumentException();
            uri = URI.create(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean allowed = List.of("alicdn.com", "tbcdn.cn").stream()
                    .anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !allowed
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("仅支持 HTTPS 闲鱼图片 CDN 地址，其他来源请打开图片后保存");
        }
        Request request = new Request.Builder().url(uri.toASCIIString())
                .header("Accept", "image/jpeg,image/png,image/webp,image/gif")
                .header("User-Agent", "XianYuSmart-ImageDownload/2.1")
                .get().build();
        try (Response response = client.newCall(request).execute()) {
            if (response.code() != 200) {
                throw new IllegalStateException("图片服务器拒绝下载（HTTP " + response.code() + "），可打开图片后保存");
            }
            ResponseBody body = response.body();
            if (body == null) throw new IllegalStateException("图片服务器未返回图片");
            if (body.contentLength() > MAX_BYTES) throw new IllegalArgumentException("图片不能超过 10MB");
            String type = response.header("Content-Type", "").split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if ("image/jpg".equals(type)) type = "image/jpeg";
            if (!List.of("image/jpeg", "image/png", "image/webp", "image/gif").contains(type)) {
                throw new IllegalArgumentException("仅支持 JPEG、PNG、WebP 和 GIF 图片");
            }
            byte[] bytes = body.byteStream().readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("图片不能超过 10MB");
            String extension = detectExtension(bytes);
            String detected = switch (extension) {
                case "jpg" -> "image/jpeg";
                case "png" -> "image/png";
                case "webp" -> "image/webp";
                case "gif" -> "image/gif";
                default -> "";
            };
            if (!type.equals(detected)) throw new IllegalArgumentException("服务器返回的内容不是有效的支持图片");
            return new Download(bytes, type, extension);
        } catch (IOException e) {
            throw new IllegalStateException("图片下载失败或超时，请稍后手动重试，也可打开图片后保存");
        }
    }

    private String detectExtension(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255) return "jpg";
        if (bytes.length >= 8 && Arrays.equals(Arrays.copyOf(bytes, 8), new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10})) return "png";
        if (bytes.length >= 6 && (prefix(bytes, "GIF87a", 0) || prefix(bytes, "GIF89a", 0))) return "gif";
        if (bytes.length >= 12 && prefix(bytes, "RIFF", 0) && prefix(bytes, "WEBP", 8)) return "webp";
        return "";
    }

    private boolean prefix(byte[] bytes, String text, int offset) {
        for (int i = 0; i < text.length(); i++) if (bytes[offset + i] != text.charAt(i)) return false;
        return true;
    }

    public record Download(byte[] bytes, String contentType, String extension) { }
}
