package io.github.shandianchengzi.txtreader;

import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;

/** Offline ZIP containing local TXT books and reading progress, with bounded extraction. */
final class BackupStore {
    private static final long MAX_BOOK = 32L * 1024 * 1024;
    private static final long MAX_TOTAL = 512L * 1024 * 1024;
    private static final int MAX_BOOKS = 1000;
    private static final int MAX_MANIFEST = 2 * 1024 * 1024;
    private BackupStore() {}

    static void exportTo(Context context, Uri destination, JSONObject metadata) throws Exception {
        JSONArray books = metadata.getJSONArray("books");
        if (books.length() > MAX_BOOKS) throw new IllegalArgumentException("书籍数量超出上限");
        try (OutputStream target = context.getContentResolver().openOutputStream(destination, "w")) {
            if (target == null) throw new IOException("无法创建备份文件");
            try (ZipOutputStream zip = new ZipOutputStream(target)) {
                JSONArray records = new JSONArray();
                for (int i = 0; i < books.length(); i++) {
                    JSONObject b = books.getJSONObject(i);
                    JSONObject r = new JSONObject();
                    r.put("name", b.getString("name"));
                    r.put("chapter", b.optInt("chapter", 0));
                    r.put("scroll", b.optInt("scroll", 0));
                    r.put("audioOffset", b.optInt("audioOffset", -1));
                    r.put("path", "books/" + i + ".txt");
                    records.put(r);
                }
                JSONObject manifest = new JSONObject();
                manifest.put("format", 1);
                manifest.put("books", records);
                manifest.put("font", metadata.optInt("font", 19));
                manifest.put("speed", metadata.optDouble("speed", 1.0));
                byte[] json = manifest.toString().getBytes(StandardCharsets.UTF_8);
                if (json.length > MAX_MANIFEST) throw new IOException("书架索引过大");
                zip.putNextEntry(new ZipEntry("manifest.json"));
                zip.write(json);
                zip.closeEntry();
                byte[] buffer = new byte[16384];
                long total = 0;
                for (int i = 0; i < books.length(); i++) {
                    JSONObject b = books.getJSONObject(i);
                    InputStream source = context.getContentResolver()
                            .openInputStream(Uri.parse(b.getString("uri")));
                    if (source == null) throw new IOException("无法打开：" + b.getString("name"));
                    try (InputStream in = source) {
                        zip.putNextEntry(new ZipEntry("books/" + i + ".txt"));
                        long size = 0;
                        int n;
                        while ((n = in.read(buffer)) != -1) {
                            if (n == 0) continue;
                            size += n;
                            total += n;
                            if (size > MAX_BOOK || total > MAX_TOTAL)
                                throw new IOException("超过备份大小限制");
                            zip.write(buffer, 0, n);
                        }
                        zip.closeEntry();
                    }
                }
            }
        }
    }

    static JSONObject importFrom(Context context, Uri source) throws Exception {
        File folder = new File(context.getFilesDir(), "restored-" + System.nanoTime());
        if (!folder.mkdirs()) throw new IOException("无法创建恢复目录");
        boolean complete = false;
        try {
            JSONObject manifest = null;
            JSONArray records = null;
            boolean[] found = null;
            long total = 0;
            byte[] buf = new byte[16384];
            InputStream input = context.getContentResolver().openInputStream(source);
            if (input == null) throw new IOException("无法打开备份");
            try (ZipInputStream zip = new ZipInputStream(input)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory()) throw new IOException("不支持的备份目录");
                    if (manifest == null) {
                        if (!"manifest.json".equals(entry.getName()))
                            throw new IOException("不是有效的阅读器完整备份");
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        int n;
                        while ((n = zip.read(buf)) != -1) {
                            if (bytes.size() + n > MAX_MANIFEST) throw new IOException("索引过大");
                            bytes.write(buf, 0, n);
                        }
                        manifest = new JSONObject(bytes.toString("UTF-8"));
                        if (manifest.optInt("format", -1) != 1)
                            throw new IOException("不支持此备份版本");
                        records = manifest.getJSONArray("books");
                        if (records.length() > MAX_BOOKS) throw new IOException("书籍过多");
                        found = new boolean[records.length()];
                    } else {
                        String entryName = entry.getName();
                        if (!entryName.matches("books/[0-9]{1,4}\\.txt"))
                            throw new IOException("备份内包含未知文件");
                        int index = Integer.parseInt(entryName.substring(6, entryName.length() - 4));
                        if (index >= records.length || found[index]
                                || !entryName.equals(records.getJSONObject(index).getString("path")))
                            throw new IOException("备份内的书籍路径无效或重复");
                        File file = new File(folder, index + ".txt");
                        long size = 0;
                        try (FileOutputStream out = new FileOutputStream(file)) {
                            int n;
                            while ((n = zip.read(buf)) != -1) {
                                if (n == 0) continue;
                                size += n;
                                total += n;
                                if (size > MAX_BOOK || total > MAX_TOTAL)
                                    throw new IOException("备份文件大小超出上限");
                                out.write(buf, 0, n);
                            }
                        }
                        found[index] = true;
                    }
                    zip.closeEntry();
                }
            }
            if (manifest == null) throw new IOException("备份文件为空");
            JSONArray result = new JSONArray();
            for (int i = 0; i < records.length(); i++) {
                if (!found[i]) throw new IOException("备份缺少书籍 " + i);
                JSONObject b = records.getJSONObject(i);
                if (b.getString("name").length() > 512) throw new IOException("书名过长");
                JSONObject restored = new JSONObject();
                restored.put("uri", Uri.fromFile(new File(folder, i + ".txt")).toString());
                restored.put("name", b.getString("name"));
                restored.put("chapter", Math.max(0, b.optInt("chapter", 0)));
                restored.put("scroll", Math.max(0, b.optInt("scroll", 0)));
                restored.put("audioOffset", b.optInt("audioOffset", -1));
                result.put(restored);
            }
            manifest.put("books", result);
            complete = true;
            return manifest;
        } finally {
            if (!complete) deleteFiles(folder);
        }
    }

    static void deleteOldImports(Context context, String keep) {
        File[] list = context.getFilesDir().listFiles();
        if (list == null) return;
        for (File f : list) if (f.isDirectory() && f.getName().startsWith("restored-")
                && !f.getName().equals(keep)) deleteFiles(f);
    }
    private static void deleteFiles(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteFiles(child);
        file.delete();
    }
}
