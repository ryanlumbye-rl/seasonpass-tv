package dk.seasonpass.tvhome;

import android.net.Uri;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Afsnitslinks (0.7.0): telefonen kan sende en https-adresse til netop afsnittet i tjenestens egen app (feltet "link" på
 * afsnit i feedet). Fjernsynet starter kun adresser, der er https og ligger på en kendt tjenestevært; alt andet ignoreres,
 * og så åbnes tjenestens app som før. Kendte værter følger telefonens aftale: viaplay.*, tv.nrk.no, dr.dk, svtplay.se,
 * tv4play.se og mtv.fi (med eller uden www og andre underdomæner).
 */
final class Links {

    private Links() { }

    private static final Pattern HOST = Pattern.compile(
            "(?:[a-z0-9-]+\\.)*(?:viaplay\\.[a-z]{2,3}|tv\\.nrk\\.no|dr\\.dk|svtplay\\.se|tv4play\\.se|mtv\\.fi|ruutu\\.fi|ruv\\.is"
                    + "|tv2\\.dk|netflix\\.com|disneyplus\\.com|tv\\.apple\\.com|primevideo\\.com|hbomax\\.com|max\\.com"
                    + "|skyshowtime\\.com|britbox\\.(?:com|dk)|cmore\\.(?:dk|se|no|fi))");

    /** Er adressen en https-adresse på en kendt vært, uden brugernavn og med en gyldig port? */
    static boolean ok(String url) {
        if (url == null || url.isEmpty() || url.length() > 2048 || "null".equals(url)) return false;
        try {
            Uri u = Uri.parse(url);
            if (!"https".equals(u.getScheme()) || u.getUserInfo() != null) return false;
            String host = u.getHost();
            if (host == null) return false;
            int port = u.getPort();
            if (port != -1 && port != 443) return false;
            return HOST.matcher(host.toLowerCase(Locale.ROOT)).matches();
        } catch (Exception e) {
            return false;
        }
    }
}
