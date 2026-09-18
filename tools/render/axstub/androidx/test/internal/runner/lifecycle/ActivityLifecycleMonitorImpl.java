package androidx.test.internal.runner.lifecycle;
import android.app.Activity;
import androidx.test.runner.lifecycle.*;
import java.util.*;
public class ActivityLifecycleMonitorImpl implements ActivityLifecycleMonitor {
  private final List<ActivityLifecycleCallback> cbs = new ArrayList<>();
  private final Map<Activity, Stage> estados = new WeakHashMap<>();
  public void addLifecycleCallback(ActivityLifecycleCallback c) { cbs.add(c); }
  public void removeLifecycleCallback(ActivityLifecycleCallback c) { cbs.remove(c); }
  public Stage getLifecycleStageOf(Activity a) { return estados.get(a); }
  public Collection<Activity> getActivitiesInStage(Stage s) {
    List<Activity> r = new ArrayList<>();
    for (Map.Entry<Activity, Stage> e : estados.entrySet()) if (e.getValue() == s) r.add(e.getKey());
    return r;
  }
  public void signalLifecycleChange(Stage s, Activity a) {
    estados.put(a, s);
    for (ActivityLifecycleCallback c : new ArrayList<>(cbs)) c.onActivityLifecycleChanged(a, s);
  }
}
