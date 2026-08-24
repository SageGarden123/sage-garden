# WorkManager instantiates its InputMerger implementations via reflection (Class.forName +
# no-arg constructor), never with a direct `new` call the shrinker can see. Confirmed via logcat
# that R8 was stripping OverwritingInputMerger's constructor, which broke Glance's widget-update
# work and left the home screen widget stuck on its loading layout forever:
#   WM-InputMerger: java.lang.NoSuchMethodException: androidx.work.OverwritingInputMerger.<init> []
-keep class * extends androidx.work.InputMerger {
    public <init>();
}

# Same reflection pattern as above, for Glance's action-callback dispatch (androidx.glance ships its
# own "-keep public class * extends ActionCallback" consumer rule with no explicit <init>() clause —
# given the WorkManager rule above looked equally sufficient on paper and still failed at runtime,
# don't trust that one either; keep our own callbacks' no-arg constructors explicitly).
-keep class * extends androidx.glance.appwidget.action.ActionCallback {
    public <init>();
}
