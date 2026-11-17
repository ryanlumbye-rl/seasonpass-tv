package dk.seasonpass.tvhome;

import android.content.Context;
import android.util.Xml;

import org.json.JSONArray;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Nyheder på pauseskærmen (0.8.0): en overskrift ad gangen under datoen, fra en nyhedstjenestes åbne RSS-feed. Kun overskrifterne
 * hentes og vises; intet gemmes ud over de seneste overskrifter i appens cache, og intet sendes af sted. Slået fra som standard.
 * Hvert land har to tjenester; Færøerne og Grønland kan vælge mellem to danske og egne (kun dem, der har et åbent feed).
 */
final class News {

    private News() { }

    static final class Source {
        final String id, name, url;
        Source(String id, String name, String url) { this.id = id; this.name = name; this.url = url; }
    }

    private static final Source DR = new Source("dr", "DR Nyheder", "https://www.dr.dk/nyheder/service/feeds/allenyheder");
    private static final Source TV2 = new Source("tv2dk", "TV 2 Nyheder", "https://feeds.services.tv2.dk/api/feeds/nyheder/rss");
    private static final Source SVT = new Source("svt", "SVT Nyheter", "https://www.svt.se/nyheter/rss.xml");
    private static final Source AFTONBLADET = new Source("aftonbladet", "Aftonbladet", "https://rss.aftonbladet.se/rss2/small/pages/sections/senastenytt/");
    private static final Source NRK = new Source("nrk", "NRK", "https://www.nrk.no/toppsaker.rss");
    private static final Source VG = new Source("vg", "VG", "https://www.vg.no/rss/feed");
    private static final Source YLE = new Source("yle", "Yle Uutiset", "https://feeds.yle.fi/uutiset/v1/majorHeadlines/YLE_UUTISET.rss");
    private static final Source HS = new Source("hs", "Helsingin Sanomat", "https://www.hs.fi/rss/tuoreimmat.xml");
    private static final Source RUV = new Source("ruv", "RÚV", "https://www.ruv.is/rss/frettir");
    private static final Source VISIR = new Source("visir", "Vísir", "https://www.visir.is/rss/allt");
    private static final Source KVF = new Source("kvf", "Kringvarp Føroya", "https://kvf.fo/rss");
    private static final Source KNR = new Source("knr", "KNR", "https://knr.gl/rss.xml");

    /** Landet ud fra tidszonen (telefonens land) eller, uden den, sproget: DK, SE, NO, FI, IS, FO eller GL; ellers DK. */
    static String country(Strings s) {
        String z = s.tz.getID();
        switch (z) {
            case "Europe/Stockholm": return "SE";
            case "Europe/Oslo": return "NO";
            case "Europe/Helsinki": return "FI";
            case "Atlantic/Reykjavik": return "IS";
            case "Atlantic/Faroe": return "FO";
            case "America/Nuuk": case "America/Godthab": case "America/Thule": case "America/Danmarkshavn": case "America/Scoresbysund": return "GL";
            case "Europe/Copenhagen": return "DK";
            default: break;
        }
        switch (s.code) {
            case "sv": return "SE";
            case "no": case "nb": return "NO";
            case "fi": return "FI";
            case "is": return "IS";
            case "fo": return "FO";
            case "kl": return "GL";
            default: return "DK";
        }
    }

    /** De tjenester, man kan vælge mellem i landet; egne først, så de danske for Færøerne og Grønland. */
    static List<Source> sources(String country) {
        List<Source> l = new ArrayList<>();
        switch (country) {
            case "SE": l.add(SVT); l.add(AFTONBLADET); break;
            case "NO": l.add(NRK); l.add(VG); break;
            case "FI": l.add(YLE); l.add(HS); break;
            case "IS": l.add(RUV); l.add(VISIR); break;
            case "FO": l.add(KVF); l.add(DR); l.add(TV2); break;
            case "GL": l.add(KNR); l.add(DR); l.add(TV2); break;
            default: l.add(DR); l.add(TV2); break;
        }
        return l;
    }

    /** Den valgte tjeneste; er valget ikke i landets liste (fx efter en rejse), bruges landets første. */
    static Source chosen(Context c, Strings s) {
        List<Source> l = sources(country(s));
        String id = Prefs.newsSource(c);
        for (Source x : l) if (x.id.equals(id)) return x;
        return l.get(0);
    }

    // ---- Hentning og tolkning ----

    private static final long FRESH_MS = 20 * 60 * 1000L;
    private static final int MAX_ITEMS = 12, MAX_BYTES = 1024 * 1024;

    private static File file(Context c, Source s) { return new File(c.getCacheDir(), "news-" + s.id + ".json"); }

    static boolean stale(Context c, Source s) {
        File f = file(c, s);
        return !f.isFile() || System.currentTimeMillis() - f.lastModified() > FRESH_MS;
    }

    /** De seneste hentede overskrifter (tom liste, hvis der ikke er hentet nogen). */
    static List<String> cached(Context c, Source s) {
        List<String> out = new ArrayList<>();
        try {
            File f = file(c, s);
            if (!f.isFile()) return out;
            JSONArray a = new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) { }
        return out;
    }

    /** Henter feedet (blokerende; kald fra en baggrundstråd). Ved fejl beholdes de gamle overskrifter. */
    static List<String> refresh(Context c, Source s) {
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(s.url).openConnection();
            h.setConnectTimeout(8000);
            h.setReadTimeout(10000);
            h.setRequestProperty("User-Agent", "SeasonPass-TV");
            h.setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml");
            if (h.getResponseCode() != 200) return cached(c, s);
            byte[] body = read(h.getInputStream());
            List<String> titles = parse(body);
            if (titles.isEmpty()) return cached(c, s);
            Files.write(file(c, s).toPath(), new JSONArray(titles).toString().getBytes(StandardCharsets.UTF_8));
            return titles;
        } catch (Exception e) {
            return cached(c, s);
        }
    }

    private static byte[] read(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) throw new IllegalStateException("feedet er for stort");
            }
            return out.toByteArray();
        }
    }

    /** Overskrifterne i RSS (item) og Atom (entry); kanalens egen titel springes over. */
    static List<String> parse(byte[] raw) throws Exception {
        // Nogle feeds (fx KNR) bruger HTML-navne som &nbsp;, som XML ikke kender; de bliver til mellemrum. Navnene er ASCII,
        // så tegnene ellers rører vi ikke ved (bytes ind, samme bytes ud).
        byte[] xml = new String(raw, StandardCharsets.ISO_8859_1)
                .replaceAll("&(?!(?:amp|lt|gt|quot|apos|#[0-9]+|#x[0-9a-fA-F]+);)[A-Za-z][A-Za-z0-9]*;", " ")
                .getBytes(StandardCharsets.ISO_8859_1);
        List<String> out = new ArrayList<>();
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            p.setInput(new ByteArrayInputStream(xml), null);   // tegnsættet læses fra feedets egen erklæring
            int depthInItem = 0;
            for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT && out.size() < MAX_ITEMS; e = p.next()) {
                if (e == XmlPullParser.START_TAG) {
                    String n = p.getName().toLowerCase(Locale.ROOT);
                    if (n.equals("item") || n.equals("entry")) depthInItem++;
                    else if (depthInItem > 0 && n.equals("title")) {
                        String t = clean(p.nextText());
                        if (!t.isEmpty()) out.add(t);
                    }
                } else if (e == XmlPullParser.END_TAG) {
                    String n = p.getName().toLowerCase(Locale.ROOT);
                    if (n.equals("item") || n.equals("entry")) depthInItem = Math.max(0, depthInItem - 1);
                }
            }
        } catch (Exception ignored) {
            // Et feed, der ikke er helt gyldig XML (fx KNR): behold de overskrifter, der nåede at komme med, ellers læs dem ud som tekst.
        }
        if (out.isEmpty()) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?s)<(?:item|entry)\\b.*?<title[^>]*>(.*?)</title>")
                    .matcher(new String(xml, StandardCharsets.UTF_8));
            while (m.find() && out.size() < MAX_ITEMS) {
                String t = m.group(1).replace("<![CDATA[", "").replace("]]>", "");
                t = clean(t);
                if (!t.isEmpty()) out.add(t);
            }
        }
        return out;
    }

    /** Ren tekst: ingen mærker, én slags mellemrum, ikke for lang. */
    static String clean(String raw) {
        if (raw == null) return "";
        String t = raw;
        if (t.indexOf('<') >= 0 || t.indexOf('&') >= 0) t = android.text.Html.fromHtml(t, android.text.Html.FROM_HTML_MODE_LEGACY).toString();
        t = t.replace('­', ' ').replaceAll("\\s+", " ").trim();
        return t.length() > 180 ? t.substring(0, 177).trim() + "…" : t;
    }
}
