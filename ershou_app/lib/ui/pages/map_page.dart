import 'dart:convert';

import 'package:flutter/foundation.dart' show kDebugMode;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:geolocator/geolocator.dart';
import 'package:webview_flutter/webview_flutter.dart';

import '../../core/providers.dart';
import '../../core/utils/coord.dart';
import '../../core/utils/point_merge.dart';
import '../../data/models2.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import 'items_by_point_page.dart';

/// 校内地图（移动端）。
///
/// **实现方式：WebView 承载高德 JS API**，页面由后端下发
/// （`GET /api/v1/map/amap-html`，见 `AmapPageController`）。
///
/// 为什么不用原生 `amap_flutter_map`：
/// 1. Web 端的高德接入已经跑通并验证过，WebView 复用同一套渲染逻辑，风险最低；
/// 2. **key 不用打进 App、用户也不用手输**——后端把 key 注入到页面里下发，
///    换 key 不用重新发版；
/// 3. 不用改 Android 清单、不用配高德原生 SDK 的隐私合规。
///
/// 交互分工：页面只负责"画地图 + 画气泡 + 把点击回传"，
/// 点位详情、商品列表仍留在 Flutter 侧（见底部的 `_bottomPanel`）。
class MapPage extends ConsumerStatefulWidget {
  const MapPage({super.key});

  @override
  ConsumerState<MapPage> createState() => _MapPageState();
}

class _MapPageState extends ConsumerState<MapPage> {
  WebViewController? _web;
  MapData _data = const MapData();
  /// 展示用的点位分组（100 米内合并）
  List<PointGroup> _groups = const [];
  /// 代表点 id → 分组，点气泡时用它把"整组"取出来
  Map<int, PointGroup> _groupByRepId = const {};
  MapPoint? _selected;
  /// 选中气泡代表的整组（含组内每个精确点位）
  PointGroup? _selectedGroup;
  bool _loading = true;
  bool _checking = true;
  String? _error;
  /// 后端没配高德 key 时退回自绘底图
  bool _amapReady = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _boot());
  }

  @override
  Widget build(BuildContext context) {
    // 切到这个 Tab 时补一次"自动重试"（IndexedStack 保活，initState 只跑一次）
    ref.listen<int>(navIndexProvider, (prev, next) {
      if (next == 2) _onBecameVisible();
    });
    // 商品详情页「在地图上看」：重建 WebView 并聚焦到那个点位
    ref.listen<MapFocus?>(mapFocusProvider, (prev, next) {
      if (next != null) _reloadWithFocus(next);
    });
    return _buildBody();
  }

  /// 用聚焦点重建 WebView（页面按 focusPointId 只画那一个点位）
  Future<void> _reloadWithFocus(MapFocus focus) async {
    if (!_amapReady) {
      await _boot();
    }
    final repo = ref.read(mapRepositoryProvider);
    if (!mounted) return;
    // 已经加载过就重新指向带 focusPointId 的地址，省去重建控制器的麻烦
    await _web?.loadRequest(Uri.parse(
      repo.amapPageUrl(focusPointId: focus.pointId),
    ));
  }

  Future<void> _boot() async {
    setState(() {
      _checking = true;
      _error = null;
    });
    final repo = ref.read(mapRepositoryProvider);
    final ready = await repo.amapConfigured();
    if (!mounted) return;
    if (!ready) {
      setState(() {
        _amapReady = false;
        _checking = false;
        _loading = false;
      });
      return;
    }
    setState(() {
      _amapReady = true;
      _checking = false;
    });
    // ⚠️ 顺序很重要：**先拉点位、再建 WebView**。
    // 反过来的话 WebView 会在 _groups 还是空的时候拼 URL，
    // 合并结果就传不进去（地图上会显示未合并的一堆气泡，而 Flutter 侧明明合并了）。
    await _loadPoints();
    if (!mounted) return;
    _createWebView();
  }

  /// 切到地图 Tab 时：如果当前状态是"没地图"或"报错"，就自动重来一次。
  ///
  /// 为什么需要：地图是 IndexedStack 里保活的，`initState` 只跑一次。
  /// 后端没配 key、或后端没起来时，用户切走再切回来还是那个错误页——
  /// 只能手动点「重试」，体验很差（自己踩过）。
  void _onBecameVisible() {
    if (_error != null || (!_amapReady && !_checking)) {
      _boot();
    }
  }

  void _createWebView() {
    final repo = ref.read(mapRepositoryProvider);
    final controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(const Color(0xFFEEF3F0))
      // 与页面约定的事件：ready / select / pick（见 AmapPageController 的 HTML）
      ..addJavaScriptChannel('FlutterBridge', onMessageReceived: _onBridgeMessage)
      ..setNavigationDelegate(NavigationDelegate(
        onPageFinished: (_) => _injectPoints(),
        onWebResourceError: (e) {
          if (!mounted) return;
          // 主文档加载失败才算错误；瓦片/接口的失败忽略
          if (e.isForMainFrame ?? false) {
            setState(() => _error = '地图加载失败：${e.description}');
          }
        },
      ))
      ..loadRequest(Uri.parse(repo.amapPageUrl(
        mergedPointsJson: _mergedPointsJson(),
      )));
    setState(() => _web = controller);
  }

  /// 把「100 米内合并」的结果编成页面要画的点位（只影响展示）
  String _mergedPointsJson() {
    final groups = _groups;
    if (groups.isEmpty) return '';
    final rows = groups.map((g) {
      final rep = g.rep;
      return {
        'id': rep.id,
        'name': g.name,
        'itemCount': g.itemCount,
        'minPrice': g.minPrice,
        'lng': rep.lng,
        'lat': rep.lat,
        // 组内真实点位 id：点气泡时回传，供"看商品"按整组查
        'ids': g.ids,
      };
    }).toList();
    return jsonEncode(rows);
  }

  Future<void> _loadPoints() async {
    try {
      final data = await ref.read(mapRepositoryProvider).points();
      if (!mounted) return;
      // 展示用的分组：100 米内合并成一个气泡（只影响展示）
      final groups = groupPointsByDistance(data.points);
      setState(() {
        _data = data;
        _groups = groups;
        _groupByRepId = {for (final g in groups) g.rep.id: g};
        _loading = false;
        if (_selected != null) {
          final fresh = data.points.where((p) => p.id == _selected!.id).toList();
          _selected = fresh.isEmpty ? null : fresh.first;
        }
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.toString();
        _loading = false;
      });
    }
  }

  /// 地图页已经在加载时自己拉过一次点位；这里只在需要时把选中态同步过去
  void _injectPoints() {
    final controller = _web;
    if (controller == null) return;
    controller.runJavaScript('window.__ershouPointsReady = true;');
  }

  void _onBridgeMessage(JavaScriptMessage message) {
    Map<String, dynamic> payload;
    try {
      payload = (jsonDecode(message.message) as Map).cast<String, dynamic>();
    } catch (_) {
      return;
    }
    final type = payload['type'];
    // 桥接事件是排查"点了没反应"的唯一入口，debug 下全打出来
    debugPrint('[map] bridge type=$type raw=${message.message}');
    if (type == 'ready') {
      final count = payload['count'];
      final markerCount = payload['markerCount'];
      final merged = payload['merged'] == true;
      // 这两个数是排查"气泡画不出来/点不动"的关键：count=传入的点位数、
      // markerCount=真正画上去的标记数。对不上就说明有坐标缺失或合并数据没传进去。
      debugPrint('[map] ready count=$count markerCount=$markerCount merged=$merged');
      if (mounted) setState(() => _loading = false);
      // 自检：用 JS 直接点第一个气泡，验证"气泡点击 → 回传 → 弹卡片"这条链路是通的。
      // 靠 adb 触摸做不到——气泡会随地图自适应漂移，坐标很难点准。
      _selfTestTap();
      return;
    }
    if (type == 'select') {
      final id = payload['pointId'];
      debugPrint('[map] select 收到 pointId=$id 组数=${_groupByRepId.length} 点位数=${_data.points.length}');
      if (id is! int) {
        debugPrint('[map] select 被丢弃：pointId 不是 int');
        return;
      }
      // 点的是合并后的气泡：rep.id 就是 page 那边传的 p.id
      final group = _groupByRepId[id];
      final hit = group != null
          ? group.rep
          : _data.points.where((p) => p.id == id).firstOrNull;
      if (hit == null) return;
      setState(() {
        _selected = hit;
        _selectedGroup = group;
      });
      _centerOn(hit);
      return;
    }
  }

  /// 自检：让页面用 JS 点一下第一个气泡，验证整条点击链路。
  /// 只在 debug 构建里跑（release 不会输出这些日志）。
  Future<void> _selfTestTap() async {
    if (!kDebugMode) return;
    final controller = _web;
    if (controller == null) return;
    try {
      await Future<void>.delayed(const Duration(seconds: 2));
      final id = await controller.runJavaScriptReturningResult('window.__ershouFirstId()');
      final pointId = int.tryParse(id.toString().replaceAll('"', '')) ?? 0;
      if (pointId == 0) {
        debugPrint('[map] selftest 页面里没有气泡');
        return;
      }
      final ok = await controller
          .runJavaScriptReturningResult('window.__ershouTap($pointId)');
      debugPrint('[map] selftest tap pointId=$pointId → $ok');
      // 再直接发一条假事件：如果这条能收到，说明桥接没问题，
      // 那"没反应"就一定是页面里的点击回调没触发（DOM 层面）
      final selOk = await controller.runJavaScriptReturningResult(
          'window.__ershouSel($pointId)');
      debugPrint('[map] selftest pinEls 里有点位吗 → $selOk');
      await controller.runJavaScript(
          "window.FlutterBridge && window.FlutterBridge.postMessage(JSON.stringify({type:'ping'}));");
      debugPrint('[map] selftest 已发出 ping');
    } catch (e) {
      debugPrint('[map] selftest 失败：$e');
    }
  }

  /// 让地图把选中点位居中并点亮（WebView 里执行）
  void _centerOn(MapPoint p) {
    final controller = _web;
    if (controller == null || !p.hasGeo) return;
    // 第三个参数是点位 id：页面据此点亮对应气泡、并把名称标签显示出来
    controller.runJavaScript(
      'window.__ershouCenter && window.__ershouCenter(${p.id}, ${p.lng}, ${p.lat});',
    );
  }

  Future<void> _locateMe() async {
    final repo = ref.read(mapRepositoryProvider);
    final pos = await ref.read(locationServiceProvider).currentGcj02();
    if (!mounted) return;
    if (pos == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('定位失败：请在系统设置里允许本应用获取位置')),
      );
      return;
    }
    _web?.runJavaScript('window.__ershouLocate && window.__ershouLocate(${pos[0]}, ${pos[1]});');
    try {
      final list = await repo.nearby(lng: pos[0], lat: pos[1]);
      if (!mounted) return;
      setState(() => _nearby = list);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('你附近有 ${list.length} 个面交点（来自 Redis，没花高德配额）')),
      );
    } catch (_) {
      // 附近列表拿不到不影响地图本身
    }
  }

  List<NearbyPoint> _nearby = const [];

  /// 真正的界面（build 只负责挂监听，避免把 listen 写进条件分支里）
  Widget _buildBody() {
    return Scaffold(
      backgroundColor: AppColors.bg,
      appBar: AppBar(
        title: const Text('校内地图'),
        actions: [
          IconButton(
            onPressed: _boot,
            icon: const Icon(Icons.refresh, size: 20),
            tooltip: '刷新',
          ),
        ],
      ),
      // 「我的位置」放在地图内部（而不是 Scaffold 的 FAB）：FAB 会浮在底部详情面板上
      // 把文字压住（真机截图里发现的），放进地图右上角才是地图控件的常规位置。
      body: _checking
          ? const Center(child: CircularProgressIndicator())
          : _error != null
              ? EmptyState(
                  icon: Icons.map_outlined,
                  message: _error!,
                  onRetry: _boot,
                )
              : !_amapReady
                  ? EmptyState(
                      icon: Icons.map_outlined,
                      message: '后端还没配置高德 key，暂时无法显示真实地图。\n'
                          '配置 ershou.amap.js-key 后回来刷新即可。',
                      onRetry: _boot,
                    )
                  : _mapStack(),
    );
  }

  /// 地图 + 浮层：底部卡片浮在地图上（不再用 Column 占掉一截高度）。
  ///
  /// 之前是"地图 Expanded + 底部大面板"的 Column，面板占掉约 1/4 屏，
  /// 视野被压得很扁、字号又大，很不简约。现在改成地图铺满 + 底部悬浮小卡片。
  Widget _mapStack() {
    return Stack(
      children: [
        Positioned.fill(child: _webArea()),
        if (_loading)
          const Positioned(
            top: 12,
            left: 0,
            right: 0,
            child: Center(child: CircularProgressIndicator()),
          ),
        Positioned(
          top: 12,
          right: 12,
          child: FloatingActionButton.small(
            heroTag: 'map-locate',
            onPressed: _locateMe,
            tooltip: '我的位置',
            backgroundColor: Colors.white,
            foregroundColor: AppColors.brandDark,
            elevation: 2,
            child: const Icon(Icons.my_location, size: 19),
          ),
        ),
        // 附近点位（Redis GEO）：竖直小胶囊列表，只在定位后出现
        if (_nearby.isNotEmpty)
          Positioned(left: 10, top: 12, child: _nearbyColumn()),
        // 选中点位：底部悬浮小卡片
        if (_selected != null)
          Positioned(left: 10, right: 10, bottom: 12, child: _pointCard(_selected!)),
      ],
    );
  }

  Widget _webArea() {
    final controller = _web;
    if (controller == null) {
      return const Center(child: CircularProgressIndicator());
    }
    return WebViewWidget(controller: controller);
  }

  /// 附近点位：极简竖直列表，点一个即在地图上居中点亮
  Widget _nearbyColumn() {
    return Container(
      constraints: const BoxConstraints(maxWidth: 168),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.94),
        borderRadius: BorderRadius.circular(10),
        boxShadow: AppTheme.softShadow,
      ),
      padding: const EdgeInsets.symmetric(vertical: 6, horizontal: 4),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Padding(
            padding: EdgeInsets.fromLTRB(8, 2, 8, 4),
            child: Text('我附近',
                style: TextStyle(fontSize: 10.5, color: AppColors.inkFaint, fontWeight: FontWeight.w600)),
          ),
          Flexible(
            child: ListView.builder(
              shrinkWrap: true,
              padding: EdgeInsets.zero,
              itemCount: _nearby.length,
              itemBuilder: (context, i) {
                final n = _nearby[i];
                final dist = n.distanceMeters < 1000
                    ? '${n.distanceMeters.round()}m'
                    : '${(n.distanceMeters / 1000).toStringAsFixed(1)}km';
                return InkWell(
                  onTap: () {
                    final hit = _data.points.where((p) => p.id == n.id).toList();
                    if (hit.isEmpty) return;
                    setState(() => _selected = hit.first);
                    _centerOn(hit.first);
                  },
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 5),
                    child: Row(
                      children: [
                        Expanded(
                          child: Text(
                            n.name,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(fontSize: 12, color: AppColors.ink),
                          ),
                        ),
                        const SizedBox(width: 6),
                        Text(dist,
                            style: const TextStyle(fontSize: 10.5, color: AppColors.brandDark)),
                      ],
                    ),
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  /// 选中点位的悬浮卡片：名称 + 关键信息一行 + 一个按钮。尽量小。
  ///
  /// 合并组只影响**显示的名称与件数**；`_selected` 始终是精确点位，
  /// 所以点「看商品」用的是商家自己定的那个点位的 id（不会被代表点替换）。
  Widget _pointCard(MapPoint p) {
    final group = _selectedGroup;
    final merged = group != null && group.merged;
    final title = merged ? group.name : p.name;
    final count = merged ? group.itemCount : p.itemCount;
    return Material(
      color: Colors.white,
      elevation: 4,
      shadowColor: Colors.black26,
      borderRadius: BorderRadius.circular(12),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 10, 8, 10),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700, height: 1.3),
                  ),
                  const SizedBox(height: 3),
                  Row(
                    children: [
                      Text(
                        MapPoint.typeLabel(p.type),
                        style: const TextStyle(fontSize: 11.5, color: AppColors.inkSoft),
                      ),
                      const SizedBox(width: 8),
                      Text(
                        count > 0 ? '$count 件在售' : '暂无在售',
                        style: TextStyle(
                          fontSize: 11.5,
                          fontWeight: FontWeight.w600,
                          color: count > 0 ? AppColors.price : AppColors.inkFaint,
                        ),
                      ),
                      if (merged) ...[
                        const SizedBox(width: 8),
                        // 合并时说明"按哪个点算"：商品详情用的是精确点位
                        const Text(
                          '合并显示',
                          style: TextStyle(fontSize: 11, color: AppColors.brandDark),
                        ),
                      ],
                    ],
                  ),
                ],
              ),
            ),
            if (p.hasGeo)
              IconButton(
                onPressed: () => _openAmapNavigation(p),
                icon: const Icon(Icons.navigation_outlined, size: 19),
                tooltip: '怎么去（按商家定点的精确坐标）',
                visualDensity: VisualDensity.compact,
                color: AppColors.inkSoft,
              ),
            TextButton(
              onPressed: count == 0
                  ? null
                  : () => Navigator.of(context).push(
                        MaterialPageRoute<void>(builder: (_) => ItemsByPointPage(point: p)),
                      ),
              style: TextButton.styleFrom(
                padding: const EdgeInsets.symmetric(horizontal: 10),
                minimumSize: const Size(0, 34),
                tapTargetSize: MaterialTapTargetSize.shrinkWrap,
              ),
              child: Text(count == 0 ? '无在售' : '看商品',
                  style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
            ),
            IconButton(
              onPressed: () => setState(() {
                _selected = null;
                _selectedGroup = null;
              }),
              icon: const Icon(Icons.close, size: 16),
              visualDensity: VisualDensity.compact,
              color: AppColors.inkFaint,
            ),
          ],
        ),
      ),
    );
  }


  /// 用高德 URI 唤起导航/网页（**不消耗任何接口配额**）
  void _openAmapNavigation(MapPoint p) {
    final url = 'https://uri.amap.com/marker?position=${p.lng},${p.lat}'
        '&name=${Uri.encodeComponent(p.name)}&coordinate=gaode&callnative=1';
    _web?.loadRequest(Uri.parse(url));
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('已打开高德（返回可继续看地图）')),
    );
  }
}

/// 定位服务：geolocator 拿 WGS-84 → 转 GCJ-02（高德坐标系）。
///
/// 用原生定位而不是 WebView 里的 `navigator.geolocation`：后者在 Android WebView 里
/// 还要额外走 `onGeolocationPermissionsShowPrompt` 授权，而且拿到的依然是 WGS-84，
/// 交给原生做更直接，转换也是纯本地计算（不消耗高德配额）。
class LocationService {
  const LocationService();

  /// 返回 GCJ-02 的 [lng, lat]；无权限/超时/服务关闭时返回 null
  Future<List<double>?> currentGcj02() async {
    try {
      if (!await Geolocator.isLocationServiceEnabled()) return null;
      var permission = await Geolocator.checkPermission();
      if (permission == LocationPermission.denied) {
        permission = await Geolocator.requestPermission();
      }
      if (permission == LocationPermission.denied ||
          permission == LocationPermission.deniedForever) {
        return null;
      }
      final pos = await Geolocator.getCurrentPosition(
        locationSettings: const LocationSettings(
          accuracy: LocationAccuracy.high,
          timeLimit: Duration(seconds: 10),
        ),
      );
      return wgs84ToGcj02(pos.longitude, pos.latitude);
    } catch (_) {
      return null;
    }
  }
}
