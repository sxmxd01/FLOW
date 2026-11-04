# JavaScript bridge: methods are looked up by name from the WebView.
-keepattributes JavascriptInterface
-keepclassmembers class app.focusflow.FocusBridge {
    @android.webkit.JavascriptInterface <methods>;
}
