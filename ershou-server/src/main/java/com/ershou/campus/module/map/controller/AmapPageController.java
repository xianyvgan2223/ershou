package com.ershou.campus.module.map.controller;

import com.ershou.campus.common.result.Result;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 移动端地图页：**后端直接吐一个已经带好 key 的高德地图 HTML**。
 *
 * <h3>为什么移动端走这条路</h3>
 * Flutter 端没有地图控件（原实现是自绘底图 + InteractiveViewer），要上真实地图有两条路：
 * <ol>
 *   <li>原生插件 {@code amap_flutter_map}：性能好，但要改 Android 清单、配隐私合规、
 *       维护一个更新不活跃的插件，风险高；</li>
 *   <li><b>WebView 承载高德 JS API</b>（本方案）：与 Web 端**同一套渲染逻辑**，
 *       Web 端已经跑通并且验证过，移动端只要把 WebView 指过来就行。</li>
 * </ol>
 *
 * <p>选 2 还有个额外好处：<b>key 不用打进 App、也不用用户手输</b>。
 * 页面由后端渲染下发，key 从服务端配置读（{@code ershou.amap.js-key}），
 * 换 key 不用重新发版。App 只需访问 {@code GET /api/v1/map/amap-html?campusId=2}。
 *
 * <p>注意：下发的只有 JS API key（**公开 key，本来就会出现在网页源码里**）与安全密钥
 * securityJsCode（它也只是防跨站盗用，不当作机密）。**Web 服务 key 绝不能下发**，
 * 那个才是需要 IP 白名单保护的。
 */
@Slf4j
@Tag(name = "移动端地图页")
@RestController
@RequestMapping("/api/v1/map")
@RequiredArgsConstructor
public class AmapPageController {

    private final ObjectMapper objectMapper;
    private final RestClient.Builder restClientBuilder;

    private RestClient restClient;

    /** 懒建 RestClient（只用于服务端调高德 Web 服务） */
    private RestClient restClient() {
        if (restClient == null) {
            restClient = restClientBuilder.build();
        }
        return restClient;
    }

    @Value("${ershou.amap.js-key:}")
    private String amapJsKey;

    @Value("${ershou.amap.js-security-code:}")
    private String amapSecurityCode;

    /** Web 服务 key（restapi.amap.com 用，绑 IP 白名单）。**只服务端用，绝不下发**。 */
    @Value("${ershou.amap.web-key:}")
    private String amapWebKey;

    private final org.springframework.core.env.Environment environment;

    /**
     * 取高德 JS API key，**同时认两种变量名**。
     *
     * <p>踩过的坑（2026-10-02 移动端地图打不开）：本项目的 `.env` 里这两个值叫
     * {@code VITE_AMAP_JS_KEY}（因为 vite 只注入 {@code VITE_} 前缀的变量给浏览器），
     * 而这里的配置项是 {@code ershou.amap.js-key: ${AMAP_JS_KEY:}} —— 两边**名字不一样**。
     * 结果：Web 端地图好好的（读的是 {@code VITE_} 那个），移动端却只拿到一句
     * "后端未配置高德 key，无法显示真地图"，而且是静默降级 —— 不报错、只显示一行灰字。
     *
     * <p>配置项本身没错（服务端配置不该依赖前端的命名约定），但**一个 key 要写两遍、
     * 漏一遍就静默坏一半**这种设计太容易踩。所以这里加一层兜底：
     * {@code ershou.amap.js-key} 没配时自动回退到同名环境变量 / {@code VITE_} 版本。
     * 生产环境照旧只配正式变量名即可，回退不会生效。
     */
    private String resolveJsKey() {
        return firstNonBlank(amapJsKey, environment.getProperty("AMAP_JS_KEY"),
                environment.getProperty("VITE_AMAP_JS_KEY"));
    }

    /** 安全密钥同理，两种变量名都认 */
    private String resolveSecurityCode() {
        return firstNonBlank(amapSecurityCode, environment.getProperty("AMAP_JS_SECURITY_CODE"),
                environment.getProperty("VITE_AMAP_JS_SECURITY_CODE"));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    /**
     * 逆地理编码：把坐标翻成"这里可能叫什么"，返回多个候选。
     *
     * <p>为什么放服务端而不是前端直接调：
     * <ol>
     *   <li>调 {@code restapi.amap.com} 需要 **Web 服务 key**（绑 IP 白名单），
     *       那个 key 不能下发到前端；</li>
     *   <li>移动端 WebView 里跨域调高德服务要处理安全密钥代理，服务端一次搞定更省事；</li>
     *   <li>顺手可以加缓存与降级，前端不必关心高德的错误码。</li>
     * </ol>
     *
     * <p>只用腾讯/高德的**基础 LBS** 配额（15 万次/月），比搜索（5000 次/月）宽松得多。
     * 没配 web-key 时返回空列表，调用方退化为"手动填名称"，不会报错。
     */
    @Operation(summary = "逆地理编码候选（选点后识别地名，服务端代理高德 Web 服务）")
    @GetMapping("/regeo")
    public Result<List<Map<String, Object>>> regeo(@RequestParam Double lng, @RequestParam Double lat) {
        if (!StringUtils.hasText(amapWebKey)) {
            log.debug("[amap][regeo] 未配置 ershou.amap.web-key，返回空候选");
            return Result.ok(List.of());
        }
        try {
            String url = "https://restapi.amap.com/v3/geocode/regeo"
                    + "?key=" + amapWebKey
                    + "&location=" + lng + "," + lat
                    + "&radius=500&extensions=all&output=JSON";
            String body = restClient().get().uri(url).retrieve().body(String.class);
            return Result.ok(parseRegeoCandidates(body));
        } catch (Exception e) {
            log.warn("[amap][regeo] 调用失败 lng={} lat={}：{}", lng, lat, e.getMessage());
            return Result.ok(List.of());
        }
    }

    /** 解析高德逆地理结果：POI 列表（按距离排序）+ 兜底的格式化地址 */
    private List<Map<String, Object>> parseRegeoCandidates(String body) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!StringUtils.hasText(body)) {
            return out;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (!"1".equals(root.path("status").asText())) {
                log.warn("[amap][regeo] 高德返回失败：{} {}", root.path("info").asText(), root.path("infocode").asText());
                return out;
            }
            JsonNode regeo = root.path("regeocode");
            String formatted = regeo.path("formattedAddress").asText("");
            JsonNode pois = regeo.path("pois");
            List<JsonNode> sorted = new ArrayList<>();
            pois.forEach(sorted::add);
            sorted.sort(Comparator.comparingInt(p -> p.path("distance").asInt(Integer.MAX_VALUE)));
            Set<String> seen = new HashSet<>();
            for (JsonNode poi : sorted) {
                String name = poi.path("name").asText("").trim();
                if (name.isEmpty() || !seen.add(name)) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", name);
                row.put("address", poi.path("address").asText(formatted));
                row.put("distanceMeters", poi.path("distance").asInt(0));
                row.put("isPoi", true);
                out.add(row);
                if (out.size() >= 8) {
                    return out;
                }
            }
            if (!formatted.isEmpty() && seen.add(formatted)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", formatted);
                row.put("address", "");
                row.put("distanceMeters", 0);
                row.put("isPoi", false);
                out.add(row);
            }
        } catch (Exception e) {
            log.warn("[amap][regeo] 解析失败：{}", e.getMessage());
        }
        return out;
    }

    /** App 用它判断"后端有没有配高德 key"，没配就退回自绘底图 */
    @Operation(summary = "高德 key 是否已配置（不下发 key 本身）")
    @GetMapping("/amap-status")
    public Result<Map<String, Object>> amapStatus() {
        String jsKey = resolveJsKey();
        String securityCode = resolveSecurityCode();
        boolean ready = StringUtils.hasText(jsKey) && StringUtils.hasText(securityCode);
        if (!ready) {
            // 这个接口是移动端判断"能不能显示真地图"的唯一依据，配置缺失必须留在日志里，
            // 否则前端只是退回自绘底图，排查时看不到任何线索。
            //
            // 注意（2026-10-02 定案）：.env 现在由 DotenvEnvironmentPostProcessor 在**应用启动时
            // 自动加载**，与"用哪个入口启动"无关（脚本 / IDEA / jar 都一样）。
            // 所以看到这条日志只可能是两种原因：① .env 里确实没配；② 后端的工作目录不是
            // ershou-server（那样就找不到 ../.env）。
            log.warn("[amap][status] 高德 key 未配置（或只配了一半）：js-key={} 字符, security-code={} 字符；"
                            + "请在项目根目录的 .env 里配 AMAP_JS_KEY / AMAP_JS_SECURITY_CODE"
                            + "（后端启动时会自动读取；若仍为空，请确认后端的工作目录是 ershou-server）",
                    jsKey.length(), securityCode.length());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("configured", ready);
        data.put("hint", ready ? "高德地图可用"
                : "后端未配置高德 key：请在项目根目录 .env 里补 AMAP_JS_KEY / AMAP_JS_SECURITY_CODE"
                        + "（后端会自动读取，无需每次重启都配），移动端将退回自绘底图");
        // 排查"App 到底能不能显示真地图"时，这两个开关是关键信息，但都不能下发 key 本身
        data.put("jsKeyLength", jsKey.length());
        data.put("securityCodeLength", securityCode.length());
        return Result.ok(data);
    }

    /**
     * 高德地图页（HTML）：WebView 直接加载这个地址即可看到真地图。
     *
     * <p>页面只做三件事：初始化地图、把点位画成气泡、点气泡通过 JavaScript 通道回传 pointId。
     * 业务交互（列表、选点、导航）仍在 Flutter 侧，这里保持"纯展示 + 事件回传"。
     */
    @Operation(summary = "移动端高德地图 HTML（自带 key，供 WebView 加载）")
    @GetMapping(value = "/amap-html", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> amapHtml(@RequestParam(defaultValue = "2") Long campusId,
                                           @RequestParam(defaultValue = "false") boolean pickable,
                                           @RequestParam(required = false) Long focusPointId,
                                           @RequestParam(defaultValue = "false") boolean wantRegeo,
                                           @RequestParam(required = false) String mapPoints) {
        String jsKey = resolveJsKey();
        if (!StringUtils.hasText(jsKey)) {
            log.warn("[amap][html] 未配置高德 key，向 App 下发降级页（移动端地图会显示不了）");
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .body("<html><body style='font:14px system-ui;padding:24px;color:#666'>"
                            + "后端未配置高德 key（ershou.amap.js-key），无法显示真地图。</body></html>");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header("Cache-Control", "no-store")
                .body(buildHtml(campusId, pickable, focusPointId, wantRegeo, mapPoints));
    }

    private String buildHtml(Long campusId, boolean pickable, Long focusPointId,
                             boolean wantRegeo, String mapPoints) {
        // 直接用文本块拼，避免引入模板引擎；里面的 ${} 是 JS 模板串，不能用 String.format
        String page = """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="UTF-8" />
                  <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no" />
                  <style>
                    html,body,#map{margin:0;height:100%;width:100%;background:#eef3f0}
                    /* 气泡：**固定直径的正圆**，只放数字。
                       以前用 min-width+padding 会被文字撑成长条（"3 件"这种），既难看又挡地图。
                       ⚠️ pointer-events:auto 不能省：AMap 给 marker 外层容器设了
                       pointer-events:none，而传进来的 content 是 0×0 的 div，
                       不加这个气泡就**完全点不动**（真机上排查了很久）。 */
                    .pin{position:relative;pointer-events:auto;width:26px;height:26px}
                    .bubble{box-sizing:border-box;display:flex;align-items:center;justify-content:center;
                      width:26px;height:26px;border:2px solid #fff;border-radius:50%;
                      background:#0f9d58;color:#fff;font:600 12px/1 system-ui;
                      box-shadow:0 1px 4px rgba(0,0,0,.3);transition:transform .12s ease,background .12s ease}
                    .bubble.empty{background:#9aa6b2;font-size:11px}
                    /* 选中：橙色 + 放大，但不改变形状（仍是正圆） */
                    .pin.active .bubble{background:#ff6b35;transform:scale(1.35)}
                    /* 合并组角标：说明这个气泡其实代表 N 个点位 */
                    .badge{position:absolute;top:-4px;right:-6px;min-width:15px;height:15px;
                      padding:0 3px;border:1.5px solid #fff;border-radius:999px;background:#7c3aed;
                      color:#fff;font:700 10px/12px system-ui;text-align:center;font-style:normal}
                    /* 名称标签：绝对定位在地图文档层，不参与气泡布局 —— 所以不会把气泡挤成长条。
                       默认隐藏（13 个点位全带标签会糊成一片），只显示选中的那个。 */
                    .label{display:none;position:absolute;left:50%;bottom:46px;transform:translateX(-50%);
                      padding:3px 8px;border-radius:6px;background:rgba(255,255,255,.96);
                      border:1px solid rgba(0,0,0,.06);box-shadow:0 2px 6px rgba(0,0,0,.16);
                      color:#1f2937;font:12px/1.4 system-ui;white-space:nowrap}
                    .pin.active .label{display:block}
                    /* 选点时点哪里打的"水滴针"。
                       pointer-events:none 是必须的：AMap 给 marker 外层设了 pointer-events:none，
                       而这个标记正好压在用户要点的位置上，不关掉它会挡住后续的地图点击。 */
                    .pickmark{position:relative;width:26px;height:36px;pointer-events:none;
                      filter:drop-shadow(0 3px 6px rgba(0,0,0,.32))}
                    .pickmark__head{position:absolute;left:0;top:0;width:26px;height:26px;
                      box-sizing:border-box;border:2.5px solid #fff;border-radius:50%;
                      background:#ff6b35;box-shadow:0 1px 4px rgba(0,0,0,.3)}
                    /* 水滴的尖：一个旋转 45° 的方块，只有一角露在圆形下方 */
                    .pickmark__head::after{content:'';position:absolute;left:50%;top:20px;
                      width:11px;height:11px;margin-left:-5.5px;border-radius:0 0 2px 0;
                      background:#ff6b35;transform:rotate(45deg)}
                    .pickmark__head::before{content:'';position:absolute;left:50%;top:9px;
                      width:8px;height:8px;margin:-4px 0 0 -4px;border-radius:50%;background:#fff}
                    /* 地面小椭圆：给"针扎在地上"一点纵深感 */
                    .pickmark__foot{position:absolute;left:50%;bottom:0;width:14px;height:5px;
                      margin-left:-7px;border-radius:50%;background:rgba(0,0,0,.22)}
                    /* 点一下播一次掉落动画，让"点哪儿打哪儿"看得见 */
                    @keyframes pickdrop{0%{transform:translateY(-14px);opacity:0}
                      60%{transform:translateY(0);opacity:1}100%{transform:translateY(0)}}
                    .pickmark.is-drop{animation:pickdrop .26s ease-out}
                  </style>
                </head>
                <body>
                <div id="map"></div>
                <script>
                  window._AMapSecurityConfig = { securityJsCode: '%SECURITY_CODE%' };
                  var CAMPUS_ID = %CAMPUS_ID%;
                  var PICKABLE = %PICKABLE%;
                  // 聚焦模式：商品详情页「在地图上看」跳过来时只画这一个点位
                  var FOCUS_POINT_ID = %FOCUS_POINT_ID%;
                  // 选点模式：点地图后除了回传坐标，还顺手做一次逆地理编码，
                  // 把"这里可能叫什么"一起回传（用页面已有的 JS API，不需要 Web 服务 key）
                  var WANT_REGEO = %WANT_REGEO%;
                  function post(o) {
                    if (window.FlutterBridge) window.FlutterBridge.postMessage(JSON.stringify(o));
                  }
                  function fail(msg) { document.body.innerHTML =
                    '<div style="font:14px system-ui;padding:24px;color:#b91c1c">' + msg + '</div>'; }
                </script>
                <script src="https://webapi.amap.com/loader.js"></script>
                <script>
                  // 插件必须在这里声明：只在 loader 里写 'AMap.Scale' 的话，
                  // 下面 new AMap.Geocoder() 会抛 "is not a constructor"，逆地理编码静默失效
                  AMapLoader.load({ key: '%JS_KEY%', version: '2.0', plugins: ['AMap.Scale', 'AMap.Geocoder'] })
                    .then(function (AMap) {
                      window.AMap_ = AMap;
                      return fetch('/api/v1/map/points?campusId=' + CAMPUS_ID).then(function (r) { return r.json(); });
                    })
                    .then(function (res) {
                      var data = (res && res.data) || {};
                      var cfg = data.mapConfig || {};
                      var center = cfg.center || { lng: 110.416819, lat: 25.311724 };
                      var map = new AMap.Map('map', {
                        zoom: cfg.zoom || 15,
                        center: [center.lng, center.lat],
                        resizeEnable: true,
                        viewMode: '2D'
                      });
                      map.addControl(new AMap.Scale());
                      // 选点用的"水滴针"标记。
                      // ⚠️ marker 的 content **必须自己写 pointer-events:auto**：AMap 给 marker
                      // 外层容器设了 pointer-events:none，不加的话这个标记会挡住地图点击事件。
                      // 也别只给内层 div 加——那样触摸仍然落不到它上面（气泡踩过这个坑）。
                      function setPickMarker(lng, lat) {
                        if (!window.__pickMarker) {
                          window.__pickMarker = new AMap.Marker({
                            position: [lng, lat], anchor: 'bottom-center', zIndex: 250,
                            content: '<div class="pickmark"><i class="pickmark__head"></i>'
                              + '<i class="pickmark__foot"></i></div>'
                          });
                          map.add(window.__pickMarker);
                        } else {
                          window.__pickMarker.setPosition([lng, lat]);
                        }
                        var el = window.__pickMarker.getContent();
                        var dom = (typeof el === 'string')
                          ? document.querySelector('.pickmark')
                          : el;
                        if (dom) {
                          // 重新触发一次掉落动画，让"点哪儿打哪儿"这件事看得见
                          dom.classList.remove('is-drop');
                          void dom.offsetWidth;
                          dom.classList.add('is-drop');
                        }
                      }
                      window.__ershouPickMark = setPickMarker;
                      window.__ershouClearPickMark = function () {
                        if (window.__pickMarker) { map.remove(window.__pickMarker); window.__pickMarker = null; }
                      };
                      map.on('click', function (e) {
                        if (!PICKABLE) return;
                        var lng = e.lnglat.getLng(), lat = e.lnglat.getLat();
                        // 先在点击处打上标记：用户点了地图必须马上看到反馈，
                        // 不能等逆地理编码回来才画（那也是 slow 的时候"像没反应"的原因）。
                        setPickMarker(lng, lat);
                        if (!WANT_REGEO) {
                          post({ type: 'pick', lng: lng, lat: lat });
                          return;
                        }
                        // 逆地理编码：取周边 POI 按距离排序，最多 8 个（含兜底的门牌地址）
                        try {
                          var geocoder = new AMap.Geocoder({ radius: 500, extensions: 'all' });
                          geocoder.getAddress([lng, lat], function (status, result) {
                            var cands = [];
                            var regeo = result && result.regeocode;
                            if (status === 'complete' && regeo) {
                              var pois = (regeo.pois || []).slice();
                              pois.sort(function (a, b) {
                                return (a.distance || 1e9) - (b.distance || 1e9);
                              });
                              var seen = {};
                              for (var i = 0; i < pois.length && cands.length < 8; i++) {
                                var nm = (pois[i].name || '').trim();
                                if (!nm || seen[nm]) continue;
                                seen[nm] = 1;
                                cands.push({ name: nm, address: pois[i].address || '',
                                  distanceMeters: pois[i].distance || 0, isPoi: true,
                                  // 带上高德认定的 POI 坐标：用户拍板某个候选时，
                                  // 针要挪到 POI 的位置（而不是随手点的那个坐标）
                                  lng: pois[i].location ? pois[i].location.lng : null,
                                  lat: pois[i].location ? pois[i].location.lat : null });
                              }
                              var fmt = (regeo.formattedAddress || '').trim();
                              if (fmt && !seen[fmt]) {
                                cands.push({ name: fmt, address: '', distanceMeters: 0, isPoi: false });
                              }
                            }
                            post({ type: 'pick', lng: lng, lat: lat, candidates: cands });
                          });
                        } catch (err) {
                          post({ type: 'pick', lng: lng, lat: lat });
                        }
                      });
                      var points = data.points || [];
                      // 合并后的自定义气泡：Flutter 侧把「100 米内合并」的结果传进来
                      // （原生地图组件没有自定义 marker，只能这么画）。
                      // 注意 mapPoints 只用于**展示**：每个气泡带 ids 数组，
                      // 点它时回传的是组内真实点位 id，商品详情/面交单引用的仍是精确点位。
                      var mapPoints = %MAP_POINTS%;
                      if (mapPoints && mapPoints.length) points = mapPoints;
                      if (FOCUS_POINT_ID > 0) {
                        points = points.filter(function (p) {
                          return p.id === FOCUS_POINT_ID
                            || (p.ids && p.ids.indexOf(FOCUS_POINT_ID) >= 0);
                        });
                      }
                      var pinEls = {};
                      var clickCount = 0;
                      points.forEach(function (p) {
                        var lng = p.lng, lat = p.lat;
                        if (!lng || !lat) return;
                        var el = document.createElement('div');
                        el.className = 'pin';
                        // 合并组在气泡右上角加个小角标，说明"这里其实是 N 个点位"
                        var badge = (p.ids && p.ids.length > 1)
                          ? '<i class="badge">' + p.ids.length + '</i>' : '';
                        el.innerHTML = '<div class="bubble' + (p.itemCount ? '' : ' empty') + '">'
                          + (p.itemCount || 0) + '</div>' + badge
                          + '<div class="label">' + (p.name || '') + '</div>';
                        var marker = new AMap.Marker({
                          position: [lng, lat], content: el, anchor: 'bottom-center', clickable: true, zIndex: 100
                        });
                        marker.on('click', function () {
                          clickCount++;
                          for (var k in pinEls) { pinEls[k].classList.remove('active'); }
                          el.classList.add('active');
                          // ids = 合并组内的真实点位 id；单点就是 [id]
                          post({ type: 'select', pointId: p.id, ids: p.ids || [p.id] });
                        });
                        // 把坐标挂在 DOM 上，__ershouFocus 用得上（本地没坐标时按 id 聚焦）
                        el.__lng = lng;
                        el.__lat = lat;
                        pinEls[p.id] = el;
                        map.add(marker);
                      });
                      // 注意底部的内边距：地图下面还有 Flutter 的详情卡片浮着，
                      // 不留的话最南边的点位会被挡住（真机截图里发现过）
                      if (points.length > 1) map.setFitView(null, false, [70, 40, 120, 40]);

                      // 聚焦模式（商品详情页「在地图上查看」跳过来）：直接把那个点位点亮并居中
                      if (FOCUS_POINT_ID > 0 && pinEls[FOCUS_POINT_ID]) {
                        pinEls[FOCUS_POINT_ID].classList.add('active');
                        var fp = points[0];
                        map.setZoomAndCenter(17, [fp.lng, fp.lat]);
                      }

                      // 供 Flutter 侧调用的钩子（用 runJavaScript 执行）：
                      // 选中点位居中 / 显示我的位置（蓝点 + 精度圈）
                      window.__ershouSel = function (id) { return !!pinEls[id]; };
                      window.__ershouCount = function () { return Object.keys(pinEls).length; };
                      window.__ershouClicks = function () { return clickCount; };
                      // 测试用：用 JS 直接触发某个气泡的点击（绕过触摸）。
                      // 用来区分"点击回调坏了"和"触摸没送到 marker"——
                      // 真机截图里气泡会随地图自适应漂移，靠 adb 坐标点很不稳。
                      window.__ershouTap = function (id) {
                        if (!pinEls[id]) return false;
                        pinEls[id].click();
                        return true;
                      };
                      window.__ershouFirstId = function () {
                        return Number(Object.keys(pinEls)[0] || 0);
                      };
                      window.__ershouCenter = function (id, lng, lat) {
                        for (var k in pinEls) { pinEls[k].classList.remove('active'); }
                        if (pinEls[id]) pinEls[id].classList.add('active');
                        map.setZoomAndCenter(Math.max(map.getZoom(), 17), [lng, lat]);
                      };
                      // 按 id 聚焦（本地没有坐标时用，页面自己从已画的点位里找）
                      window.__ershouFocus = function (id) {
                        for (var k in pinEls) { pinEls[k].classList.remove('active'); }
                        if (!pinEls[id]) return;
                        pinEls[id].classList.add('active');
                        map.setZoomAndCenter(17, [pinEls[id].__lng, pinEls[id].__lat]);
                      };
                      window.__ershouLocate = function (lng, lat) {
                        if (window.__myMarker) map.remove(window.__myMarker);
                        if (window.__myCircle) map.remove(window.__myCircle);
                        window.__myMarker = new AMap.Marker({
                          position: [lng, lat], anchor: 'center', zIndex: 200,
                          content: '<div style="width:14px;height:14px;border:3px solid #fff;border-radius:50%;'
                            + 'background:#1a73e8;box-shadow:0 0 0 2px rgba(26,115,232,.35)"></div>'
                        });
                        window.__myCircle = new AMap.Circle({
                          center: [lng, lat], radius: 50,
                          strokeColor: '#1a73e8', strokeOpacity: .4, strokeWeight: 1,
                          fillColor: '#1a73e8', fillOpacity: .12
                        });
                        map.add(window.__myCircle);
                        map.add(window.__myMarker);
                        map.setZoomAndCenter(Math.max(map.getZoom(), 17), [lng, lat]);
                      };
                      post({ type: 'ready', count: points.length,
                             markerCount: Object.keys(pinEls).length,
                             merged: !!(mapPoints && mapPoints.length) });
                    })
                    .catch(function (e) { fail('高德地图加载失败：' + (e && e.message ? e.message : e)); });
                </script>
                </body>
                </html>
                """;
        return page
                .replace("%SECURITY_CODE%", jsonSafe(resolveSecurityCode()))
                .replace("%JS_KEY%", jsonSafe(resolveJsKey()))
                .replace("%CAMPUS_ID%", String.valueOf(campusId))
                .replace("%FOCUS_POINT_ID%", String.valueOf(focusPointId == null ? 0L : focusPointId))
                .replace("%WANT_REGEO%", String.valueOf(wantRegeo))
                // 合并后的展示点位（JSON 数组）。只用于画气泡，业务引用仍是精确点位。
                // 传 null/空表示"不合并"，页面回退到服务端返回的原始点位。
                .replace("%MAP_POINTS%", sanitizeJsonArray(mapPoints))
                .replace("%PICKABLE%", String.valueOf(pickable));
    }

    /**
     * 把前端传来的 JSON 数组做一次最小校验：必须是数组、长度有上限、不含 {@code </script>}。
     *
     * <p>它会内联进 HTML 的 {@code <script>} 里，所以不能直接原样拼——
     * 一个 {@code </script>} 就能截断脚本（虽然数据来自我们自己的 App，仍是必须的防御）。
     */
    private String sanitizeJsonArray(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "null";
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]") || trimmed.length() > 400_000) {
            log.warn("[amap][map-points] 参数不合法，忽略并回退到服务端点位，长度={}", trimmed.length());
            return "null";
        }
        String safe = trimmed.replace("</", "<\\/");
        try {
            objectMapper.readTree(safe);
        } catch (Exception e) {
            log.warn("[amap][map-points] JSON 解析失败，忽略：{}", e.getMessage());
            return "null";
        }
        return safe;
    }

    /** 只保留 JSON 字符串里安全的字符，避免把引号注入到 HTML 模板（key 由运维配置，仍需防御） */
    private String jsonSafe(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("[^A-Za-z0-9_\\-]", "");
    }
}
