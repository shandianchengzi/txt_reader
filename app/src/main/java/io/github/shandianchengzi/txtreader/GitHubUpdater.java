package io.github.shandianchengzi.txtreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Manual GitHub Release updater; never installs silently or trusts a checksum alone. */
final class GitHubUpdater {
    private static final String API =
            "https://api.github.com/repos/shandianchengzi/txt_reader/releases/latest";
    private static final String RELEASE_PATH =
            "https://github.com/shandianchengzi/txt_reader/releases/download/";
    // The certificate already pinned by the GitHub Actions release workflow.
    private static final String SIGNING_CERT_SHA256 =
            "558a589e18e4c93268232b7df46283085019ec68087b09d1bece5b67f9ca6866";
    private static final int MAX_APK_BYTES = 80 * 1024 * 1024;
    private static final String APK_MIME = "application/vnd.android.package-archive";

    private final Activity activity;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;
    private boolean busy;
    private boolean awaitingInstallerPermission;

    private static final class Release {
        final String tag, notes, apkName, apkUrl, shaUrl, pageUrl;
        final long size;
        Release(String tag, String notes, String apkName,
                String apkUrl, String shaUrl, String pageUrl, long size) {
            this.tag = tag;
            this.notes = notes;
            this.apkName = apkName;
            this.apkUrl = apkUrl;
            this.shaUrl = shaUrl;
            this.pageUrl = pageUrl;
            this.size = size;
        }
    }

    GitHubUpdater(Activity activity) { this.activity = activity; }

    private boolean alive() {
        return !activity.isFinishing()
                && (Build.VERSION.SDK_INT < 17 || !activity.isDestroyed());
    }
    private void onUi(Runnable action) {
        ui.post(() -> { if (alive()) action.run(); });
    }
    private void error(String prefix, Exception e) {
        busy = false;
        if (cancelled) return;
        new AlertDialog.Builder(activity).setTitle(prefix)
                .setMessage(e.getMessage() == null ? e.toString() : e.getMessage())
                .setPositiveButton("确定", null).show();
    }

    void checkForUpdates() {
        if (busy) return;
        busy = true;
        cancelled = false;
        AlertDialog checking = new AlertDialog.Builder(activity)
                .setTitle("检查更新").setMessage("正在查询官方 GitHub Release…")
                .setNegativeButton("取消", (d, w) -> cancelled = true)
                .setCancelable(false).create();
        checking.show();
        new Thread(() -> {
            try {
                Release remote = findLatest();
                String installed = activity.getPackageManager()
                        .getPackageInfo(activity.getPackageName(), 0).versionName;
                boolean newer = compareVersion(remote.tag, installed) > 0;
                onUi(() -> {
                    checking.dismiss();
                    if (cancelled) { busy = false; return; }
                    busy = false;
                    if (newer) showUpdate(remote, installed);
                    else new AlertDialog.Builder(activity).setTitle("已经是最新版本")
                            .setMessage("当前版本：" + installed + "\n最新正式版：" + remote.tag)
                            .setPositiveButton("确定", null).show();
                });
            } catch (Exception e) {
                onUi(() -> { checking.dismiss(); error("检查更新失败", e); });
            }
        }, "TXT GitHub release check").start();
    }

    private static int[] parseVersion(String text) throws Exception {
        String normalized = text.startsWith("v") || text.startsWith("V")
                ? text.substring(1) : text;
        if (!normalized.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))
            throw new Exception("不支持的发布版本号：" + text);
        String[] parts = normalized.split("\\.");
        return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2])};
    }
    private static int compareVersion(String a, String b) throws Exception {
        int[] aa = parseVersion(a), bb = parseVersion(b);
        for (int i = 0; i < 3; i++) {
            int result = Integer.compare(aa[i], bb[i]);
            if (result != 0) return result;
        }
        return 0;
    }
    private static String officialAsset(String url, String name) throws Exception {
        if (url == null || !url.startsWith(RELEASE_PATH)
                || !url.endsWith("/" + name))
            throw new Exception("GitHub Release 附件地址不符合官方仓库格式");
        return url;
    }
    private Release findLatest() throws Exception {
        JSONObject json = new JSONObject(new String(fetchSmall(API, 512 * 1024),
                StandardCharsets.UTF_8));
        if (json.optBoolean("draft") || json.optBoolean("prerelease"))
            throw new Exception("GitHub 返回了非正式版本");
        String tag = json.getString("tag_name");
        parseVersion(tag);
        String apkName = "txt-reader-" + tag + ".apk";
        String apkUrl = null, shaUrl = null;
        long size = 0;
        JSONArray assets = json.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            String name = asset.optString("name");
            if (apkName.equals(name)) {
                apkUrl = officialAsset(asset.getString("browser_download_url"), name);
                size = asset.optLong("size", 0);
            } else if ("SHA256SUMS.txt".equals(name)) {
                shaUrl = officialAsset(asset.getString("browser_download_url"), name);
            }
        }
        if (apkUrl == null || shaUrl == null || size <= 0 || size > MAX_APK_BYTES)
            throw new Exception("最新版本缺少 APK 或 SHA256SUMS.txt，或 APK 大小异常");
        String page = json.optString("html_url", "");
        if (!page.startsWith("https://github.com/shandianchengzi/txt_reader/releases/tag/"))
            throw new Exception("发布页不是官方仓库");
        return new Release(tag, json.optString("body", ""), apkName,
                apkUrl, shaUrl, page, size);
    }
    private static HttpURLConnection connect(String address) throws Exception {
        URL url = new URL(address);
        if (!"https".equalsIgnoreCase(url.getProtocol()))
            throw new Exception("拒绝非 HTTPS 更新连接");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "TXTReader-Android-Update");
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        if (conn.getResponseCode() != 200) {
            int status = conn.getResponseCode();
            conn.disconnect();
            throw new Exception("GitHub 网络响应异常（HTTP " + status + "）");
        }
        return conn;
    }
    private byte[] fetchSmall(String url, int maximum) throws Exception {
        HttpURLConnection conn = connect(url);
        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (cancelled) throw new Exception("已取消");
                if (out.size() + count > maximum) throw new Exception("服务器返回的数据过大");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        } finally { conn.disconnect(); }
    }
    private void showUpdate(Release remote, String installed) {
        String notes = remote.notes.length() > 600
                ? remote.notes.substring(0, 600) + "…" : remote.notes;
        new AlertDialog.Builder(activity).setTitle("发现新版本 " + remote.tag)
                .setMessage("当前版本：" + installed + "\n最新版本：" + remote.tag
                        + (notes.isEmpty() ? "" : "\n\n更新说明：\n" + notes)
                        + "\n\n将从官方 GitHub 下载 APK，校验 SHA-256 和发布签名后交给系统安装。")
                .setPositiveButton("下载并安装", (d, w) -> downloadAndInstall(remote))
                .setNeutralButton("查看发布页", (d, w) -> {
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(remote.pageUrl)));
                })
                .setNegativeButton("取消", null).show();
    }
    private File apk() {
        return new File(new File(activity.getCacheDir(), "updates"), "release.apk");
    }
    private static String hex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(alphabet[(b >>> 4) & 15]).append(alphabet[b & 15]);
        }
        return out.toString();
    }
    private static String manifestDigest(String sha, String expectedName) throws Exception {
        String answer = null;
        for (String line : sha.split("\\r?\\n")) {
            String[] pair = line.trim().split("\\s+", 2);
            if (pair.length != 2) continue;
            String name = pair[1].trim();
            if (name.startsWith("*")) name = name.substring(1);
            if (!expectedName.equals(name)) continue;
            if (answer != null || !pair[0].matches("[0-9a-fA-F]{64}"))
                throw new Exception("SHA256SUMS.txt 格式异常或出现重复条目");
            answer = pair[0].toLowerCase(Locale.ROOT);
        }
        if (answer == null) throw new Exception("SHA256SUMS.txt 未包含对应 APK");
        return answer;
    }
    private String downloadApk(Release remote, File target, AlertDialog progress)
            throws Exception {
        File parent = target.getParentFile();
        if (!parent.exists() && !parent.mkdirs())
            throw new Exception("无法创建应用更新缓存目录");
        File partial = new File(parent, "release.apk.part");
        if (partial.exists()) partial.delete();
        try {
            HttpURLConnection conn = connect(remote.apkUrl);
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(partial)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[16384];
                long copied = 0, previousProgress = -1;
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (cancelled) throw new Exception("已取消");
                    copied += count;
                    if (copied > MAX_APK_BYTES || copied > remote.size)
                        throw new Exception("APK 下载长度超过发布信息，已中止");
                    out.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    int pct = (int) (copied * 100 / remote.size);
                    if (pct / 5 != previousProgress / 5) {
                        previousProgress = pct;
                        final int value = pct;
                        onUi(() -> progress.setMessage("正在下载 APK：" + value + "%"));
                    }
                }
                if (copied != remote.size) throw new Exception("APK 下载不完整");
                out.getFD().sync();
                return hex(digest.digest());
            } finally { conn.disconnect(); }
        } catch (Exception failure) {
            partial.delete();
            throw failure;
        }
    }
    private void verifyApk(File path, Release remote) throws Exception {
        PackageManager manager = activity.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo pkg = manager.getPackageArchiveInfo(path.getAbsolutePath(), flags);
        if (pkg == null || !activity.getPackageName().equals(pkg.packageName))
            throw new Exception("安装包的应用 ID 不一致，已拒绝安装");
        String expectedVersion = remote.tag.substring(1);
        if (!expectedVersion.equals(pkg.versionName))
            throw new Exception("APK 版本号与 GitHub Release 不一致");
        PackageInfo installed = manager.getPackageInfo(activity.getPackageName(), 0);
        long newCode = Build.VERSION.SDK_INT >= 28 ? pkg.getLongVersionCode() : pkg.versionCode;
        long oldCode = Build.VERSION.SDK_INT >= 28
                ? installed.getLongVersionCode() : installed.versionCode;
        if (newCode <= oldCode) throw new Exception("安装包版本号并未高于当前版本");
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28) {
            if (pkg.signingInfo == null || pkg.signingInfo.hasMultipleSigners())
                throw new Exception("安装包签名信息不符合预期");
            signatures = pkg.signingInfo.getApkContentsSigners();
        } else signatures = pkg.signatures;
        if (signatures == null || signatures.length != 1)
            throw new Exception("无法验证安装包签名证书");
        String fingerprint = hex(MessageDigest.getInstance("SHA-256")
                .digest(signatures[0].toByteArray()));
        if (!SIGNING_CERT_SHA256.equals(fingerprint))
            throw new Exception("签名证书与官方固定签名不一致，已拒绝安装");
    }
    private void downloadAndInstall(Release remote) {
        if (busy) return;
        busy = true;
        cancelled = false;
        AlertDialog progress = new AlertDialog.Builder(activity)
                .setTitle("下载更新").setMessage("正在从 GitHub 下载校验文件…")
                .setNegativeButton("取消", (d, w) -> cancelled = true)
                .setCancelable(false).create();
        progress.show();
        new Thread(() -> {
            File target = apk();
            File partial = new File(target.getParentFile(), "release.apk.part");
            try {
                String expected = manifestDigest(new String(
                        fetchSmall(remote.shaUrl, 64 * 1024), StandardCharsets.UTF_8),
                        remote.apkName);
                String actual = downloadApk(remote, target, progress);
                if (!expected.equals(actual)) throw new Exception("SHA-256 不匹配，拒绝安装");
                if (cancelled) throw new Exception("已取消");
                verifyApk(partial, remote);
                if (target.exists() && !target.delete())
                    throw new Exception("无法移除旧版安装缓存");
                if (!partial.renameTo(target)) throw new Exception("无法保存已校验的 APK");
                onUi(() -> {
                    progress.dismiss();
                    busy = false;
                    if (!cancelled) beginInstallation();
                });
            } catch (Exception e) {
                partial.delete();
                onUi(() -> { progress.dismiss(); error("下载或验证更新失败", e); });
            }
        }, "TXT verified APK downloader").start();
    }
    private boolean installAllowed() {
        return Build.VERSION.SDK_INT < 26 ||
                activity.getPackageManager().canRequestPackageInstalls();
    }
    private void beginInstallation() {
        File file = apk();
        if (!file.isFile()) return;
        if (!installAllowed()) {
            awaitingInstallerPermission = true;
            new AlertDialog.Builder(activity).setTitle("允许安装更新")
                    .setMessage("APK 已下载并完成 SHA-256 和签名验证。Android 需要您允许"
                            + "「TXT 阅读器」安装来自此来源的应用。授权后返回阅读器，"
                            + "即可继续安装。")
                    .setPositiveButton("打开授权设置", (d, w) -> {
                        try {
                            activity.startActivity(new Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName())));
                        } catch (Exception e) {
                            new AlertDialog.Builder(activity)
                                    .setMessage("请在系统设置中允许本应用安装 APK，"
                                            + "再回到阅读器检查更新。")
                                    .setPositiveButton("确定", null).show();
                        }
                    })
                    .setNegativeButton("取消", (d, w) -> awaitingInstallerPermission = false)
                    .show();
            return;
        }
        awaitingInstallerPermission = false;
        Uri uri = Uri.parse("content://" + activity.getPackageName() + ".updates/release.apk");
        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(uri, APK_MIME);
        install.setClipData(ClipData.newRawUri("TXT Reader verified update", uri));
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(install);
        } catch (Exception e) {
            new AlertDialog.Builder(activity).setTitle("无法打开系统安装程序")
                    .setMessage(e.getMessage()).setPositiveButton("确定", null).show();
        }
    }
    void onResume() {
        if (awaitingInstallerPermission && installAllowed()) beginInstallation();
    }
}
