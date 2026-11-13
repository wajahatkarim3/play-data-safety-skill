// DataSafetyCsv — deterministic Google Play Data safety CSV generator.
// Part of play-data-safety (MIT). Originally written for NowKit — https://kit.shipkaro.dev
//
// Turns the human-readable single source of truth (play_data_safety.md) into the
// machine CSV that Play's "Import from CSV" expects (play_data_safety.csv), using
// Google's official reference template (data_safety_sample_reference.csv) for the exact row
// schema. Runs a mandatory self-check and EXITS NON-ZERO if the CSV would not faithfully match
// the .md — so a drifted or under-declared Data safety form can never be shipped silently.
//
// No build step, no dependencies. Requires only JDK 17+:
//   java DataSafetyCsv.java <play_data_safety.md> <reference/play-data-safety-template.csv> <out.csv>
//
// Why this exists: the old flow asked the agent to hand-toggle ~40 data-type rows + their usage
// blocks in a 780-row machine CSV, separately from the .md table. That drifted (published Data
// safety under-declared, contradicting the app's own privacy policy — exactly what Play strikes
// apps for). This makes the .md the single source of truth and derives the CSV from it, verified.

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class DataSafetyCsv {

    // ---- Canonical purpose label -> Play purpose Response ID ------------------------------------
    // The .md "Purpose" column MUST use these exact tokens (case-insensitive). Anything else is a
    // hard error, so purposes can never be silently dropped or misfiled.
    // Separate MULTIPLE purposes in the .md Purpose column with ';' (NOT ',' — one canonical
    // purpose contains commas). Matching is case-insensitive; short aliases are accepted.
    static final Map<String, String> PURPOSES = new LinkedHashMap<>();
    static {
        PURPOSES.put("app functionality", "PSL_APP_FUNCTIONALITY");
        PURPOSES.put("analytics", "PSL_ANALYTICS");
        PURPOSES.put("developer communications", "PSL_DEVELOPER_COMMUNICATIONS");
        PURPOSES.put("fraud prevention, security, and compliance", "PSL_FRAUD_PREVENTION_SECURITY");
        PURPOSES.put("fraud prevention", "PSL_FRAUD_PREVENTION_SECURITY");   // alias
        PURPOSES.put("security and compliance", "PSL_FRAUD_PREVENTION_SECURITY"); // alias
        PURPOSES.put("advertising or marketing", "PSL_ADVERTISING");
        PURPOSES.put("advertising", "PSL_ADVERTISING");                     // alias
        PURPOSES.put("personalization", "PSL_PERSONALIZATION");
        PURPOSES.put("account management", "PSL_ACCOUNT_MANAGEMENT");
    }
    // The canonical purpose tokens to show in error messages (aliases hidden to avoid noise).
    static final String PURPOSE_HELP = "app functionality; analytics; developer communications; "
            + "fraud prevention, security, and compliance; advertising or marketing; personalization; "
            + "account management";

    static final List<String> ERRORS = new ArrayList<>();
    static final Set<String> VALID_LABELS = new TreeSet<>();
    static boolean unknownTypeSeen = false;
    static void fail(String msg) { ERRORS.add(msg); }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.err.println("usage: java DataSafetyCsv.java <play_data_safety.md> "
                    + "<reference_template.csv> <out.csv>");
            System.exit(2);
        }
        Path mdPath = Path.of(args[0]);
        Path tplPath = Path.of(args[1]);
        Path outPath = Path.of(args[2]);

        List<String[]> template = parseCsv(Files.readString(tplPath));
        if (template.isEmpty()) { System.err.println("ERROR: empty template " + tplPath); System.exit(2); }

        // Build canonical data-type label -> (questionId, responseId) from the template itself, so
        // the taxonomy stays correct as Google evolves the reference file (no hardcoded 40-row map).
        Map<String, String[]> typeByLabel = new LinkedHashMap<>();  // normLabel -> {qid, rid}
        for (String[] r : template) {
            if (r[0].startsWith("PSL_DATA_TYPES_")) {
                typeByLabel.put(normLabel(r[4]), new String[]{r[0], r[1]});
                VALID_LABELS.add(r[4]);
            }
        }

        Md md = parseMd(Files.readString(mdPath), typeByLabel);

        // If parsing already found hard problems, stop before writing anything.
        if (!ERRORS.isEmpty()) { report(false, md, null); System.exit(1); }

        // ---- Compute the Response value for every template row --------------------------------
        boolean anyCollected = !md.types.isEmpty();
        // Does the app let users create an account? PSL_ACM_NONE means "no account creation",
        // which hides Play's account-deletion-URL question entirely.
        boolean hasAccounts = !md.acm.contains("PSL_ACM_NONE");
        // responseId -> collected type (usage rows are keyed by responseId inside the qid)
        Map<String, DataType> byRid = new LinkedHashMap<>();
        for (DataType t : md.types) {
            if (byRid.put(t.responseId, t) != null) {
                fail("duplicate data type responseId " + t.responseId + " (label '" + t.label + "')");
            }
        }

        List<String[]> out = new ArrayList<>();
        out.add(template.get(0)); // header
        int trueTypeRows = 0;
        for (int i = 1; i < template.size(); i++) {
            String[] r = template.get(i).clone();
            String qid = r[0], rid = r[1];
            String v = "";  // clear by default — never inherit the template's baked-in examples

            if (qid.equals("PSL_DATA_COLLECTION_COLLECTS_PERSONAL_DATA")) {
                v = anyCollected ? "true" : "false";
            } else if (qid.equals("PSL_DATA_COLLECTION_ENCRYPTED_IN_TRANSIT")) {
                v = md.encrypted ? "true" : "false";
            } else if (qid.equals("PSL_SUPPORTED_ACCOUNT_CREATION_METHODS")) {
                v = md.acm.contains(rid) ? "true" : "";
            } else if (qid.equals("PSL_HAS_OUTSIDE_APP_ACCOUNTS")) {
                // Nested under "My app does not allow users to create an account". Play shows it
                // whenever PSL_ACM_NONE is ticked and blocks Next until it's answered, even though
                // the template marks it OPTIONAL. Answered from the optional .md line
                // "- **Accounts created outside the app:** Yes/No" (default No). Left blank when the
                // app does have accounts.
                v = hasAccounts ? "" : (md.outsideAccounts ? "true" : "false");
            } else if (qid.equals("PSL_SUPPORT_DATA_DELETION_BY_USER")) {
                v = rid.equals("DATA_DELETION_YES") ? "true" : "";
            } else if (qid.equals("PSL_ACCOUNT_DELETION_URL")) {
                // Play only asks for an ACCOUNT deletion URL when the app lets users create an
                // account. With PSL_ACM_NONE the question is hidden, and answering a hidden
                // question fails the import with "You cannot answer PSL_ACCOUNT_DELETION_URL".
                v = hasAccounts ? md.deletionUrl : "";
            } else if (qid.equals("PSL_DATA_DELETION_URL")) {
                // Not account-gated — it pairs with PSL_SUPPORT_DATA_DELETION_BY_USER.
                v = md.deletionUrl;
            } else if (qid.startsWith("PSL_DATA_TYPES_")) {
                DataType t = byRid.get(rid);
                boolean on = t != null && t.questionId.equals(qid);
                v = on ? "true" : "";
                if (on) trueTypeRows++;
            } else if (qid.startsWith("PSL_DATA_USAGE_RESPONSES:")) {
                String[] parts = qid.split(":", 3);       // RESPONSES : <RID> : <SUB>
                DataType t = parts.length == 3 ? byRid.get(parts[1]) : null;
                if (t != null) v = usageValue(parts[2], rid, t);
            }
            r[2] = v;
            out.add(r);
        }

        // ---- Mandatory self-check --------------------------------------------------------------
        if (anyCollected && "false".equals(valueOf(out, "PSL_DATA_COLLECTION_COLLECTS_PERSONAL_DATA"))) {
            fail("collects-personal-data is false but " + md.types.size() + " data types are collected");
        }
        if (trueTypeRows != md.types.size()) {
            fail("CSV declares " + trueTypeRows + " data-type rows but the .md table lists "
                    + md.types.size() + " collected types — they must match exactly");
        }
        String accUrl = valueOf(out, "PSL_ACCOUNT_DELETION_URL");
        String datUrl = valueOf(out, "PSL_DATA_DELETION_URL");
        if (hasAccounts) {
            if (accUrl == null || accUrl.isBlank())
                fail("PSL_ACCOUNT_DELETION_URL is empty but the app supports account creation");
        } else if (accUrl != null && !accUrl.isBlank()) {
            // The inverse is the actual Play rejection: answering a question Play never asked.
            fail("PSL_ACCOUNT_DELETION_URL is set but the app has no account creation (PSL_ACM_NONE) — "
                    + "Play rejects the import with \"You cannot answer PSL_ACCOUNT_DELETION_URL\"");
        }
        if (datUrl == null || datUrl.isBlank()) fail("PSL_DATA_DELETION_URL is empty (Play rejects the import)");
        if (md.acm.isEmpty()) fail("no account-creation method resolved from the .md");
        if (!hasAccounts) {
            // Play nests this under "does not allow users to create an account" and blocks Next
            // until it's answered, even though the template column says OPTIONAL.
            String outside = valueOf(out, "PSL_HAS_OUTSIDE_APP_ACCOUNTS");
            if (outside == null || outside.isBlank())
                fail("PSL_HAS_OUTSIDE_APP_ACCOUNTS is empty with PSL_ACM_NONE — the form imports "
                        + "but Play won't let you past the page until it's answered");
        }

        // Per-type structural checks: exactly one collect/share choice, one user-control choice, >=1 purpose.
        for (DataType t : md.types) {
            int cs = boolCount(out, "PSL_DATA_USAGE_RESPONSES:" + t.responseId + ":PSL_DATA_USAGE_COLLECTION_AND_SHARING");
            int uc = boolCount(out, "PSL_DATA_USAGE_RESPONSES:" + t.responseId + ":DATA_USAGE_USER_CONTROL");
            int cp = boolCount(out, "PSL_DATA_USAGE_RESPONSES:" + t.responseId + ":DATA_USAGE_COLLECTION_PURPOSE");
            int csExpected = t.shared ? 2 : 1;   // collected-only = 1 row; collected+shared = both rows
            if (cs != csExpected) fail("'" + t.label + "': collected/shared choice has " + cs
                    + " selection(s), expected " + csExpected
                    + (t.shared ? " (Collected + Shared)" : " (Collected)"));
            if (uc != 1) fail("'" + t.label + "': required-or-optional choice not set exactly once");
            if (cp < 1)  fail("'" + t.label + "': no collection purpose set");
            if (t.shared) {
                int sp = boolCount(out, "PSL_DATA_USAGE_RESPONSES:" + t.responseId + ":DATA_USAGE_SHARING_PURPOSE");
                if (sp < 1) fail("'" + t.label + "': marked Shared but no sharing purpose set");
            }
        }

        if (!ERRORS.isEmpty()) { report(false, md, null); System.exit(1); }

        Files.writeString(outPath, serializeCsv(out));
        report(true, md, outPath.toString());
    }

    // Compute the Response value for one usage sub-question of a collected type.
    static String usageValue(String sub, String rid, DataType t) {
        switch (sub) {
            case "PSL_DATA_USAGE_COLLECTION_AND_SHARING":
                // Play's question is "Is this data collected, shared, or BOTH?" and the row type is
                // MULTIPLE_CHOICE — "both" is expressed by setting BOTH responses to true. Every type
                // that reaches here is Collected=Yes (parseTypeRow drops the rest), so ONLY_COLLECTED
                // is always true; ONLY_SHARED is added on top when the .md marks it Shared.
                // (Marking a collected type as shared-only makes Play reject the import with
                // "You cannot answer …:PSL_DATA_USAGE_EPHEMERAL" — that question exists only for
                // collected data — and under-declares collection.)
                if (rid.equals("PSL_DATA_USAGE_ONLY_COLLECTED")) return "true";
                if (rid.equals("PSL_DATA_USAGE_ONLY_SHARED"))    return t.shared ? "true" : "";
                return "";
            case "PSL_DATA_USAGE_EPHEMERAL":
                return t.ephemeral ? "true" : "false";
            case "DATA_USAGE_USER_CONTROL":
                if (rid.equals("PSL_DATA_USAGE_USER_CONTROL_OPTIONAL")) return t.optional ? "true" : "";
                if (rid.equals("PSL_DATA_USAGE_USER_CONTROL_REQUIRED")) return t.optional ? "" : "true";
                return "";
            case "DATA_USAGE_COLLECTION_PURPOSE":
                return t.purposes.contains(rid) ? "true" : "";
            case "DATA_USAGE_SHARING_PURPOSE":
                return (t.shared && t.purposes.contains(rid)) ? "true" : "";
            default:
                return "";
        }
    }

    // ---- .md parsing --------------------------------------------------------------------------
    static class DataType {
        String label, questionId, responseId;
        boolean ephemeral, optional, shared;
        Set<String> purposes = new LinkedHashSet<>();
    }
    static class Md {
        boolean encrypted;
        String deletionUrl = "";
        Set<String> acm = new LinkedHashSet<>();
        boolean outsideAccounts = false;
        List<DataType> types = new ArrayList<>();
    }

    static Md parseMd(String text, Map<String, String[]> typeByLabel) {
        Md md = new Md();
        String[] lines = text.split("\r?\n");
        boolean encFound = false, urlFound = false, acmFound = false, inTable = false;
        for (String raw : lines) {
            String line = raw.strip();
            String low = line.toLowerCase(Locale.ROOT);
            if (line.startsWith("- **") && line.contains("TODO"))
                fail("unanswered question (TODO) in the .md: " + line);

            if (low.contains("encrypted in transit")) {
                encFound = true;
                md.encrypted = endsAffirmative(line);
            } else if (low.startsWith("- **data deletion url:")) {
                urlFound = true;
                md.deletionUrl = firstUrl(line);
                if (md.deletionUrl.isEmpty())
                    fail("'Data deletion URL' line has no https URL: " + line);
            } else if (low.startsWith("- **accounts created outside the app:")) {
                md.outsideAccounts = endsAffirmative(line.replaceFirst("(?i).*outside the app:\\*\\*", "?"));
            } else if (low.startsWith("- **account creation method:")) {
                acmFound = true;
                md.acm.addAll(mapAcm(line.substring(line.indexOf(':') + 1)));
                if (md.acm.isEmpty())
                    fail("could not resolve an account-creation method from: " + line);
            }

            if (line.startsWith("|")) {
                String[] cells = splitRow(line);
                if (cells.length >= 1 && cells[0].equalsIgnoreCase("Data type")) { inTable = true; continue; }
                if (isSeparatorRow(cells)) continue;
                if (inTable) {
                    if (cells.length < 6) { fail("table row has too few columns: " + line); continue; }
                    parseTypeRow(cells, md, typeByLabel);
                }
            } else if (inTable && line.isEmpty()) {
                inTable = false;
            }
        }
        if (!encFound) fail("missing 'encrypted in transit' line in the .md security section");
        if (!urlFound) fail("missing '- **Data deletion URL:** <url>' line in the .md security section");
        if (!acmFound) fail("missing '- **Account creation method:** <...>' line in the .md security section");
        if (md.types.isEmpty()) fail("no collected data types found in the .md '## Data types collected' table");
        return md;
    }

    static void parseTypeRow(String[] cells, Md md, Map<String, String[]> typeByLabel) {
        // columns: 0 Data type | 1 Collected | 2 Ephemeral | 3 Required/choose | 4 Shared | 5 Purpose | 6 Source
        String collected = cells[1].strip().toLowerCase(Locale.ROOT);
        if (!collected.startsWith("y")) return; // only rows the app actually collects

        DataType t = new DataType();
        t.label = cells[0].strip();
        String[] ids = typeByLabel.get(normLabel(t.label));
        if (ids == null) {
            fail("unknown data type '" + t.label + "' — not one of Google's canonical types (see list below)");
            unknownTypeSeen = true;
            return;
        }
        t.questionId = ids[0];
        t.responseId = ids[1];
        t.ephemeral = cells[2].strip().toLowerCase(Locale.ROOT).startsWith("y");
        String ctrl = cells[3].strip().toLowerCase(Locale.ROOT);
        if (ctrl.contains("choose") || ctrl.contains("users can")) t.optional = true;
        else if (ctrl.contains("required")) t.optional = false;
        else { fail("'" + t.label + "': column 'Required, or users choose' unclear: '" + cells[3] + "'"); return; }
        t.shared = cells[4].strip().toLowerCase(Locale.ROOT).startsWith("y");

        for (String p : cells[5].split(";")) {   // ';' separates multiple purposes (NOT ',')
            String key = p.strip().toLowerCase(Locale.ROOT);
            if (key.isEmpty()) continue;
            String id = PURPOSES.get(key);
            if (id == null) {
                fail("'" + t.label + "': unknown purpose '" + p.strip() + "' — must be one (or ';'-separated) of: "
                        + PURPOSE_HELP);
                continue;
            }
            t.purposes.add(id);
        }
        if (t.purposes.isEmpty()) fail("'" + t.label + "': no valid purpose in column 6");
        md.types.add(t);
    }

    static Set<String> mapAcm(String text) {
        String s = text.toLowerCase(Locale.ROOT);
        Set<String> ids = new LinkedHashSet<>();
        if (s.contains("none") || s.contains("no account") || s.contains("does not") || s.contains("no sign"))
            ids.add("PSL_ACM_NONE");
        boolean pwd = s.contains("password");
        boolean otherAuth = s.contains("other auth");
        if (s.contains("oauth")) ids.add("PSL_ACM_OAUTH");
        if (pwd && otherAuth) ids.add("PSL_ACM_USER_ID_PASSWORD_OTHER_AUTH");
        else if (pwd) ids.add("PSL_ACM_USER_ID_PASSWORD");
        else if (otherAuth) ids.add("PSL_ACM_USER_ID_OTHER_AUTH");
        return ids;
    }

    // ---- small helpers -------------------------------------------------------------------------
    static boolean endsAffirmative(String line) {
        String s = line.toLowerCase(Locale.ROOT);
        int q = s.lastIndexOf('?');
        String tail = q >= 0 ? s.substring(q + 1) : s;
        return tail.contains("yes") || tail.matches(".*\\btrue\\b.*");
    }
    static String firstUrl(String line) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("https?://\\S+").matcher(line);
        if (!m.find()) return "";
        String u = m.group();
        while (u.endsWith(".") || u.endsWith(")") || u.endsWith(",")) u = u.substring(0, u.length() - 1);
        return u;
    }
    static String normLabel(String s) {
        String x = s.replace('→', '/');      // → to /
        x = x.replaceAll("\\(.*?\\)", " ");        // drop parentheticals
        x = x.replaceAll("[\\s/]+", " ").strip();  // collapse separators + spaces
        return x.toLowerCase(Locale.ROOT);
    }
    static String[] splitRow(String line) {
        String s = line;
        if (s.startsWith("|")) s = s.substring(1);
        if (s.endsWith("|")) s = s.substring(0, s.length() - 1);
        String[] parts = s.split("\\|", -1);
        for (int i = 0; i < parts.length; i++) parts[i] = parts[i].strip();
        return parts;
    }
    static boolean isSeparatorRow(String[] cells) {
        for (String c : cells) if (!c.replaceAll("[-: ]", "").isEmpty()) return false;
        return cells.length > 0;
    }
    static int boolCount(List<String[]> rows, String qid) {
        int n = 0;
        for (String[] r : rows) if (r[0].equals(qid) && r[2].equals("true")) n++;
        return n;
    }
    static String valueOf(List<String[]> rows, String qid) {
        for (String[] r : rows) if (r[0].equals(qid)) return r[2];
        return null;
    }
    static void report(boolean ok, Md md, String outFile) {
        if (ok) {
            System.out.println("PASS — Data safety CSV written to " + outFile);
            System.out.println("  " + md.types.size() + " data type(s), encrypted-in-transit="
                    + md.encrypted + ", deletion URL set, account method=" + md.acm);
            for (DataType t : md.types)
                System.out.println("    • " + t.label + "  [" + (t.shared ? "collected+shared" : "collected")
                        + (t.optional ? ", optional" : ", required") + (t.ephemeral ? ", ephemeral" : "")
                        + ", " + t.purposes + "]");
            System.out.println("  Safe to import in Play Console → Data safety → Import from CSV.");
        } else {
            System.out.println("FAIL — CSV NOT written. Fix play_data_safety.md (the source of truth), then re-run:");
            for (String e : ERRORS) System.out.println("  ✗ " + e);
            if (unknownTypeSeen) {
                System.out.println("\n  Canonical data-type labels (use these verbatim in the .md table's 'Data type' column):");
                for (String l : VALID_LABELS) System.out.println("      " + l);
            }
        }
    }

    // ---- RFC 4180 CSV parse/serialize (preserves the template's exact quoting) -----------------
    static List<String[]> parseCsv(String text) {
        List<String[]> rows = new ArrayList<>();
        List<String> field = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQ = false;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (inQ) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else inQ = false;
                } else cur.append(c);
            } else {
                if (c == '"') inQ = true;
                else if (c == ',') { field.add(cur.toString()); cur.setLength(0); }
                else if (c == '\n') { field.add(cur.toString()); cur.setLength(0); rows.add(pad(field)); field.clear(); }
                else if (c == '\r') { /* skip, handled by \n */ }
                else cur.append(c);
            }
        }
        if (cur.length() > 0 || !field.isEmpty()) { field.add(cur.toString()); rows.add(pad(field)); }
        return rows;
    }
    static String[] pad(List<String> field) {
        while (field.size() < 5) field.add("");
        return field.toArray(new String[0]);
    }
    static String serializeCsv(List<String[]> rows) {
        StringBuilder sb = new StringBuilder();
        for (String[] r : rows) {
            for (int i = 0; i < r.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(csvField(r[i] == null ? "" : r[i]));
            }
            sb.append('\n');
        }
        return sb.toString();
    }
    static String csvField(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r"))
            return '"' + s.replace("\"", "\"\"") + '"';
        return s;
    }
}
