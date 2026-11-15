package dk.seasonpass.tvhome;

import android.service.dreams.DreamService;

/** SeasonPass som tv'ets pauseskærm: serier i slideshow, stort ur og det, der spilles på Spotify. Se SaverView. */
public class SeasonDream extends DreamService {

    private SaverView view;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        view = new SaverView(this);
        setContentView(view);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        if (view != null) view.start();
    }

    @Override
    public void onDreamingStopped() {
        if (view != null) view.stop();
        super.onDreamingStopped();
    }
}
