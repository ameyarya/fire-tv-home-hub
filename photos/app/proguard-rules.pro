# The gallery page calls TV.viewer(open) through addJavascriptInterface.
-keepclassmembers class com.homecontrol.photos.PhotosActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
