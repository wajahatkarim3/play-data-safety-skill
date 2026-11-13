// Scan — find what an Android app collects, for Google Play's Data safety form.
//
// Reads the project, never edits it:
//   1. Dependencies: runs the project's own Gradle (`dependencies --configuration <variant>RuntimeClasspath`)
//      to get the resolved graph, transitive libraries included. Falls back to reading build files
//      and gradle/libs.versions.toml if Gradle can't run.
//   2. Permissions: every AndroidManifest.xml under src/ (and the merged manifest if a build left one).
//   3. Code: searches Kotlin/Java sources for collection points and outbound hosts.
//   4. Matches dependencies against the cited SDK table in ../sdks/*.json.
//
// Writes <out>/scan.json (for agents and scripts), <out>/scan.md (for people) and
// <out>/play_data_safety.draft.md (input for DataSafetyCsv.java once the open questions are answered).
//
// No build step, no dependencies. Requires JDK 17+:
//   java Scan.java <project-dir> [--module :app] [--configuration releaseRuntimeClasspath]
//                  [--out <dir>] [--no-gradle] [--check <play_data_safety.md>]
//
// Exit codes: 0 scan written · 1 error · 2 usage · 4 --check found undeclared data types.

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import java.util.stream.*;

@SuppressWarnings("unchecked")
public class Scan {

    // ---------------------------------------------------------------- built-in signal tables
    // Permission -> what it can expose. A permission is a SIGNAL, not a declaration: data used only
    // on the device is not "collected" in Play's sense, so each one becomes a question.
    static final String[][] PERMISSIONS = {
        {"ACCESS_FINE_LOCATION", "Location / Precise location"},
        {"ACCESS_COARSE_LOCATION", "Location / Approximate location"},
        {"ACCESS_BACKGROUND_LOCATION", "Location / Precise location"},
        {"READ_CONTACTS", "Contacts / Contacts"},
        {"WRITE_CONTACTS", "Contacts / Contacts"},
        {"GET_ACCOUNTS", "Personal info / Email address"},
        {"READ_CALENDAR", "Calendar / Calendar events"},
        {"WRITE_CALENDAR", "Calendar / Calendar events"},
        {"RECORD_AUDIO", "Audio files / Voice or sound recordings"},
        {"CAMERA", "Photos and videos / Photos"},
        {"READ_MEDIA_IMAGES", "Photos and videos / Photos"},
        {"READ_MEDIA_VIDEO", "Photos and videos / Videos"},
        {"READ_MEDIA_AUDIO", "Audio files / Music files"},
        {"READ_MEDIA_VISUAL_USER_SELECTED", "Photos and videos / Photos"},
        {"READ_EXTERNAL_STORAGE", "Files and docs / Files and docs"},
        {"MANAGE_EXTERNAL_STORAGE", "Files and docs / Files and docs"},
        {"READ_SMS", "Messages / SMS or MMS"},
        {"RECEIVE_SMS", "Messages / SMS or MMS"},
        {"SEND_SMS", "Messages / SMS or MMS"},
        {"READ_CALL_LOG", "Personal info / Other info"},
        {"READ_PHONE_NUMBERS", "Personal info / Phone number"},
        {"READ_PHONE_STATE", "Device or other IDs / Device or other IDs"},
        {"QUERY_ALL_PACKAGES", "App activity / Installed apps"},
        {"PACKAGE_USAGE_STATS", "App activity / Other actions"},
        {"AD_ID", "Device or other IDs / Device or other IDs"},
        {"BODY_SENSORS", "Health and fitness / Health info"},
        {"ACTIVITY_RECOGNITION", "Health and fitness / Fitness info"},
        {"BLUETOOTH_SCAN", "Location / Precise location"},
        {"NEARBY_WIFI_DEVICES", "Location / Precise location"},
    };

    // Code pattern -> what it suggests. Same rule: a signal to ask about, never a declaration.
    static final String[][] CODE = {
        // kind, regex, data type it points at ("" = ask what is sent)
        {"network", "Retrofit\\.Builder|@(GET|POST|PUT|PATCH|DELETE)\\(|OkHttpClient|newWebSocket|HttpURLConnection|io\\.ktor\\.client|\\bHttpClient\\(", ""},
        {"location", "FusedLocationProviderClient|LocationServices\\.|LocationManager|requestLocationUpdates|getCurrentLocation|lastLocation", "Location / Precise location"},
        {"contacts", "ContactsContract", "Contacts / Contacts"},
        {"calendar", "CalendarContract", "Calendar / Calendar events"},
        {"audio", "MediaRecorder|AudioRecord\\b|SpeechRecognizer", "Audio files / Voice or sound recordings"},
        {"camera", "androidx\\.camera|ImageCapture|CameraManager|ACTION_IMAGE_CAPTURE|TakePicture\\(|CaptureVideo\\(", "Photos and videos / Photos"},
        {"media", "PickVisualMedia|PickMultipleVisualMedia|MediaStore\\.(Images|Video|Audio)", "Photos and videos / Photos"},
        {"files", "OpenDocument|ACTION_OPEN_DOCUMENT|GetContent\\(", "Files and docs / Files and docs"},
        {"device-id", "AdvertisingIdClient|Settings\\.Secure\\.ANDROID_ID|Secure\\.ANDROID_ID", "Device or other IDs / Device or other IDs"},
        {"phone", "getLine1Number|TelephonyManager", "Personal info / Phone number"},
        {"sms", "SmsManager|Telephony\\.Sms", "Messages / SMS or MMS"},
        {"installed-apps", "getInstalledPackages|getInstalledApplications|queryIntentActivities", "App activity / Installed apps"},
        {"health", "HealthConnectClient|androidx\\.health\\.connect", "Health and fitness / Health info"},
        {"accounts", "AccountManager", "Personal info / Email address"},
    };

    // Group prefixes of libraries that collect nothing by themselves (UI, language, DI, networking
    // plumbing). Everything else that no SDK entry covers is reported as "unknown" for review.
    static final String[] NO_DATA_GROUPS = {
        "androidx.", "org.jetbrains", "com.google.android.material", "com.google.code.gson",
        "com.google.code.findbugs", "com.google.guava", "com.google.errorprone", "com.google.j2objc",
        "org.checkerframework", "com.google.protobuf", "com.google.dagger", "javax.inject", "jakarta.inject",
        "io.insert-koin", "com.squareup.okhttp3", "com.squareup.retrofit2", "com.squareup.okio",
        "com.squareup.moshi", "com.squareup.wire", "com.squareup.picasso", "com.squareup.leakcanary",
        "io.coil-kt", "com.github.bumptech.glide", "com.jakewharton", "io.reactivex", "org.reactivestreams",
        "io.ktor", "com.google.accompanist", "junit", "org.hamcrest", "org.ow2", "net.bytebuddy",
        "org.slf4j", "com.airbnb.android:lottie", "com.google.auto", "com.intellij", "org.jspecify",
        "com.google.android.gms:play-services-basement", "com.google.android.gms:play-services-base",
        "com.google.android.gms:play-services-tasks", "com.google.android.play:core-common",
        "com.google.android.play:review", "com.google.android.play:app-update", "com.google.android.play:feature-delivery",
        "org.json", "com.android.", "com.mikepenz", "br.com.devsrsouza.compose.icons", "co.touchlab", "com.caverock", "com.google.crypto.tink",
        "com.russhwolf", "com.squareup.curtains", "dev.drewhamilton.poko", "dev.whyoleg.cryptography", "org.commonmark",
        "org.bouncycastle", "com.github.lisawray.groupie", "com.squareup.sqldelight", "app.cash.sqldelight", "io.realm", "net.zetetic", "com.google.zxing",
        "io.noties.markwon", "com.github.skydoves", "org.jsoup", "com.fasterxml.jackson", "io.github.oshai", "com.github.ajalt", "com.google.android.datatransport", "dev.chrisbanes", "io.github.aakira",
    };

    // ---------------------------------------------------------------- state
    static Path project, skillDir, out;
    static String moduleArg = null, configArg = null, checkFile = null;
    static boolean noGradle = false;
    static final List<String> notes = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args[0].startsWith("--")) usage();
        project = Path.of(args[0]).toAbsolutePath().normalize();
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--module" -> moduleArg = need(args, ++i);
                case "--configuration" -> configArg = need(args, ++i);
                case "--out" -> out = Path.of(need(args, ++i)).toAbsolutePath();
                case "--no-gradle" -> noGradle = true;
                case "--check" -> checkFile = need(args, ++i);
                case "--skill-dir" -> skillDir = Path.of(need(args, ++i)).toAbsolutePath();
                default -> { System.err.println("unknown option " + args[i]); usage(); }
            }
        }
        if (!Files.isDirectory(project)) die("not a directory: " + project);
        if (skillDir == null) skillDir = findSkillDir();
        if (out == null) out = project.resolve("play-data-safety");

        List<Map<String, Object>> sdks = loadSdks(skillDir.resolve("sdks"));

        // 1. modules
        List<String> appModules = moduleArg != null ? List.of(moduleArg.startsWith(":") ? moduleArg : ":" + moduleArg)
                                                    : findAppModules();
        if (appModules.isEmpty()) die("no Android application module found (no build file applies com.android.application). "
                + "Pass --module :yourModule");

        // 2. dependencies
        Map<String, String> deps = new TreeMap<>();   // group:artifact -> version
        String depSource = "none";
        if (!noGradle && gradlew() != null) {
            boolean any = false;
            for (String m : appModules) any |= gradleDeps(m, deps);
            if (any) depSource = "gradle (resolved, includes transitive libraries)";
        }
        if (depSource.equals("none")) {
            staticDeps(deps);
            depSource = "build files (direct dependencies only; transitive libraries NOT checked)";
            notes.add("Gradle was not used, so libraries pulled in by other libraries were not checked. "
                    + "Re-run without --no-gradle (or fix the Gradle error above) for a complete list.");
        }

        // 3. match
        List<Map<String, Object>> matched = new ArrayList<>();
        Set<String> coveredGroups = new HashSet<>();
        Set<String> matchedCoords = new HashSet<>();
        for (Map<String, Object> sdk : sdks) {
            Set<String> hits = new LinkedHashSet<>();
            for (String coord : deps.keySet())
                for (Object p : list(sdk.get("match")))
                    if (matches(coord, (String) p)) { hits.add(coord + ":" + deps.get(coord)); matchedCoords.add(coord); }
            if (!hits.isEmpty()) {
                Map<String, Object> m = new LinkedHashMap<>(sdk);
                m.put("matchedDependencies", new ArrayList<>(hits));
                matched.add(m);
                for (String h : hits) coveredGroups.add(h.substring(0, h.indexOf(':')));
            }
        }
        List<String> unknown = new ArrayList<>(), supporting = new ArrayList<>();
        for (String coord : deps.keySet()) {
            if (matchedCoords.contains(coord) || noData(coord)) continue;
            String group = coord.substring(0, coord.indexOf(':'));
            (coveredGroups.contains(group) ? supporting : unknown).add(coord + ":" + deps.get(coord));
        }

        // 4. permissions + code
        List<Map<String, Object>> perms = scanPermissions();
        List<Map<String, Object>> code = new ArrayList<>();
        Set<String> hosts = new TreeSet<>();
        scanCode(code, hosts);

        // 5. draft rows from SDK entries
        Map<String, Map<String, Object>> rows = new TreeMap<>();
        List<Map<String, Object>> conditional = new ArrayList<>();
        for (Map<String, Object> sdk : matched) {
            for (Object o : list(sdk.get("dataTypes"))) {
                Map<String, Object> dt = map(o);
                if (Boolean.FALSE.equals(dt.get("collected")) && !Boolean.TRUE.equals(dt.get("shared"))) continue;
                String type = (String) dt.get("type");
                if (Boolean.TRUE.equals(dt.get("conditional"))) {   // only in some setups: ask, don't declare
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("sdk", sdk.get("name")); c.put("type", type); c.put("when", dt.getOrDefault("when", ""));
                    c.put("shared", Boolean.TRUE.equals(dt.get("shared"))); c.put("purposes", dt.get("purposes"));
                    conditional.add(c);
                    continue;
                }
                Map<String, Object> r = rows.computeIfAbsent(type, k -> {
                    Map<String, Object> n = new LinkedHashMap<>();
                    n.put("type", k); n.put("shared", false); n.put("ephemeral", true);
                    n.put("purposes", new LinkedHashSet<String>()); n.put("sources", new LinkedHashSet<String>());
                    return n;
                });
                if (Boolean.TRUE.equals(dt.get("shared"))) r.put("shared", true);
                if (!Boolean.TRUE.equals(dt.get("ephemeral"))) r.put("ephemeral", false);
                for (Object p : list(dt.get("purposes"))) ((Set<String>) r.get("purposes")).add((String) p);
                ((Set<String>) r.get("sources")).add((String) sdk.get("name"));
            }
        }

        // --check: compare against an existing declaration, for CI
        if (checkFile != null) System.exit(check(Path.of(checkFile), rows));

        // 6. write
        Files.createDirectories(out);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tool", "play-data-safety");
        result.put("scannedAt", LocalDate.now().toString());
        result.put("project", project.toString());
        result.put("modules", appModules);
        result.put("dependencySource", depSource);
        result.put("dependencyCount", deps.size());
        result.put("sdks", matched);
        result.put("draftRows", rows.values());
        result.put("conditionalTypes", conditional);
        result.put("unknownDependencies", unknown);
        result.put("supportingDependencies", supporting);
        result.put("permissions", perms);
        result.put("codeSignals", code);
        result.put("hosts", hosts);
        result.put("notes", notes);
        result.put("advisory", "Draft for a human to confirm. Static analysis cannot know what your server stores, "
                + "whether a field is optional, or how deletion works. Not legal advice.");
        Files.writeString(out.resolve("scan.json"), toJson(result, 0) + "\n");
        Files.writeString(out.resolve("scan.md"), report(result, matched, rows, conditional, unknown, perms, code, hosts));
        Files.writeString(out.resolve("play_data_safety.draft.md"), draft(rows));

        System.out.println("Scan written to " + out);
        System.out.println("  dependencies: " + deps.size() + " via " + depSource);
        System.out.println("  SDKs recognised: " + matched.size()
                + (matched.isEmpty() ? "" : " (" + matched.stream().map(m -> (String) m.get("id")).collect(Collectors.joining(", ")) + ")"));
        System.out.println("  data types from SDKs: " + rows.size() + " (+ " + conditional.size() + " that depend on how you use them)");
        System.out.println("  unknown libraries to review: " + unknown.size());
        System.out.println("  permissions to ask about: " + perms.size() + " · code signals: " + code.size() + " · hosts: " + hosts.size());
        for (String n : notes) System.out.println("  NOTE: " + n);
    }

    // ---------------------------------------------------------------- modules
    static List<String> findAppModules() throws IOException {
        List<String> mods = new ArrayList<>();
        try (Stream<Path> s = Files.walk(project, 4)) {
            for (Path p : s.filter(Scan::notIgnoredDir).filter(p -> {
                String n = p.getFileName().toString();
                return n.equals("build.gradle") || n.equals("build.gradle.kts");
            }).collect(Collectors.toList())) {
                // A line that applies the Android application plugin, not a root build declaring it "apply false".
                boolean app = Files.readAllLines(p).stream().anyMatch(l -> !l.contains("apply false")
                        && (l.contains("com.android.application") || l.matches(".*plugins\\.android\\.application\\b.*")));
                if (app) {
                    Path dir = project.relativize(p.getParent());
                    String path = dir.toString().isEmpty() ? "" : ":" + dir.toString().replace(File.separatorChar, ':');
                    mods.add(path);
                }
            }
        }
        Collections.sort(mods);
        return mods;
    }

    // ---------------------------------------------------------------- gradle
    static String gradlew() {
        boolean win = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        Path w = project.resolve(win ? "gradlew.bat" : "gradlew");
        return Files.exists(w) ? w.toString() : null;
    }

    static boolean gradleDeps(String module, Map<String, String> deps) throws Exception {
        String task = (module.isEmpty() ? "" : module) + ":dependencies";
        String config = configArg != null ? configArg : "releaseRuntimeClasspath";
        System.out.println("Running Gradle: " + task + " --configuration " + config + " (first run can take a few minutes)");
        String[] r = run(List.of(gradlew(), "-q", "--console=plain", task, "--configuration", config));
        if (r[0].equals("0")) { parseTree(r[1], deps); return true; }
        if (configArg == null && (r[1] + r[2]).contains("not found")) {
            // Product flavors: no plain releaseRuntimeClasspath. List all and take the first *ReleaseRuntimeClasspath.
            System.out.println("  no '" + config + "' (product flavors?). Listing all configurations…");
            r = run(List.of(gradlew(), "-q", "--console=plain", task));
            if (r[0].equals("0")) {
                String section = pickSection(r[1]);
                if (section != null) { parseTree(section, deps); return true; }
            }
        }
        String err = (r[2].isBlank() ? r[1] : r[2]).strip();
        String[] lines = err.split("\n");
        notes.add("Gradle failed for " + task + ": " + String.join(" | ", Arrays.copyOf(lines, Math.min(6, lines.length))));
        return false;
    }

    static String pickSection(String all) {
        Matcher m = Pattern.compile("(?m)^(\\w*ReleaseRuntimeClasspath) - ").matcher(all);
        if (!m.find()) return null;
        notes.add("Used configuration '" + m.group(1) + "'. Pass --configuration to scan another flavor.");
        int start = m.start();
        int end = all.indexOf("\n\n", start);
        return all.substring(start, end < 0 ? all.length() : end);
    }

    static final Pattern TREE = Pattern.compile("^[\\s|+\\\\-]*([A-Za-z0-9_.\\-]+):([A-Za-z0-9_.\\-]+)(?::(\\S+))?(?: -> (\\S+))?(.*)$");
    static void parseTree(String text, Map<String, String> deps) {
        for (String line : text.split("\n")) {
            if (!line.matches("^[\\s|+\\\\-]*[+\\\\]--- .*")) continue;
            if (line.contains("--- project ")) continue;
            Matcher m = TREE.matcher(line);
            if (!m.matches()) continue;
            if (m.group(5) != null && m.group(5).contains("(c)")) continue;   // version constraint, not a library
            String ver = m.group(4) != null ? m.group(4) : (m.group(3) != null ? m.group(3) : "");
            ver = ver.replaceAll("[{}]", "").replace("strictly ", "");
            deps.put(m.group(1) + ":" + m.group(2), ver);
        }
    }

    static String[] run(List<String> cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(project.toFile());
        Process p = pb.start();
        StringBuilder so = new StringBuilder(), se = new StringBuilder();
        Thread a = pump(p.getInputStream(), so), b = pump(p.getErrorStream(), se);
        if (!p.waitFor(15, TimeUnit.MINUTES)) { p.destroyForcibly(); return new String[]{"timeout", so.toString(), "Gradle timed out after 15 minutes"}; }
        a.join(); b.join();
        return new String[]{String.valueOf(p.exitValue()), so.toString(), se.toString()};
    }
    static Thread pump(InputStream in, StringBuilder sb) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String l; while ((l = r.readLine()) != null) sb.append(l).append('\n');
            } catch (IOException ignored) {}
        });
        t.start();
        return t;
    }

    // ---------------------------------------------------------------- static fallback
    static void staticDeps(Map<String, String> deps) throws IOException {
        Map<String, String> libs = new HashMap<>();          // accessor -> g:a:v
        Map<String, List<String>> bundles = new HashMap<>();
        Path toml = project.resolve("gradle/libs.versions.toml");
        if (Files.exists(toml)) parseToml(Files.readString(toml), libs, bundles);
        Pattern literal = Pattern.compile("[\"']([A-Za-z0-9_.\\-]+):([A-Za-z0-9_.\\-]+)(?::([^\"'@]+))?[\"']");
        Pattern libRef = Pattern.compile("\\blibs\\.((?:bundles\\.)?[A-Za-z0-9_.]+)");
        try (Stream<Path> s = Files.walk(project, 4)) {
            for (Path p : s.filter(Scan::notIgnoredDir).filter(p -> p.getFileName().toString().matches("build\\.gradle(\\.kts)?")).collect(Collectors.toList())) {
                String t = Files.readString(p);
                int i = t.indexOf("dependencies");
                while (i >= 0) {
                    int open = t.indexOf('{', i);
                    if (open < 0) break;
                    int close = matchBrace(t, open);
                    String block = t.substring(open, close);
                    if (!block.contains("classpath")) {
                        for (String line : block.split("\n")) {
                            Matcher c = SHIPPED.matcher(line);
                            if (!c.find()) continue;          // test, debug, lint, ksp/kapt, compileOnly: not shipped
                            Matcher m = literal.matcher(line);
                            while (m.find()) deps.put(m.group(1) + ":" + m.group(2), m.group(3) == null ? "" : m.group(3));
                            Matcher r = libRef.matcher(line);
                            while (r.find()) {
                                String acc = r.group(1);
                                if (acc.startsWith("bundles.")) {
                                    for (String b : bundles.getOrDefault(norm(acc.substring(8)), List.of()))
                                        put(deps, libs.get(norm(b)));
                                } else put(deps, libs.get(norm(acc)));
                            }
                        }
                    }
                    i = t.indexOf("dependencies", close);
                }
            }
        }
    }
    // Configurations whose libraries end up in the release app.
    static final Pattern SHIPPED = Pattern.compile("^\\s*\"?(implementation|api|runtimeOnly|release\\w*Implementation|releaseRuntimeOnly|(?!test|androidTest|debug|testFixtures|lint)[a-z]\\w*Implementation)\\b");
    static void put(Map<String, String> deps, String gav) {
        if (gav == null) return;
        String[] p = gav.split(":");
        if (p.length >= 2) deps.put(p[0] + ":" + p[1], p.length > 2 ? p[2] : "");
    }
    static int matchBrace(String t, int open) {
        int d = 0;
        for (int i = open; i < t.length(); i++) {
            if (t.charAt(i) == '{') d++;
            else if (t.charAt(i) == '}' && --d == 0) return i;
        }
        return t.length();
    }
    static String norm(String s) { return s.replaceAll("[-_.]", ".").toLowerCase(Locale.ROOT); }

    static void parseToml(String text, Map<String, String> libs, Map<String, List<String>> bundles) {
        Map<String, String> versions = new HashMap<>();
        String section = "";
        for (String raw : text.split("\n")) {
            String line = raw.replaceAll("#.*$", "").strip();
            if (line.isEmpty()) continue;
            if (line.startsWith("[")) { section = line; continue; }
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).strip().replace("\"", "");
            String val = line.substring(eq + 1).strip();
            switch (section) {
                case "[versions]" -> versions.put(key, val.replace("\"", ""));
                case "[libraries]" -> {
                    String gav = null;
                    if (val.startsWith("\"")) gav = val.replace("\"", "");
                    else {
                        String module = attr(val, "module");
                        String group = attr(val, "group"), name = attr(val, "name");
                        String ver = attr(val, "version");
                        String ref = attr(val, "version.ref");
                        if (ref != null) ver = versions.get(ref);
                        if (module != null) gav = module + ":" + (ver == null ? "" : ver);
                        else if (group != null && name != null) gav = group + ":" + name + ":" + (ver == null ? "" : ver);
                    }
                    if (gav != null) libs.put(norm(key), gav);
                }
                case "[bundles]" -> {
                    List<String> items = new ArrayList<>();
                    Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(val);
                    while (m.find()) items.add(m.group(1));
                    bundles.put(norm(key), items);
                }
                default -> {}
            }
        }
    }
    static String attr(String val, String name) {
        Matcher m = Pattern.compile("(?<![\\w.])" + Pattern.quote(name) + "\\s*=\\s*\"([^\"]*)\"").matcher(val);
        return m.find() ? m.group(1) : null;
    }

    // ---------------------------------------------------------------- permissions
    static List<Map<String, Object>> scanPermissions() throws IOException {
        Map<String, Map<String, Object>> found = new TreeMap<>();
        List<Path> manifests;
        try (Stream<Path> s = Files.walk(project, 8)) {
            manifests = s.filter(p -> p.getFileName().toString().equals("AndroidManifest.xml"))
                    .filter(p -> {
                        String str = p.toString().replace('\\', '/');
                        if (str.contains("/src/test") || str.contains("/src/androidTest") || str.contains("/.gradle/")) return false;
                        // keep sources, plus the merged manifest of a release build if one exists
                        return !str.contains("/build/") || (str.contains("merged_manifest") && str.toLowerCase(Locale.ROOT).contains("release"));
                    }).collect(Collectors.toList());
        }
        Pattern perm = Pattern.compile("<uses-permission(?:-sdk-23)?[^>]*android:name\\s*=\\s*\"([^\"]+)\"([^>]*)>");
        for (Path mf : manifests) {
            // Drop XML comments first: templates often ship commented-out <uses-permission> examples.
            String t = Files.readString(mf).replaceAll("(?s)<!--.*?-->", "");
            Matcher m = perm.matcher(t);
            while (m.find()) {
                String full = m.group(1);
                String shortName = full.substring(full.lastIndexOf('.') + 1);
                boolean removed = m.group(2).contains("tools:node=\"remove\"");
                for (String[] p : PERMISSIONS) {
                    if (!p[0].equals(shortName)) continue;
                    Map<String, Object> e = found.computeIfAbsent(full, k -> {
                        Map<String, Object> n = new LinkedHashMap<>();
                        n.put("permission", k); n.put("suggests", p[1]); n.put("files", new LinkedHashSet<String>());
                        n.put("removed", false);
                        return n;
                    });
                    ((Set<String>) e.get("files")).add(project.relativize(mf).toString());
                    if (removed) e.put("removed", true);
                }
            }
        }
        return found.values().stream().filter(e -> !Boolean.TRUE.equals(e.get("removed"))).collect(Collectors.toList());
    }

    // ---------------------------------------------------------------- code
    static final Set<String> HOST_SKIP = Set.of("schemas.android.com", "www.w3.org", "example.com", "www.example.com",
            "localhost", "127.0.0.1", "10.0.2.2", "developer.android.com", "play.google.com", "github.com",
            "www.apache.org", "apache.org", "opensource.org", "xmlpull.org", "ns.adobe.com", "www.google.com");

    static void scanCode(List<Map<String, Object>> out, Set<String> hosts) throws IOException {
        List<Pattern> pats = Arrays.stream(CODE).map(c -> Pattern.compile(c[1])).collect(Collectors.toList());
        Map<String, Integer> perKind = new HashMap<>();
        Pattern url = Pattern.compile("https?://([A-Za-z0-9][A-Za-z0-9.\\-]*\\.[A-Za-z]{2,})");
        List<Path> files;
        try (Stream<Path> s = Files.walk(project, 20)) {
            files = s.filter(p -> { String n = p.getFileName().toString(); return n.endsWith(".kt") || n.endsWith(".java"); })
                    .filter(p -> {
                        String str = "/" + project.relativize(p).toString().replace('\\', '/');
                        return !str.contains("/build/") && !str.matches(".*/\\.[^/]+/.*") && !str.contains("/src/test")
                                && !str.contains("/src/androidTest") && !str.contains("/play-data-safety/");
                    }).collect(Collectors.toList());
        }
        for (Path f : files) {
            List<String> lines;
            try { lines = Files.readAllLines(f); } catch (IOException e) { continue; }
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                String trimmed = l.strip();
                if (trimmed.startsWith("import ") || trimmed.startsWith("//") || trimmed.startsWith("*")) continue;
                for (int k = 0; k < CODE.length; k++) {
                    if (!pats.get(k).matcher(l).find()) continue;
                    int n = perKind.merge(CODE[k][0], 1, Integer::sum);
                    if (n > 8) continue;   // enough evidence; keep the report readable
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("kind", CODE[k][0]);
                    e.put("suggests", CODE[k][2]);
                    e.put("location", project.relativize(f) + ":" + (i + 1));
                    e.put("code", trimmed.length() > 140 ? trimmed.substring(0, 140) + "…" : trimmed);
                    out.add(e);
                }
                Matcher m = url.matcher(l);
                while (m.find()) if (!HOST_SKIP.contains(m.group(1).toLowerCase(Locale.ROOT))) hosts.add(m.group(1).toLowerCase(Locale.ROOT));
            }
        }
        for (Map.Entry<String, Integer> e : perKind.entrySet())
            if (e.getValue() > 8) notes.add(e.getValue() + " '" + e.getKey() + "' code signals found; only the first 8 are listed.");
    }

    // ---------------------------------------------------------------- matching
    static boolean matches(String coord, String pattern) {
        if (pattern.endsWith("*")) return coord.startsWith(pattern.substring(0, pattern.length() - 1));
        return coord.equals(pattern);
    }
    static boolean noData(String coord) {
        if (coord.endsWith("-bom") || coord.endsWith(":bom")) return true;   // version alignment only
        for (String g : NO_DATA_GROUPS) {
            if (g.contains(":")) { if (coord.startsWith(g)) return true; }
            else if (coord.startsWith(g)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- --check
    static int check(Path md, Map<String, Map<String, Object>> rows) throws IOException {
        if (!Files.exists(md)) die("--check file not found: " + md);
        Set<String> declared = new HashSet<>();
        boolean inTable = false;
        for (String raw : Files.readAllLines(md)) {
            String line = raw.strip();
            if (!line.startsWith("|")) { if (inTable && line.isEmpty()) inTable = false; continue; }
            String[] c = line.substring(1).split("\\|");
            if (c.length < 2) continue;
            if (c[0].strip().equalsIgnoreCase("Data type")) { inTable = true; continue; }
            if (c[0].strip().matches("-+:?|:?-+:?")) continue;
            if (inTable && c[1].strip().toLowerCase(Locale.ROOT).startsWith("y")) declared.add(c[0].strip().toLowerCase(Locale.ROOT));
        }
        List<String> missing = new ArrayList<>();
        for (Map<String, Object> r : rows.values())
            if (!declared.contains(((String) r.get("type")).toLowerCase(Locale.ROOT)))
                missing.add(r.get("type") + "  (from " + String.join(", ", (Set<String>) r.get("sources")) + ")");
        if (missing.isEmpty()) {
            System.out.println("PASS: every data type the recognised SDKs collect is declared in " + md);
            return 0;
        }
        System.out.println("FAIL: data types your SDKs collect that " + md + " does not declare:");
        for (String m : missing) System.out.println("  ✗ " + m);
        System.out.println("Update the declaration (and Play Console) before releasing.");
        return 4;
    }

    // ---------------------------------------------------------------- outputs
    static String report(Map<String, Object> res, List<Map<String, Object>> sdks, Map<String, Map<String, Object>> rows,
                         List<Map<String, Object>> conditional, List<String> unknown, List<Map<String, Object>> perms, List<Map<String, Object>> code, Set<String> hosts) {
        StringBuilder b = new StringBuilder();
        b.append("# Data safety scan\n\n");
        b.append("> **Draft for a human to confirm.** Static analysis cannot know what your server stores, whether a field is ")
         .append("optional, or how deletion works. This is not legal advice.\n\n");
        b.append("- Project: `").append(res.get("project")).append("`\n");
        b.append("- Modules: ").append(res.get("modules")).append("\n");
        b.append("- Dependencies: ").append(res.get("dependencyCount")).append(" via ").append(res.get("dependencySource")).append("\n");
        b.append("- Scanned: ").append(res.get("scannedAt")).append("\n\n");

        b.append("## SDKs recognised (").append(sdks.size()).append(")\n\n");
        if (sdks.isEmpty()) b.append("None of the libraries match an SDK in the table.\n\n");
        else {
            b.append("| SDK | Matched libraries | Role | Confidence | Source |\n|---|---|---|---|---|\n");
            for (Map<String, Object> s : sdks) {
                Map<String, Object> src = list(s.get("sources")).isEmpty() ? Map.of() : map(list(s.get("sources")).get(0));
                b.append("| ").append(s.get("name")).append(" | ")
                 .append(list(s.get("matchedDependencies")).stream().map(x -> "`" + x + "`").collect(Collectors.joining("<br>")))
                 .append(" | ").append(s.getOrDefault("role", "")).append(" | ").append(s.getOrDefault("confidence", ""))
                 .append(" | ").append(src.isEmpty() ? "" : "[" + src.getOrDefault("checked", "link") + "](" + src.get("url") + ")").append(" |\n");
            }
            b.append("\n");
        }

        b.append("## Data types your SDKs collect (").append(rows.size()).append(")\n\n");
        if (!rows.isEmpty()) {
            b.append("| Data type | Shared | Purposes | Because of |\n|---|---|---|---|\n");
            for (Map<String, Object> r : rows.values())
                b.append("| ").append(r.get("type")).append(" | ").append(Boolean.TRUE.equals(r.get("shared")) ? "Yes" : "No")
                 .append(" | ").append(String.join("; ", (Set<String>) r.get("purposes")))
                 .append(" | ").append(String.join(", ", (Set<String>) r.get("sources"))).append(" |\n");
            b.append("\n");
        }

        b.append("## Questions only you can answer\n\n");
        int q = 0;
        for (Map<String, Object> c : conditional)
            b.append(++q).append(". **").append(c.get("sdk")).append(":** declare `").append(c.get("type"))
             .append("`? Only if: ").append(c.get("when")).append("\n");
        for (Map<String, Object> s : sdks)
            for (Object a : list(s.get("ask"))) b.append(++q).append(". **").append(s.get("name")).append(":** ").append(a).append("\n");
        for (Map<String, Object> p : perms)
            b.append(++q).append(". **Permission `").append(p.get("permission")).append("`:** does data from it leave the device? ")
             .append("If yes, declare `").append(p.get("suggests")).append("`. Data used only on the device is not collected.\n");
        if (!hosts.isEmpty())
            b.append(++q).append(". **Servers your code calls** (").append(String.join(", ", hosts))
             .append("): what user data does each one receive?\n");
        b.append(++q).append(". Can users request deletion of their data? Give the URL where they can.\n");
        b.append(++q).append(". How do users create an account: username and password, OAuth (e.g. Google sign-in), other, or none?\n\n");

        b.append("## Libraries not in the table (").append(unknown.size()).append(")\n\n");
        if (unknown.isEmpty()) b.append("None.\n\n");
        else {
            b.append("Check each one's docs for a Play Data safety page. If it collects data, add an entry to `sdks/` (see `sdks/SCHEMA.md`).\n\n");
            Map<String, List<String>> byGroup = new TreeMap<>();
            for (String u : unknown) byGroup.computeIfAbsent(u.substring(0, u.indexOf(':')), k -> new ArrayList<>()).add(u.substring(u.indexOf(':') + 1));
            for (Map.Entry<String, List<String>> g : byGroup.entrySet())
                b.append("- `").append(g.getKey()).append("`: ").append(String.join(", ", g.getValue())).append("\n");
            b.append("\n");
        }

        b.append("## Code signals (").append(code.size()).append(")\n\n");
        if (code.isEmpty()) b.append("None.\n\n");
        else {
            b.append("| Kind | Where | Code | Points at |\n|---|---|---|---|\n");
            for (Map<String, Object> c : code)
                b.append("| ").append(c.get("kind")).append(" | `").append(c.get("location")).append("` | `")
                 .append(((String) c.get("code")).replace("|", "\\|").replace("`", "'")).append("` | ")
                 .append(c.get("suggests")).append(" |\n");
            b.append("\n");
        }
        List<String> n = list(res.get("notes")).stream().map(Object::toString).collect(Collectors.toList());
        if (!n.isEmpty()) { b.append("## Notes\n\n"); for (String x : n) b.append("- ").append(x).append("\n"); }
        return b.toString();
    }

    static String draft(Map<String, Map<String, Object>> rows) {
        StringBuilder b = new StringBuilder();
        b.append("# Play Data Safety — <App name>\n\n");
        b.append("(Draft generated ").append(LocalDate.now()).append(" by play-data-safety. Advisory: confirm every row before importing.)\n\n");
        b.append("## Data collection and security\n\n");
        b.append("- **Does your app collect or share any of the required user data types?** ").append(rows.isEmpty() ? "TODO" : "Yes").append("\n");
        b.append("- **Is all of the user data collected by your app encrypted in transit?** TODO\n");
        b.append("- **Do you provide a way for users to request that their data is deleted?** TODO\n");
        b.append("- **Data deletion URL:** TODO\n");
        b.append("- **Account creation method:** TODO (Username and password, OAuth, Other authentication, or None)\n\n");
        b.append("## Data types collected\n\n");
        b.append("| Data type | Collected | Processed ephemerally | Required, or users choose | Shared | Purpose | Source |\n");
        b.append("|---|---|---|---|---|---|---|\n");
        for (Map<String, Object> r : rows.values())
            b.append("| ").append(r.get("type")).append(" | Yes | ").append(Boolean.TRUE.equals(r.get("ephemeral")) ? "Yes" : "No")
             .append(" | Data collection is required | ").append(Boolean.TRUE.equals(r.get("shared")) ? "Yes" : "No")
             .append(" | ").append(String.join("; ", (Set<String>) r.get("purposes")))
             .append(" | ").append(String.join(", ", (Set<String>) r.get("sources"))).append(" |\n");
        b.append("\n");
        return b.toString();
    }

    // ---------------------------------------------------------------- sdk table
    static Path findSkillDir() {
        // Default: the folder that holds scripts/Scan.java. Java's source launcher doesn't expose the
        // script path, so look in the usual install places, relative to the project, then the cwd.
        List<Path> candidates = new ArrayList<>();
        for (String d : List.of(".claude/skills", ".agents/skills", ".gemini/skills", ".codex/skills", ".cursor/skills", ".opencode/skills"))
            candidates.add(project.resolve(d).resolve("play-data-safety"));
        String home = System.getProperty("user.home");
        for (String d : List.of(".claude/skills", ".agents/skills", ".gemini/skills", ".codex/skills", ".cursor/skills", ".config/opencode/skills"))
            candidates.add(Path.of(home, d, "play-data-safety"));
        candidates.add(Path.of("").toAbsolutePath());
        candidates.add(Path.of("").toAbsolutePath().getParent());
        for (Path c : candidates) if (c != null && Files.isDirectory(c.resolve("sdks"))) return c;
        die("can't find the skill folder (with sdks/). Pass --skill-dir <path to play-data-safety skill>");
        return null;
    }

    static List<Map<String, Object>> loadSdks(Path dir) throws IOException {
        List<Map<String, Object>> all = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".json")).sorted().collect(Collectors.toList())) {
                try { all.add(map(new Json(Files.readString(p)).value())); }
                catch (RuntimeException e) { die("bad JSON in " + p + ": " + e.getMessage()); }
            }
        }
        if (all.isEmpty()) die("no SDK entries in " + dir);
        return all;
    }

    // ---------------------------------------------------------------- tiny JSON
    static final class Json {
        final String s; int i = 0;
        Json(String s) { this.s = s; }
        Object value() {
            ws();
            if (i >= s.length()) throw new RuntimeException("unexpected end");
            char c = s.charAt(i);
            switch (c) {
                case '{': { i++; Map<String, Object> m = new LinkedHashMap<>(); ws();
                    if (s.charAt(i) == '}') { i++; return m; }
                    while (true) { ws(); String k = str(); ws(); expect(':'); m.put(k, value()); ws();
                        if (s.charAt(i) == ',') { i++; continue; } expect('}'); return m; } }
                case '[': { i++; List<Object> l = new ArrayList<>(); ws();
                    if (s.charAt(i) == ']') { i++; return l; }
                    while (true) { l.add(value()); ws(); if (s.charAt(i) == ',') { i++; continue; } expect(']'); return l; } }
                case '"': return str();
                case 't': i += 4; return Boolean.TRUE;
                case 'f': i += 5; return Boolean.FALSE;
                case 'n': i += 4; return null;
                default: { int st = i; while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
                    if (st == i) throw new RuntimeException("unexpected '" + c + "' at " + i);
                    return Double.parseDouble(s.substring(st, i)); }
            }
        }
        String str() {
            expect('"'); StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> b.append('\n'); case 't' -> b.append('\t'); case 'r' -> b.append('\r');
                        case 'b' -> b.append('\b'); case 'f' -> b.append('\f');
                        case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                        default -> b.append(e);
                    }
                } else b.append(c);
            }
        }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        void expect(char c) { if (s.charAt(i) != c) throw new RuntimeException("expected '" + c + "' at " + i); i++; }
    }

    static String toJson(Object o, int ind) {
        String pad = "  ".repeat(ind + 1), end = "  ".repeat(ind);
        if (o == null) return "null";
        if (o instanceof String str) return quote(str);
        if (o instanceof Boolean || o instanceof Integer || o instanceof Long) return o.toString();
        if (o instanceof Double d) return d == Math.floor(d) && !d.isInfinite() ? String.valueOf(d.longValue()) : d.toString();
        if (o instanceof Map<?, ?> m) {
            if (m.isEmpty()) return "{}";
            return m.entrySet().stream().map(e -> pad + quote(String.valueOf(e.getKey())) + ": " + toJson(e.getValue(), ind + 1))
                    .collect(Collectors.joining(",\n", "{\n", "\n" + end + "}"));
        }
        if (o instanceof Collection<?> c) {
            if (c.isEmpty()) return "[]";
            return c.stream().map(x -> pad + toJson(x, ind + 1)).collect(Collectors.joining(",\n", "[\n", "\n" + end + "]"));
        }
        return quote(o.toString());
    }
    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\""); case '\\' -> b.append("\\\\"); case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r"); case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }

    // ---------------------------------------------------------------- helpers
    @SuppressWarnings("unchecked") static Map<String, Object> map(Object o) { return o instanceof Map ? (Map<String, Object>) o : Map.of(); }
    @SuppressWarnings("unchecked") static List<Object> list(Object o) { return o instanceof List ? (List<Object>) o : List.of(); }
    static boolean notIgnoredDir(Path p) {
        String s = p.toString().replace('\\', '/');
        return !s.contains("/build/") && !s.contains("/.gradle/") && !s.contains("/node_modules/") && !s.contains("/.git/");
    }
    static String need(String[] a, int i) { if (i >= a.length) usage(); return a[i]; }
    static void die(String m) { System.err.println("ERROR: " + m); System.exit(1); }
    static void usage() {
        System.err.println("usage: java Scan.java <project-dir> [--module :app] [--configuration releaseRuntimeClasspath]\n"
                + "                      [--out <dir>] [--no-gradle] [--check <play_data_safety.md>] [--skill-dir <dir>]");
        System.exit(2);
    }
}
