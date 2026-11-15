package dk.seasonpass.tvhome;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Fjernsynets tekster og sprog (0.4.1): telefonen sender sprogkode, Intl-sprog, tidszone og alle tekster med feedet
 * ("lang" og "strings", SeasonPass 0.12.6), så fjernsynet følger telefonens sprog på alle appens otte sprog. Tekster
 * har pladsholdere som {n}; {@link #t} sætter dem ind. Mangler en tekst (ældre telefon-app uden sprog), står den danske her.
 * Nøglerne er telefonens: 'tvhome.*' og nogle få delte (tid, sæson, pause).
 */
final class Strings {

    private static final Map<String, String> D = new HashMap<>();
    static {
        D.put("time.readyNow", "Klar nu");
        D.put("time.today", "I dag");
        D.put("time.tomorrow", "I morgen");
        D.put("time.withTime", "{day} kl. {time}");
        D.put("progress.season", "Sæson {s}");
        D.put("discover.because", "Fordi du følger {name}");
        D.put("mode.paused", "På pause");
        D.put("home.soon", "Kommer snart");
        D.put("common.close", "Luk");
        D.put("show.noDesc", "Ingen beskrivelse endnu.");
        D.put("tvhome.next", "Næste afsnit");
        D.put("tvhome.yourApps", "Dine apps");
        D.put("tvhome.discover", "Opdag serier");
        D.put("tvhome.play", "Afspil");
        D.put("tvhome.moreInfo", "Mere info");
        D.put("tvhome.settings", "Indstillinger");
        D.put("tvhome.apps", "Apps");
        D.put("tvhome.allApps", "Alle apps  ›");
        D.put("tvhome.seen", "Har set den");
        D.put("tvhome.nope", "Ikke for mig");
        D.put("tvhome.help", "Har set den: du følger den og får besked om nye sæsoner.\nIkke for mig: serien foreslås ikke igen.");
        D.put("tvhome.suggestion", "Forslag");
        D.put("tvhome.ready", "{ep} er klar");
        D.put("tvhome.newCount", "{n} nye");
        D.put("tvhome.toWatch", "{n} at se");
        D.put("tvhome.emptyTitle", "Ingen serier endnu");
        D.put("tvhome.emptyText", "Slå \"{sync}\" til i SeasonPass på telefonen for at sende dine serier hertil.");
        D.put("tvhome.pairCode", "Skriv koden i SeasonPass:  {code}");
        D.put("tvhome.paired", "SeasonPass er forbundet");
        D.put("tvhome.notInstalled", "Appen er ikke installeret");
        D.put("tvhome.unknownApp", "Ved ikke, hvilken app serien ligger i");
        D.put("tvhome.saveFail", "Serien kunne ikke gemmes");
        D.put("tvhome.followed", "Du følger {title} i SeasonPass, næste gang telefonen er åben");
        D.put("tvhome.dismissed", "{title} foreslås ikke igen");
        D.put("tvhome.newVersion", "Ny version {v}");
        D.put("tvhome.who", "Hvem ser?");
        D.put("tvhome.oneProfile", "Kun én profil. Parr flere telefoner i SeasonPass for at skifte.");
        D.put("tvhome.profileN", "Profil {n}");
        D.put("tvhome.saver", "Pauseskærm");
        D.put("tvhome.saverTv", "Tv'ets egen");
        D.put("tvhome.saverTvTap", "Tv'ets egen · tryk for at bruge SeasonPass");
        D.put("tvhome.content", "Pauseskærmens indhold");
        D.put("tvhome.contentMix", "Blandet");
        D.put("tvhome.contentNext", "Dine serier");
        D.put("tvhome.contentClock", "Kun ur");
        D.put("tvhome.clock", "Ur");
        D.put("tvhome.clockBig", "Stort");
        D.put("tvhome.clockSmall", "Lille");
        D.put("tvhome.off", "Fra");
        D.put("tvhome.on", "Til");
        D.put("tvhome.music", "Musik i pauseskærmen");
        D.put("tvhome.musicMissing", "Til · mangler adgang");
        D.put("tvhome.tryDream", "Prøv pauseskærmen");
        D.put("tvhome.update", "Opdatering");
        D.put("tvhome.version", "Version {v}");
        D.put("tvhome.versionTap", "Version {v} · tryk for at søge");
        D.put("tvhome.searching", "Søger …");
        D.put("tvhome.updateTo", "Opdatér til {v}");
        D.put("tvhome.latest", "Du har den nyeste version");
        D.put("tvhome.installing", "Henter og installerer {v} …");
        D.put("tvhome.updateFail", "Kunne ikke opdatere: {e}");
        D.put("tvhome.tvSettings", "Tv'ets indstillinger");
        D.put("tvhome.cantOpen", "Indstillinger kunne ikke åbnes");
        D.put("tvhome.updatesOff", "Opdateringer er ikke slået til i denne version");
    }

    static final TimeZone CPH = TimeZone.getTimeZone("Europe/Copenhagen");

    final String code;
    final Locale locale;
    final TimeZone tz;
    private final Map<String, String> map = new HashMap<>();

    /** Fra en feed (version 2); null eller uden sprog giver dansk. */
    Strings(JSONObject feed) {
        String c = "da";
        Locale l = new Locale("da", "DK");
        TimeZone z = CPH;
        if (feed != null) {
            JSONObject lang = feed.optJSONObject("lang");
            if (lang != null) {
                c = lang.optString("code", "da");
                String tag = lang.optString("locale", "");
                if (!tag.isEmpty()) l = Locale.forLanguageTag(tag);
                String zone = lang.optString("tz", "");
                if (!zone.isEmpty()) z = TimeZone.getTimeZone(zone);
            }
            JSONObject s = feed.optJSONObject("strings");
            if (s != null) {
                java.util.Iterator<String> it = s.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    String v = s.optString(k, "");
                    if (!v.isEmpty()) map.put(k, v);
                }
            }
        }
        code = c;
        locale = l;
        tz = z;
    }

    /**
     * Teksten med pladsholdere udfyldt: t("tvhome.ready", "ep", "S1 E6"). Mangler den, bruges den danske, ellers nøglen.
     * Værdierne sættes ind som de er (en titel med {x} i bliver ikke udfyldt igen).
     */
    String t(String key, String... kv) {
        String text = map.get(key);
        if (text == null) text = D.get(key);
        if (text == null) return key;
        if (kv.length == 0) return text;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            int end = ch == '{' ? text.indexOf('}', i) : -1;
            if (end > 0) {
                String name = text.substring(i + 1, end);
                String value = null;
                for (int k = 0; k + 1 < kv.length; k += 2) if (kv[k].equals(name)) value = kv[k + 1];
                if (value != null) { out.append(value); i = end; continue; }
            }
            out.append(ch);
        }
        return out.toString();
    }

    String t(String key, String name, long n) { return t(key, name, String.valueOf(n)); }

    /** Til at se, om skærmen skal bygges igen: sprog og tekster er de samme. */
    boolean same(Strings o) {
        return o != null && code.equals(o.code) && locale.equals(o.locale) && map.equals(o.map);
    }

    /** Dato efter sprogets egen rækkefølge, fx "21. okt." / "21 Oct" ("dMMM"), "mandag 21. oktober" ("EEEEdMMMM"). */
    String date(String skeleton, long ms, TimeZone zone) {
        String pattern;
        try {
            pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton);
        } catch (Exception e) {
            pattern = skeleton.equals("EEEE") ? "EEEE" : "d. MMM";
        }
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(pattern, locale);
        f.setTimeZone(zone);
        return f.format(new java.util.Date(ms));
    }

    private static Strings cached;
    private static String cachedKey = "";

    /** Teksterne for den aktive profil (feed.json); dansk, før en telefon har sendt. Læses kun, når filen er ændret. */
    static synchronized Strings of(Context c) {
        File f = new File(FeedProvider.dir(c), "feed.json");
        String key = f.lastModified() + ":" + f.length();
        if (cached == null || !key.equals(cachedKey)) {
            JSONObject feed = null;
            try {
                if (f.isFile()) feed = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            } catch (Exception ignored) { }
            cached = new Strings(feed);
            cachedKey = key;
        }
        return cached;
    }
}
