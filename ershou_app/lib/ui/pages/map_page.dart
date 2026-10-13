import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart' show kDebugMode;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:geolocator/geolocator.dart';
import 'package:webview_flutter/webview_flutter.dart';

import '../../core/providers.dart';
import '../../core/utils/coord.dart';
import '../../core/utils/point_merge.dart';
import '../../data/api_repository2.dart' show AmapKeyState;
import '../../data/models.dart';
import '../../data/models2.dart';
import '../theme/app_theme.dart';
import '../widgets/map_nearby_window.dart';
import '../widgets/map_point_items_window.dart';
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

  /// 为什么画不出地图：区分"没配 key"与"连不上后端"（两者给用户的提示完全不同）
  AmapKeyState _keyState = AmapKeyState.unreachable;

  /// 选中点位的在售商品（地图上的商品小窗）。
  ///
  /// 为什么按点位 id 精确查而不是"拉一批再按名称过滤"：
  /// 同名点位会串在一起、超过一页就漏；而且点位查询本来就只有几条。
  List<Item> _pointItems = const [];
  bool _pointItemsLoading = false;
  /// 小窗当前展示的点位 id：切点位时旧商品不能先闪一下再换
  int? _pointItemsFor;

  /// 「我附近」窗口是否收起。默认展开；用户收起后**记到本地**，下次进地图还是收起的。
  bool _nearbyCollapsed = false;
  static const String _nearbyCollapsedKey = 'map.nearby.collapsed';

  @override
  void initState() {
    super.initState();
    // 先读回上次的折叠状态，再拉数据：避免"展开着闪一下再收起"
    _nearbyCollapsed =
        ref.read(localStoreProvider).uiFlag(_nearbyCollapsedKey);
    WidgetsBinding.instance.addPostFrameCallback((_) => _boot());
  }

  /// 折叠/展开「我附近」，并落盘（写失败不影响使用）
  void _toggleNearby() {
    setState(() => _nearbyCollapsed = !_nearbyCollapsed);
    unawaited(ref
        .read(localStoreProvider)
        .setUiFlag(_nearbyCollapsedKey, _nearbyCollapsed));
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

  /// 画不出地图时，按**真实原因**给提示。
  ///
  /// ⚠️ 2026-10-10 修：以前不管什么原因都显示"后端还没配置高德 key"，
  /// 而实际上最常见的原因是**手机连不上后端**（不同网段 / 地址过期 / 后端刚重启）。
  /// 那句文案把用户和开发者都带偏了 —— 后端明明配好了 key，却让人去 .env 里折腾。
  String _notReadyHint(AmapKeyState state) {
    switch (state) {
      case AmapKeyState.missingKey:
        return '后端还没配置高德 key，暂时无法显示真实地图。\n'
            '在后端 .env 里补 AMAP_JS_KEY 后回来刷新即可。';
      case AmapKeyState.unreachable:
        return '连不上后端，地图暂时打不开。\n'
            '请确认：后端已启动、App 里的服务器地址正确。\n'
            '（USB 调试时可在电脑上跑 scripts/mobile-connect.ps1 建隧道）';
      case AmapKeyState.configured:
        // 理论上不会走到这里（configured 时不会显示这张卡片）
        return '地图加载中…';
    }
  }

  Future<void> _boot() async {
    setState(() {
      _checking = true;
      _error = null;
    });
    final repo = ref.read(mapRepositoryProvider);
    // 三态而不是布尔：见 MapRepository.amapKeyState 的说明 ——
    // 原来的 `catch (_) { return false; }` 会把"连不上后端"也报成"没配 key"，
    // 用户照着那句提示去 .env 里折腾 key，而真正的问题是网络不通。
    final keyState = await repo.amapKeyState();
    if (!mounted) return;
    _keyState = keyState;
    if (keyState != AmapKeyState.configured) {
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
        // 气泡上的件数用「在售」口径，和点开小窗能看到的条数一致
        'itemCount': g.members.fold<int>(0, (sum, m) => sum + _onSaleOf(m)),
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
      // 就地拉该点位的商品，填进小窗（不再跳整页）
      unawaited(_loadPointItems(hit));
      return;
    }
  }

  /// 拉某个点位的在售商品（小窗用）。
  ///
  /// `pointItemCount` 是后端聚合出来的在售件数：为 0 时直接跳过请求，
  /// 免得每次点空点位都打一次接口。
  /// 小窗加载：用"买家可见数"决定要不要请求，避免点空点位白打一次接口
  Future<void> _loadPointItems(MapPoint p) async {
    if (_onSaleOf(p) <= 0) {
      setState(() {
        _pointItems = const [];
        _pointItemsFor = p.id;
        _pointItemsLoading = false;
      });
      return;
    }
    setState(() {
      _pointItemsLoading = true;
      _pointItemsFor = p.id;
      // 立刻清空：否则切到另一个点位时，会先显示上一个点位的商品再被替换
      _pointItems = const [];
    });
    try {
      // 合并气泡要查**组内所有点位**的商品，不能只查代表点 ——
      // 否则气泡上写着"3 件"、小窗里只有 1 件（2026-10-11 用户报的就是这个）。
      // 口径由 PointGroup.itemQueryPointIds() 统一提供，有单元测试钉着。
      final group = _selectedGroup;
      final pointIds = group != null ? group.itemQueryPointIds() : <int>[p.id];
      final page = await ref
          .read(itemRepositoryProvider)
          .page(pageSize: 12, preferPointIds: pointIds);
      if (!mounted || _pointItemsFor != p.id) return; // 已经切走了，丢弃这次结果
      setState(() {
        _pointItems = page.records.where((i) => i.onSale).toList();
        _pointItemsLoading = false;
      });
    } catch (_) {
      if (!mounted || _pointItemsFor != p.id) return;
      setState(() {
        _pointItems = const [];
        _pointItemsLoading = false;
      });
    }
  }

  /// 关闭小窗与点位卡片（点小窗右上角 ✕ 或点位卡片 ✕）
  void _clearSelection() {
    setState(() {
      _selected = null;
      _selectedGroup = null;
      _pointItems = const [];
      _pointItemsFor = null;
    });
  }

  /// 点位"买家真的能看到几件"。
  ///
  /// 后端给了两个数：`itemCount` 是"还有戏"（草稿/审核中/在售/已锁定），
  /// `onSaleCount` 只有在售。**展示给买家的一律用后者** ——
  /// 用前者会出现"气泡写着 11 件在售、点开小窗只有 10 件"（差的那些还在审核中，
  /// 买家本来就看不到）。
  ///
  /// 为什么兜底成 `itemCount` 而不是 0：后端与 App 不是同时升级的，
  /// 老后端不下发 `onSaleCount`（全是 0），兜成 0 会让小窗永远不显示 ——
  /// 那比"数字差一两件"严重得多。
  static int _onSaleOf(MapPoint p) => p.onSaleCount > 0 ? p.onSaleCount : p.itemCount;

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
                      message: _notReadyHint(_keyState),
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
        // 选中点位：商品小窗 + 点位信息条（都在底部，小窗在上、信息条在下）
        if (_selected != null)
          Positioned(
            left: 10,
            right: 10,
            bottom: 12,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                _pointItemsWindow(_selected!),
                const SizedBox(height: 8),
                _pointCard(_selected!),
              ],
            ),
          ),
      ],
    );
  }

  /// 商品小窗：点位下的在售商品，横向滑动。
  ///
  /// 实现抽到 [MapPointItemsWindow]（独立组件），这样布局能被 widget 测试直接覆盖 ——
  /// 它的高度是固定值，"固定高度装不下内容"正是它出过的 bug：
  /// 132 的窗口装 134 的内容，真机上 Flutter 会把 "BOTTOM OVERFLOWED BY 3.0 PIXELS"
  /// 的黄黑条直接画出来，价格那行被挤掉。详见该组件的类注释。
  Widget _pointItemsWindow(MapPoint p) {
    final loading = _pointItemsLoading || _pointItemsFor != p.id;
    return MapPointItemsWindow(items: _pointItems, loading: loading);
  }

  Widget _webArea() {
    final controller = _web;
    if (controller == null) {
      return const Center(child: CircularProgressIndicator());
    }
    return WebViewWidget(controller: controller);
  }

  /// 附近点位窗口（实现抽到 [MapNearbyWindow]，这样折叠交互能被 widget 测试直接覆盖）。
  ///
  /// 折叠状态在这里持有并落盘：用户收起来就是不想看，重进地图不该又弹开。
  /// 高度上限与收起态胶囊的样式都在那个组件里，改样式不会牵动这里。
  Widget _nearbyColumn() {
    return MapNearbyWindow(
      points: _nearby,
      collapsed: _nearbyCollapsed,
      onToggle: _toggleNearby,
      onPick: (n) {
        final hit = _data.points.where((p) => p.id == n.id).toList();
        if (hit.isEmpty) return;
        setState(() => _selected = hit.first);
        _centerOn(hit.first);
      },
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
    // 件数用「在售」口径（_onSaleOf），与小窗里实际能看到的条数一致；
    // 合并组按组内各点求和。
    final count = merged
        ? group.members.fold<int>(0, (sum, m) => sum + _onSaleOf(m))
        : _onSaleOf(p);
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
              child: Text(count == 0 ? '无在售' : '看全部',
                  style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
            ),
            IconButton(
              onPressed: _clearSelection,
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