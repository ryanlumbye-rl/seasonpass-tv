package dk.seasonpass.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;

/** "Prøv pauseskærmen" i Indstillinger: samme visning som pauseskærmen, lukkes med en vilkårlig tast. */
public class SaverPreviewActivity extends Activity {

    private SaverView view;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new SaverView(this);
        setContentView(view);
    }

    @Override protected void onResume() { super.onResume(); view.start(); }
    @Override protected void onPause() { view.stop(); super.onPause(); }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        finish();
        return true;
    }
}
