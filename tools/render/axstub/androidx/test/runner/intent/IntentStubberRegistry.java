package androidx.test.runner.intent;
public final class IntentStubberRegistry {
  private static IntentStubber s;
  public static boolean isLoaded() { return s != null; }
  public static IntentStubber getInstance() { return s; }
  public static void load(IntentStubber x) { s = x; }
}
