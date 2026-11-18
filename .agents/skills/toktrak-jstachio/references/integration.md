# JStachio 1.3.7 integration

Authoritative API: <https://jstach.io/doc/jstachio/1.3.7/apidocs/>

```java
public record BaseView(String title, String stylesheetUrl, boolean development) {}

@JStache(path = "home.mustache")
public record HomeView(BaseView base, boolean signedIn) {}

byte[] html = HttpSupport.renderEncoded(HomeViewRenderer.of(), view);
```

Every top-level model composes `BaseView` under the field `base`.
`base.mustache` reads `base.*` and defines one `content` block; page templates
invoke the static `base` parent and override that block once.

Pre-encoding is the default. Build uses `jstache.resourcesPath`,
`jstache.incremental=true`, and direct generated renderers. Templates are
compile-time inputs only.
