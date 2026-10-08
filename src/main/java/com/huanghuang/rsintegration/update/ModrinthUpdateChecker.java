package com.huanghuang.rsintegration.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.maven.artifact.versioning.ComparableVersion;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** 查询 Modrinth 已发布版本，不下载或替换模组文件。 */
public final class ModrinthUpdateChecker {
    public static final String PROJECT_ID = "vtvveeqA";
    public static final String PROJECT_URL = "https://modrinth.com/mod/rs-integration";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private ModrinthUpdateChecker() {}

    @Nullable
    public static Update check(String currentVersion, String minecraftVersion) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        HttpRequest request = HttpRequest.newBuilder(versionsUri(minecraftVersion))
                .timeout(TIMEOUT)
                .header("User-Agent", "RS-Integration/" + currentVersion + " (" + PROJECT_URL + ")")
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("Modrinth HTTP " + response.statusCode());
        return findUpdate(response.body(), currentVersion, minecraftVersion);
    }

    static URI versionsUri(String minecraftVersion) {
        if (!minecraftVersion.matches("[0-9.]+")) throw new IllegalArgumentException("无效的 Minecraft 版本");
        return URI.create("https://api.modrinth.com/v2/project/" + PROJECT_ID
                + "/version?loaders=%5B%22forge%22%5D&game_versions=%5B%22" + minecraftVersion + "%22%5D");
    }

    @Nullable
    static Update findUpdate(String json, String currentVersion, String minecraftVersion) {
        ComparableVersion current = new ComparableVersion(currentVersion);
        ComparableVersion latest = current;
        Update update = null;
        JsonArray versions = JsonParser.parseString(json).getAsJsonArray();
        for (JsonElement element : versions) {
            try {
                JsonObject version = element.getAsJsonObject();
                if (!contains(version.getAsJsonArray("loaders"), "forge")
                        || !contains(version.getAsJsonArray("game_versions"), minecraftVersion)) continue;
                String type = version.get("version_type").getAsString();
                // 项目现有版本以 beta 发布；alpha 不作为默认更新候选。
                if (!type.equals("release") && !type.equals("beta")) continue;
                if (version.has("status") && !version.get("status").getAsString().equals("listed")) continue;
                String number = version.get("version_number").getAsString().trim();
                String id = version.get("id").getAsString();
                if (!number.matches("[0-9]+(?:\\.[0-9]+)*(?:[-+][A-Za-z0-9.-]+)?")
                        || !id.matches("[A-Za-z0-9]+")) continue;
                ComparableVersion candidate = new ComparableVersion(number);
                if (candidate.compareTo(latest) > 0) {
                    latest = candidate;
                    update = new Update(number, PROJECT_URL + "/version/" + id);
                }
            } catch (RuntimeException invalidEntry) {
                // 单条异常数据不影响其它版本的检测。
            }
        }
        return update;
    }

    private static boolean contains(JsonArray values, String expected) {
        if (values == null) return false;
        for (JsonElement value : values) {
            if (value.isJsonPrimitive() && expected.equals(value.getAsString())) return true;
        }
        return false;
    }

    public record Update(String version, String downloadUrl) {}
}
