// ValidateSdks — check every SDK entry in skills/play-data-safety/sdks/ against SCHEMA.md.
//   java tools/ValidateSdks.java [skills/play-data-safety]
// Also prints the README table (between the SDK-TABLE markers) with --readme README.md.
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

@SuppressWarnings("unchecked")
public class ValidateSdks {
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


    static final Set<String> PURPOSES = Set.of("App functionality", "Analytics", "Developer communications",
            "Fraud prevention, security, and compliance", "Advertising or marketing", "Personalization", "Account management");
    static final Set<String> ROLES = Set.of("service-provider", "third-party", "mixed");
    static final Set<String> CONF = Set.of("high", "medium", "low");

    public static void main(String[] a) throws Exception {
        Path skill = Path.of(a.length > 0 && !a[0].startsWith("--") ? a[0] : "skills/play-data-safety");
        Path readme = null;
        for (int i = 0; i < a.length; i++) if (a[i].equals("--readme")) readme = Path.of(a[i + 1]);

        Set<String> labels = new HashSet<>();
        for (String l : Files.readAllLines(skill.resolve("reference/play-data-safety-template.csv")))
            if (l.startsWith("PSL_DATA_TYPES_")) labels.add(l.substring(l.lastIndexOf(',') + 1).strip());

        List<String> errors = new ArrayList<>();
        List<Map<String, Object>> all = new ArrayList<>();
        Map<String, String> coordOwner = new HashMap<>();
        List<Path> files;
        try (Stream<Path> s = Files.list(skill.resolve("sdks"))) {
            files = s.filter(p -> p.toString().endsWith(".json")).sorted().collect(Collectors.toList());
        }
        for (Path f : files) {
            String id = f.getFileName().toString().replace(".json", "");
            Map<String, Object> e;
            try { e = (Map<String, Object>) new Json(Files.readString(f)).value(); }
            catch (Exception ex) { errors.add(id + ": invalid JSON: " + ex.getMessage()); continue; }
            all.add(e);
            if (!id.equals(e.get("id"))) errors.add(id + ": id '" + e.get("id") + "' does not match file name");
            for (String k : List.of("name", "match", "role", "dataTypes", "sources", "confidence"))
                if (!e.containsKey(k)) errors.add(id + ": missing '" + k + "'");
            if (e.get("role") != null && !ROLES.contains(e.get("role"))) errors.add(id + ": bad role '" + e.get("role") + "'");
            if (e.get("confidence") != null && !CONF.contains(e.get("confidence"))) errors.add(id + ": bad confidence '" + e.get("confidence") + "'");
            for (Object m : list(e.get("match"))) {
                String c = String.valueOf(m);
                if (!c.matches("[A-Za-z0-9_.\\-]+:[A-Za-z0-9_.\\-]*\\*?")) errors.add(id + ": bad match '" + c + "' (want group:artifact)");
                String prev = coordOwner.put(c, id);
                if (prev != null) errors.add(id + ": match '" + c + "' also claimed by " + prev);
            }
            for (Object o : list(e.get("dataTypes"))) {
                Map<String, Object> d = (Map<String, Object>) o;
                String t = String.valueOf(d.get("type"));
                if (!labels.contains(t)) errors.add(id + ": unknown data type '" + t + "'");
                List<Object> ps = list(d.get("purposes"));
                if (ps.isEmpty()) errors.add(id + ": '" + t + "' has no purposes");
                for (Object p : ps) if (!PURPOSES.contains(p)) errors.add(id + ": '" + t + "' bad purpose '" + p + "'");
                Object su = d.get("sourceUrl");
                if (su == null || !String.valueOf(su).startsWith("http")) errors.add(id + ": '" + t + "' has no sourceUrl");
            }
            for (Object o : list(e.get("sources"))) {
                Map<String, Object> s = (Map<String, Object>) o;
                if (!String.valueOf(s.get("url")).startsWith("http")) errors.add(id + ": source without url");
                if (!String.valueOf(s.get("checked")).matches("\\d{4}-\\d{2}-\\d{2}")) errors.add(id + ": source without checked date");
            }
        }
        System.out.println(files.size() + " SDK entries checked.");
        if (!errors.isEmpty()) { errors.forEach(x -> System.out.println("  ✗ " + x)); System.exit(1); }
        System.out.println("PASS");

        if (readme != null) {
            StringBuilder t = new StringBuilder("| SDK | Vendor | Role | Confidence | Checked |\n|---|---|---|---|---|\n");
            all.sort(Comparator.comparing(x -> String.valueOf(x.get("name")).toLowerCase()));
            for (Map<String, Object> e : all) {
                Map<String, Object> s = list(e.get("sources")).isEmpty() ? Map.of() : (Map<String, Object>) list(e.get("sources")).get(0);
                String role = "third-party".equals(e.get("role")) ? "Shares data" : "mixed".equals(e.get("role")) ? "Depends" : "Service provider";
                t.append("| [").append(e.get("name")).append("](").append(s.getOrDefault("url", "")).append(") | ")
                 .append(e.getOrDefault("vendor", "")).append(" | ").append(role).append(" | ")
                 .append(e.get("confidence")).append(" | ").append(s.getOrDefault("checked", "")).append(" |\n");
            }
            String r = Files.readString(readme);
            int s = r.indexOf("<!-- SDK-TABLE:START -->"), en = r.indexOf("<!-- SDK-TABLE:END -->");
            r = r.substring(0, s) + "<!-- SDK-TABLE:START -->\n" + all.size() + " SDKs.\n\n" + t + r.substring(en);
            Files.writeString(readme, r);
            System.out.println("README table updated.");
        }
    }
    static List<Object> list(Object o) { return o instanceof List ? (List<Object>) o : List.of(); }
}
