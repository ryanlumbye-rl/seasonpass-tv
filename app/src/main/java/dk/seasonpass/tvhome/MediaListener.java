package dk.seasonpass.tvhome;

import android.service.notification.NotificationListenerService;

/**
 * Tom tjeneste: at appen er "notifikationslytter" er det, der giver MediaSessionManager lov til at vise,
 * hvad der spilles i andre apps (Spotify m.fl.). Vi læser ingen notifikationer. Slås til i Indstillinger
 * (eller af install.sh); se Prefs.setListener.
 */
public class MediaListener extends NotificationListenerService { }
