package androidx.test.espresso;
public interface IdlingResource {
  interface ResourceCallback { void onTransitionToIdle(); }
  String getName(); boolean isIdleNow(); void registerIdleTransitionCallback(ResourceCallback c);
}
