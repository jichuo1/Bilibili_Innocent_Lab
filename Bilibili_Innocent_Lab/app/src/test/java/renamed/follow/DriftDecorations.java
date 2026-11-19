package renamed.follow;

import android.graphics.Canvas;
import androidx.recyclerview.widget.RecyclerView;

/** Drifted decoration subclasses only reachable through DEX construction edges in tests. */
public final class DriftDecorations {
    public static class Home extends DriftPainter { }
    public static class Personal extends DriftPainter { }
    public static class Separator extends RecyclerView.ItemDecoration {
        @Override public void onDrawOver(Canvas canvas, RecyclerView parent, RecyclerView.State state) { }
    }
    /** Same painter parent as Personal: role ambiguity must stay missing, never guessed. */
    public static class SecondPersonal extends DriftPainter { }
}
